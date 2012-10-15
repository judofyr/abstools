/** 
 * Copyright (c) 2009-2011, The HATS Consortium. All rights reserved. 
 * This file is licensed under the terms of the Modified BSD License.
 */
package abs.backend.java.codegeneration.dynamic;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;


import abs.backend.java.JavaBackend;
import abs.backend.java.codegeneration.JavaCodeGenerationException;
import abs.backend.java.codegeneration.JavaCodeStream;
import abs.backend.java.codegeneration.JavaGeneratorHelper;
import abs.backend.java.JavaBackendConstants;
import abs.backend.java.codegeneration.JavaCode;
import abs.backend.java.lib.runtime.ABSBuiltInFunctions;
import abs.backend.java.lib.runtime.ABSClosure;
import abs.backend.java.lib.runtime.ABSDynamicClass;
import abs.backend.java.lib.runtime.ABSDynamicObject;
import abs.backend.java.lib.runtime.ABSDynamicProduct;
import abs.backend.java.lib.runtime.ABSField;
import abs.backend.java.lib.runtime.ABSFut;
import abs.backend.java.lib.runtime.ABSRuntime;
import abs.backend.java.lib.runtime.AbstractAsyncCall;
import abs.backend.java.lib.runtime.Task;
import abs.backend.java.lib.types.ABSBool;
import abs.backend.java.lib.types.ABSValue;
import abs.frontend.ast.ASTNode;
import abs.frontend.ast.AssignStmt;
import abs.frontend.ast.AsyncCall;
import abs.frontend.ast.AwaitStmt;
import abs.frontend.ast.ClassDecl;
import abs.frontend.ast.Decl;
import abs.frontend.ast.DeltaDecl;
import abs.frontend.ast.DeltaID;
import abs.frontend.ast.ExpGuard;
import abs.frontend.ast.Feature;
import abs.frontend.ast.FieldDecl;
import abs.frontend.ast.FnApp;
import abs.frontend.ast.FunctionDecl;
import abs.frontend.ast.LetExp;
import abs.frontend.ast.List;
import abs.frontend.ast.MethodImpl;
import abs.frontend.ast.MethodSig;
import abs.frontend.ast.ParamDecl;
import abs.frontend.ast.ParametricDataTypeDecl;
import abs.frontend.ast.ParametricFunctionDecl;
import abs.frontend.ast.Product;
import abs.frontend.ast.ProductAdaptation;
import abs.frontend.ast.PureExp;
import abs.frontend.ast.ReturnStmt;
import abs.frontend.ast.Stmt;
import abs.frontend.ast.SyncCall;
import abs.frontend.ast.TypeParameterDecl;
import abs.frontend.ast.TypedVarOrFieldDecl;
import abs.frontend.ast.VarDecl;
import abs.frontend.ast.VarOrFieldDecl;
import abs.frontend.ast.VarOrFieldUse;
import abs.frontend.ast.VarUse;
import abs.frontend.typechecker.Type;
import abs.frontend.typechecker.TypeCheckerHelper;
import beaver.Symbol;

public class DynamicJavaGeneratorHelper {

    private static final String FLI_METHOD_PREFIX = "fli_";

    public static void generateHelpLine(ASTNode<?> node, PrintStream stream) {
        JavaGeneratorHelper.generateHelpLine(node, stream);
    }

    public static void generateArgs(PrintStream stream, List<PureExp> args, java.util.List<Type> types) {
        generateArgs(stream, null, args, types);
    }

    public static void generateArgs(PrintStream stream, String firstArg, List<PureExp> args, java.util.List<Type> types) {
        stream.print("(");
        boolean first = true;

        if (firstArg != null) {
            stream.print(firstArg);
            first = false;
        }

        for (int i = 0; i < args.getNumChild(); i++) {
            PureExp e = args.getChild(i);
            if (!first)
                stream.print(", ");
            e.generateJavaDynamic(stream);
            if (types.get(i).isIntType() && e.getType().isRatType())
                stream.print(".truncate()");
            first = false;
        }
        stream.print(")");

    }

