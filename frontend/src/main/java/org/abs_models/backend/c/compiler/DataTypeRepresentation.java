package org.abs_models.backend.c.compiler;

import org.abs_models.backend.c.codegen.CFile;
import org.abs_models.frontend.ast.DataConstructor;
import org.abs_models.frontend.typechecker.DataTypeType;

import java.io.IOException;
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
        return "struct " + cname;
    }

    @Override
    public void declare(CFile cFile) throws IOException {
        cFile.writeLine("struct " + cname + "{");
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
            cFile.writeLine("} " + variant.cname);
        }
        cFile.writeLine("} data;");
        cFile.writeLine("};");
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
        field.repr.setValue(cFile, fieldIdent, value);
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
