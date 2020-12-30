package org.abs_models.backend.c.compiler;

import org.abs_models.backend.c.codegen.CFile;
import org.abs_models.backend.c.codegen.CFunctionDecl;
import org.abs_models.backend.c.codegen.CFunctionParam;
import org.abs_models.frontend.ast.DataConstructor;
import org.abs_models.frontend.typechecker.DataTypeType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DataTypeRepresentation implements TypeRepresentation {
    public final String cname;
    public final DataTypeType type;
    public final List<Variant> variants;
    boolean isDeclared = false;

    public DataTypeRepresentation(DataTypeType type, String cname, List<Variant> variants) {
        this.type = type;
        this.cname = cname;
        this.variants = variants;
    }

    @Override
    public String getCType() {
        if (!isDeclared) throw new RuntimeException("Using type before it is declared");
        return cname;
    }

    @Override
    public void declare(CFile cFile) throws IOException {
        cFile.writeLine("typedef struct " + cname + "{");
        cFile.writeLine("int tag;");
        cFile.writeLine("union {");
        for (Variant variant : variants) {
            if (!variant.isRepresentable()) continue;
            cFile.writeLine("struct {");
            for (Field field : variant.fields) {
                if (field.repr.isRepresentable()) {
                    cFile.writeLine(field.repr.getCType() + " " + field.cname + ";");
                }
            }
            cFile.writeLine("} " + variant.cname + ";");
        }
        cFile.writeLine("} data;");
        cFile.writeLine("} " + cname + ";");

        CFunctionDecl initzero = new CFunctionDecl(cname + "_initzero", "static void", List.of(
            new CFunctionParam("val", cname + "*")
        ));
        cFile.startFunction(initzero);
        Variant var = variants.get(0);
        if (var != null) {
            cFile.writeLine("val->tag = " + var.tag + ";");
            for (Field field : var.fields) {
                field.repr.initZero(cFile, "val->data." + var.cname + "." + field.cname);
            }
        }
        cFile.stopFunction();

        CFunctionDecl deinit = new CFunctionDecl(cname + "_deinit", "static void", List.of(
            new CFunctionParam("val", cname + "*")
        ));
        cFile.startFunction(deinit);
        cFile.writeLine("switch (val->tag) {");
        for (Variant variant : variants) {
            cFile.writeLine("case " + variant.tag + ":");
            for (Field field : variant.fields) {
                field.repr.deinit(cFile, "val->data." + variant.cname + "." + field.cname);
            }
            cFile.writeLine("break;");
        }
        cFile.writeLine("}");
        cFile.stopFunction();

        CFunctionDecl initcopy = new CFunctionDecl(cname + "_initcopy", "static void", List.of(
            new CFunctionParam("val", cname + "*"),
            new CFunctionParam("other", cname)
        ));

        cFile.startFunction(initcopy);
        cFile.writeLine("val->tag = other.tag;");
        cFile.writeLine("switch (other.tag) {");
        for (Variant variant : variants) {
            cFile.writeLine("case " + variant.tag + ":");
            for (Field field : variant.fields) {
                String path = "data." + variant.cname + "." + field.cname;
                field.repr.initCopy(cFile, "val->" + path, "other." + path);
            }
            cFile.writeLine("break;");
        }
        cFile.writeLine("}");
        cFile.stopFunction();

        CFunctionDecl tostring = new CFunctionDecl(cname + "_tostring", "static void", List.of(
            new CFunctionParam("result", "absstr*"),
            new CFunctionParam("data", cname)
        ));
        cFile.startFunction(tostring);
        cFile.writeLine("switch (data.tag) {");
        for (Variant variant : variants) {
            cFile.writeLine("case " + variant.tag + ":");
            byte[] bytes = variant.dataConstructor.getName().getBytes(StandardCharsets.UTF_8);
            String cString = cFile.encodeCString(bytes);
            cFile.writeLine("absstr_literal(result," + cString + "," + bytes.length + ");");
            cFile.writeLine("break;");
        }
        cFile.writeLine("}");
        cFile.stopFunction();

        CFunctionDecl compare = new CFunctionDecl(cname + "_compare", "static int", List.of(
            new CFunctionParam("a", cname),
            new CFunctionParam("b", cname)
        ));
        cFile.startFunction(compare);
        cFile.writeLine("int cmp;");
        cFile.writeLine("if (a.tag != b.tag) return a.tag - b.tag;");
        cFile.writeLine("switch (a.tag) {");
        for (Variant variant : variants) {
            cFile.writeLine("case " + variant.tag + ":");
            for (Field field : variant.fields) {
                String path = "data." + variant.cname + "." + field.cname;
                cFile.writeLine("cmp = " + field.repr.getCType() + "_compare(a." + path + ", b." + path + ");");
                cFile.writeLine("if (cmp != 0) return cmp;");
            }
            cFile.writeLine("break;");
        }
        cFile.writeLine("}");
        cFile.writeLine("return 0;");
        cFile.stopFunction();

        isDeclared = true;
    }

    public Variant findVariant(DataConstructor dataConstructor) {
        for (Variant variant : variants) {
            if (variant.dataConstructor == dataConstructor) return variant;
        }
        throw new RuntimeException("Unknown data constructor: " + dataConstructor);
    }

    public void setVariant(CFile cFile, String ident, Variant variant) throws IOException {
        cFile.writeLine(ident + ".tag = " + variant.tag + ";");
    }

    public void setVariantField(CFile cFile, String ident, Variant variant, int fieldIdx, String value) throws IOException {
        Field field = variant.fields.get(fieldIdx);
        String fieldIdent = "(" + ident + ").data." + variant.cname + "." + field.cname;
        field.repr.initCopy(cFile, fieldIdent, value);
    }

    static class Variant {
        public final DataConstructor dataConstructor;
        public final String cname;
        public final int tag;
        public final List<Field> fields;

        public Variant(DataConstructor dataConstructor, String cname, int tag, List<Field> fields) {
            this.dataConstructor = dataConstructor;
            this.cname = cname;
            this.tag = tag;
            this.fields = fields;
        }

        public boolean isRepresentable() {
            for (Field field : fields) {
                if (field.repr.isRepresentable()) return true;
            }
            return false;
        }
    }

    static class Field {
        TypeRepresentation repr;
        String cname;

        public Field(TypeRepresentation repr, String cname) {
            this.repr = repr;
            this.cname = cname;
        }
    }
}