    public static void generateParamArgs(PrintStream stream, List<ParamDecl> params) {
        generateParamArgs(stream, null, params);
    }
    public static void generateParamArgs(PrintStream stream, String firstArg, List<ParamDecl> params) {
        stream.print("(");
        boolean first = true;

        if (firstArg != null) {
            stream.print(firstArg);
            first = false;
        }

        for (ParamDecl d : params) {
            if (!first)
                stream.print(", ");
            stream.print(JavaBackend.getVariableName(d.getName()));
            first = false;
        }
        stream.print(")");
    }

    public static void generateParams(PrintStream stream, List<ParamDecl> params) {
        generateParams(stream, null, params);
    }

    public static void generateParams(PrintStream stream, String firstArg, List<ParamDecl> params) {
        stream.print("(");

        boolean first = true;
        if (firstArg != null) {
            stream.print(firstArg);
            first = false;
        }

        for (ParamDecl d : params) {
            if (!first)
                stream.print(", ");
            // stream.print("final ");
            d.generateJavaDynamic(stream);
            first = false;
        }
        stream.print(")");
    }

    public static void generateTypeParameters(PrintStream stream, Decl dtd,
            boolean plusExtends) {
        List<TypeParameterDecl> typeParams = null;
        if (dtd instanceof ParametricDataTypeDecl) {
            typeParams = ((ParametricDataTypeDecl)dtd).getTypeParameters();
        } else
            if (dtd instanceof ParametricFunctionDecl) {
                typeParams = ((ParametricFunctionDecl)dtd).getTypeParameters();
            } else
                return;
        if (typeParams.getNumChild() > 0) {
            stream.print("<");
            boolean isFirst = true;
            for (TypeParameterDecl d : typeParams) {
                if (isFirst)
                    isFirst = false;
                else
                    stream.print(", ");
                stream.print(d.getName());
                if (plusExtends)
                    stream.print(" extends " + ABSValue.class.getName());
            }
            stream.print(">");
        }
    }

    public static void generateBuiltInFnApp(PrintStream stream, FnApp app) {
        FunctionDecl d = (FunctionDecl) app.getDecl();
        String name = d.getName();
        stream.print(ABSBuiltInFunctions.class.getName() + "." + name);
        generateArgs(stream, app.getParams(), TypeCheckerHelper.getTypesFromExp(app.getParams()));
    }

    public static String getDebugString(Stmt stmt) {
        return getDebugString(stmt, stmt.getStart());
    }

    public static String getDebugString(Stmt stmt, int pos) {
        int line = Symbol.getLine(pos);
        String fileName = stmt.getCompilationUnit().getFileName().replace("\\", "\\\\");
        return "if (thisP.__ABS_getRuntime().debuggingEnabled()) thisP.__ABS_getRuntime().nextStep(\""
        + fileName + "\"," + line + ");";
    }

    public static void generateMethodSig(PrintStream stream, MethodSig sig, boolean async) {
        generateMethodSig(stream, sig, async, "", "");
    }

    public static void generateMethodSig(PrintStream stream, MethodSig sig, boolean async, String modifier, String prefix) {
        DynamicJavaGeneratorHelper.generateHelpLine(sig,stream);
        stream.print("public " + modifier + " ");
        if (async) {
            prefix = "async_";
            stream.print(ABSFut.class.getName()+"<");
        }

        sig.getReturnType().generateJavaDynamic(stream);

        if (async)
            stream.print(">");
        stream.print(" " + prefix + JavaBackend.getMethodName(sig.getName()));
        DynamicJavaGeneratorHelper.generateParams(stream, sig.getParams());
    }

    public static void generateAsyncMethod(PrintStream stream, MethodImpl method) {
        final MethodSig sig = method.getMethodSig();
        generateMethodSig(stream, sig, true, "final","");
        stream.println("{");
        stream.println("return (" + ABSFut.class.getName() + ")");
        generateAsyncCall(stream, "this", null, method.getContextDecl().getType(), null, sig.getParams(), 
                TypeCheckerHelper.getTypes(sig.getParams()),sig.getName());
        stream.println(";");
        stream.println("}");
    }


    public static void generateAsyncCall(PrintStream stream, AsyncCall call) {
        final PureExp callee = call.getCallee();
        final List<PureExp> params = call.getParams();
        final String method = call.getMethod();

        generateAsyncCall(stream, null, callee, callee.getType(), params, null, 
                TypeCheckerHelper.getTypesFromExp(params), method);

    }

    private static void generateAsyncCall(PrintStream stream, final String calleeString, 
            final PureExp callee, final Type calleeType, final List<PureExp> args, 
            final List<ParamDecl> params,
            final java.util.List<Type> paramTypes,
            final String method) 
    {
        stream.print(ABSRuntime.class.getName() + ".getCurrentRuntime().asyncCall(");
        stream.print("new " + AbstractAsyncCall.class.getName() + "<" + ABSDynamicObject.class.getName() + ">(thisP,");
        stream.print("(" + ABSDynamicObject.class.getName() + ")" + ABSRuntime.class.getName() + ".checkForNull(");
        if (calleeString != null)
            stream.print(calleeString);
        else
            callee.generateJavaDynamic(stream);
        stream.print(")) {");
        for (int i = 0; i < paramTypes.size(); i++) {
            stream.print(ABSValue.class.getName()+" arg" + i + ";");
        }

        generateTaskGetArgsMethod(stream, paramTypes.size());
        generateTaskInitMethod(stream, paramTypes);

        stream.print("public java.lang.String methodName() { return \"" + method + "\"; }");

        stream.print("public Object execute() {");
        stream.print("return ((" + ABSDynamicObject.class.getName() + ")target).dispatch(");
        generateArgStringList(stream, "\"" + method + "\"", paramTypes.size());
        stream.print(");");
        stream.println("}");
        stream.println("}");
        stream.print(".init");
        if (args != null)
            DynamicJavaGeneratorHelper.generateArgs(stream,args, paramTypes);
        else
            DynamicJavaGeneratorHelper.generateParamArgs(stream, params);
        stream.print(")");
    }

    public static void generateSyncCall(PrintStream stream, SyncCall call) {
        stream.print("((" + ABSDynamicObject.class.getName() + ")" + ABSRuntime.class.getName() + ".checkForNull(");
        call.getCallee().generateJavaDynamic(stream);
        stream.print("))");
        stream.print(".dispatch");
        DynamicJavaGeneratorHelper.generateArgs(stream, "\"" + call.getMethod() + "\"", call.getParams(), TypeCheckerHelper.getTypesFromExp(call.getParams()));
    }
    
    
    private static void generateTaskInitMethod(PrintStream stream, final java.util.List<Type> paramTypes) {
        stream.print("public "+abs.backend.java.lib.runtime.AsyncCall.class.getName()+"<?> init(");
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) stream.print(",");
            stream.print(ABSValue.class.getName()+" _arg"+i);
        }
        stream.print(") {");
        for (int i = 0; i < paramTypes.size(); i++) {
            stream.print("arg"+i+" = _arg"+i+";");
        }
        stream.print(" return this; }");
    }

    private static void generateTaskGetArgsMethod(PrintStream stream, final int n) {
        stream.print("public java.util.List<"+ABSValue.class.getName()+"> getArgs() { return java.util.Arrays.asList(new "+ABSValue.class.getName()+"[] { ");
        generateArgStringList(stream, n);
        stream.print(" }); }");
    }

    private static void generateArgStringList(PrintStream stream, String init, final int n) {
        stream.print(init);
        for (int i = 0; i < n; i++) {
            stream.print(",");
            stream.print("arg" + i);
        }
    }
    private static void generateArgStringList(PrintStream stream, final int n) {
        for (int i = 0; i < n; i++) {
            if (i > 0) stream.print(",");
            stream.print("arg" + i);
        }
    }


    public static void generateClassDecl(PrintStream stream, final ClassDecl decl) {
//        if (decl.isForeign()) {
//            // generate standard code
//            new abs.backend.java.codegeneration.ClassDeclGenerator("", stream, decl).generate();
//        } else {
            // generate dynamic/untyped code
            new ClassDeclGenerator(stream, decl).generate();
//        }
    }

    public static void generateMethodImpl(PrintStream stream, final MethodImpl m) {
        // methods are mapped to static inner classes
        DynamicJavaGeneratorHelper.generateHelpLine(m, stream);
        stream.println("public static class " + m.getMethodSig().getName() + " extends " + ABSClosure.class.getName() + " {");
        stream.println("private static " + ABSClosure.class.getName() + " instance;");
        stream.println("public static " + ABSClosure.class.getName() + " singleton() {");
        stream.println("if (instance == null) { instance = new " + m.getMethodSig().getName() + "(); }");
        stream.println("return instance;");
        stream.println("}");

        stream.println("public " + ABSValue.class.getName() + " exec(final " + ABSDynamicObject.class.getName() + " thisP, " + ABSValue.class.getName() + "... args) {");
        for (int i = 0; i < m.getMethodSig().getNumParam(); i++) {
            ParamDecl d = m.getMethodSig().getParam(i);
            d.generateJavaDynamic(stream);
            stream.print(" = ");
            if (!d.getAccess().getType().isReferenceType()) {
                stream.print("(");
                d.getAccess().generateJavaDynamic(stream);
                stream.print(")");
            }
            stream.println("args[" + i + "];");
        }
        if (m.isForeign()) {
            stream.println("// TODO call " + FLI_METHOD_PREFIX + JavaBackend.getMethodName(m.getMethodSig().getName()) + " in outer class");
        } else {
            generateMethodBody(stream, m, false);
        }
        stream.println("}");
        stream.println("}");
    }

    public static void generateField(PrintStream stream, final FieldDecl f) {
        // fields are mapped to subclasses of ABSField (because they need to store an initialisation routine)
        String name = JavaBackend.getVariableName("field$" + f.getName());
        DynamicJavaGeneratorHelper.generateHelpLine(f, stream);
        stream.println("public static class " + name + " extends " + ABSField.class.getName() + " {");
        stream.println("private static " + ABSField.class.getName() + " instance;");
        stream.println("public static " + ABSField.class.getName() + " singleton() {");
        stream.println("if (instance == null) { instance = new " + name + "(); }");
        stream.println("return instance;");
        stream.println("}");

        stream.println("public " + ABSValue.class.getName() + " init(final " + ABSDynamicObject.class.getName() + " thisP " + ") {");
        stream.print("return ");
        if (f.hasInitExp()) {
            f.getInitExp().generateJavaDynamic(stream);
        } else {
            stream.print("null");
        }
        stream.println(";");
        stream.println("}");
        stream.println("}");
    }

    
    public static void fieldUse(PrintStream stream, VarOrFieldUse f) {
        stream.print("(");
        if (! f.getType().isReferenceType()) {
            stream.print("(" + JavaBackend.getQualifiedString(f.getType()) + ")");
        }
        stream.print("thisP.getFieldValue_Internal(\"" + f.getName() + "\"))");
    }

    private static void generateMethodBody(PrintStream stream, final MethodImpl m, boolean isFliMethod) {
        boolean addReturn = false;
        if (m.getMethodSig().getReturnType().getType().isUnitType()) {
            if (m.getBlock().getNumStmt() == 0 ||
                    (! (m.getBlock().getStmt(m.getBlock().getNumStmt() - 1) instanceof ReturnStmt))) {
                addReturn = true;
            }
        }

        stream.println("{");
        stream.println("thisP.__ABS_checkSameCOG();");

        if (!isFliMethod && m.isForeign()) {
            // FIXME
//            stream.print("return this.");
//            stream.print(FLI_METHOD_PREFIX + JavaBackend.getMethodName(m.getMethodSig().getName()));
//            DynamicJavaGeneratorHelper.generateParamArgs(stream, m.getMethodSig().getParams());
//            stream.println(";");
        } else {
            stream.println("if (thisP.__ABS_getRuntime().debuggingEnabled()) {");
            stream.println(Task.class.getName() + "<?> __ABS_currentTask = thisP.__ABS_getRuntime().getCurrentTask();");
            stream.println("__ABS_currentTask.newStackFrame(thisP, \"" + m.getMethodSig().getName() + "\");");
            for (ParamDecl d : m.getMethodSig().getParams()) {
                stream.print("__ABS_currentTask.setLocalVariable(");
                stream.println("\"" + d.getName() + "\", " + d.getName() + ");");
            }
            stream.println("}");
            m.getBlock().generateJavaDynamic(stream, addReturn);

        }
        stream.println("}");
    }

    public static void generateFLIMethod(PrintStream stream, MethodImpl m) {
//        JavaGeneratorHelper.generateMethodSig("", stream, m.getMethodSig(), false, "", FLI_METHOD_PREFIX);

        // Generate method signature
        MethodSig sig = m.getMethodSig();
        DynamicJavaGeneratorHelper.generateHelpLine(sig, stream);
        stream.print("public ");
        sig.getReturnType().generateJavaDynamic(stream);
        stream.print(" " + FLI_METHOD_PREFIX + JavaBackend.getMethodName(sig.getName()));
        DynamicJavaGeneratorHelper.generateParams(stream, sig.getParams());

        // TODO now generate method body
        // JavaGeneratorHelper.generateMethodBody("", stream, m, true);
        stream.println(" {");
        stream.println("// TODO generate body");
        stream.println("}");
    }

    /**
     * removes the gen folder and all its contents
     * @param code
     */
    public static void cleanGenFolder(JavaCode code) {
        JavaGeneratorHelper.cleanGenFolder(code);
    }

    public static void printEscapedString(PrintStream stream, String content) {
        JavaGeneratorHelper.printEscapedString(stream, content);
    }

    public static void generateExprGuard(ExpGuard expGuard, PrintStream beforeAwaitStream, PrintStream stream) {
        PureExp expr = expGuard.getPureExp();

        replaceLocalVariables((PureExp)expr.copy(), beforeAwaitStream);

        stream.print("new "+JavaBackendConstants.EXPGUARD+"() { public "+ABSBool.class.getName()+" evaluateExp() { return ");
        expGuard.getPureExp().generateJavaDynamic(stream);
        stream.print("; }}");

    }



    /**
     * replace all uses of local variables and parameters by a use of a newly introduced
     * temporary final local variable 
     */
    private static void replaceLocalVariables(ASTNode<?> astNode, PrintStream beforeAwaitStream) {
        if (isLocalVarUse(astNode)) {
            VarUse v = (VarUse) astNode;
            replaceVarUse(beforeAwaitStream, v, (TypedVarOrFieldDecl) v.getDecl());                
        } else {
            // process children:
            for (int i=0; i < astNode.getNumChild(); i++) {
                ASTNode<?> child = astNode.getChild(i);
                replaceLocalVariables(child, beforeAwaitStream);
            }
        }
    }

    /***
     * checks if astNode is a use of a local variable or parameter
     */
    private static boolean isLocalVarUse(ASTNode<?> astNode) {
        if (astNode instanceof VarUse) {
            VarUse v = (VarUse) astNode;
            VarOrFieldDecl decl = v.getDecl();
            if (decl instanceof VarDecl || decl instanceof ParamDecl) {
                return !(decl.getParent() instanceof LetExp);
            }
        }
        return false;
    }


    private static long tempCounter = 0;

    /**
     * replaces a varUse v of the local variable vDecl by a new temporary variable, which will be
     * written to beforeAwaitStream      
     */
    private static void replaceVarUse(PrintStream beforeAwaitStream, VarUse v, TypedVarOrFieldDecl vDecl) {
        String name = JavaBackend.getVariableName(vDecl.getName());
        String tempName = "temp$" + tempCounter + "$" + name;
        tempCounter = Math.max(tempCounter + 1, 0);
        // copy value of variable to temporary, final variable
        beforeAwaitStream.print("final ");
        vDecl.getAccess().generateJavaDynamic(beforeAwaitStream);
        beforeAwaitStream.print(" " + tempName + " = " + name + ";");
        // replace variable name with name of temporary variable
        v.setName(tempName);
    }

    public static void generateAwaitStmt(AwaitStmt awaitStmt, PrintStream stream) {
        OutputStream exprOStream = new ByteArrayOutputStream();
        try {
            PrintStream exprStream = new JavaCodeStream(exprOStream);
            // Necessary temporary variables are written to "stream" and the 
            // await-expression is written to exprStream
            awaitStmt.getGuard().generateJavaGuardDynamic(stream, exprStream);
            stream.print(JavaBackendConstants.ABSRUNTIME + ".await(");
            stream.print(exprOStream.toString());
            stream.println(");");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void assign(PrintStream stream, AssignStmt assign) {
        VarOrFieldUse vfu = assign.getVar();
        boolean truncateNeeded
            = vfu.getType().isIntType() && assign.getValue().getType().isRatType();
        if (vfu.isField()) {
            stream.print("thisP.setFieldValue(\"" + vfu.getName() + "\", ");
            if (truncateNeeded) stream.print("(");
            assign.getValue().generateJavaDynamic(stream);
            if (truncateNeeded) stream.print(").truncate()");
            stream.println(");");
        } else {
            vfu.generateJavaDynamic(stream);
            stream.print(" = ");
            if (truncateNeeded) stream.print("(");
            if (! assign.getVar().getType().isReferenceType()) {
                // cast ABSValue to more specific type
                stream.print("(");
                stream.print(JavaBackend.getQualifiedString(assign.getVar().getType()));
                stream.print(")");
            }
            assign.getValue().generateJavaDynamic(stream);
            if (vfu.getType().isIntType() && assign.getValue().getType().isRatType()) {
                stream.print(").truncate()");
            }
            stream.println(";");
        }

        if (assign.getVar() instanceof VarUse) {
            stream.print("if (thisP.__ABS_getRuntime().debuggingEnabled()) ");
            stream.print("thisP.__ABS_getRuntime().getCurrentTask().setLocalVariable(\"" + assign.getVar().getName() + "\", ");
            assign.getVar().generateJavaDynamic(stream);
            stream.println(");");
        }

    }
    
    public static void generateDeltaApplication(DeltaDecl delta, JavaCode.Package pkg, ArrayList<String> classes)
            throws IOException, JavaCodeGenerationException {
        
        PrintStream stream = null;
        String name = "Application";
        try {
            File file = pkg.createJavaFile(name);
            stream = new JavaCodeStream(new BufferedOutputStream(new FileOutputStream(file)));
            stream.println("package " + pkg.packageName + ";");
            stream.println("public class " + name + " {");
            stream.println("// Run this method to apply delta: " + pkg.packageName + "." + name + ".apply();");
            stream.println("public static void apply() {");
            for (String cls : classes) {
                stream.println(pkg.packageName + "." + cls + ".apply();");
            }
            stream.println("}");
            stream.println("}");
        } finally {
            if (stream != null)
                stream.close();
        }
    }
    
    public static void generateProduct(PrintStream stream, Product prod) {

        stream.println("private static " + ABSDynamicProduct.class.getName() + " instance;");
        stream.println("public static " + ABSDynamicProduct.class.getName() + " singleton() {");
        stream.println("if (instance == null) {");
        stream.println("instance = new " + ABSDynamicProduct.class.getName() + "();");
        stream.println("instance.setName(\"" + prod.getName() + "\");");

        // Features
        for (Feature feature : prod.getFeatures())
            stream.println("instance.addFeature(\"" + feature.getName() + "\");");
        
        // Adaptations
        for (ProductAdaptation ad : prod.getProductAdaptations()) {
            stream.print("instance.addAdaptation(\"" + ad.getProductID() + "\", ");
            stream.print("new " + ArrayList.class.getName() + "<" + String.class.getName() + ">(" + Arrays.class.getName() + ".asList(");
            String delim = "";
            for (DeltaID delta : ad.getDeltaIDs()) {
                stream.print(delim + "\"" + delta.getName() + "\"");
                delim = ", ";
            }
            stream.print("))");
            stream.println(");");
        }
        
        stream.println("}");
        stream.println("return instance;");
        stream.println("}");
    }
    
}
