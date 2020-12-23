package org.abs_models.backend.c.compiler;

import org.abs_models.backend.c.codegen.CFile;
import org.abs_models.backend.rvsdg.abs.ComparisonNode;

import java.io.IOException;

/**
 * A C representation of a Type.
 */
public interface TypeRepresentation {
    default boolean isRepresentable() {
        return true;
    }

    /**
     * Returns a string representing the C type.
     */
    String getCType();

    /**
     * Declares the type representation in the file.
     * After declare has been executed it should be possible to use the string returned by getCType().
     */
    default void declare(CFile cFile) throws IOException {
    }

    /**
     * Adds additional code required for the representation to be usable at runtime.
     */
    default void implement(CFile cFile) throws IOException {
    }

    /**
     * Updates `ident` to have the same content as `value` (assuming equal representation).
     */
    default void setValue(CFile cFile, String ident, String value) throws IOException {
        cFile.writeLine(ident + " = " + value + ";");
    }

    /**
     * Converts the `value` into a string stored inside `builder`.
     */
    void writeToString(CFile cFile, String builder, String value) throws IOException;

    /**
     * Compares `left` and `right` and stores the result in `result`.
     */
    void writeCompare(CFile cFile, String result, ComparisonNode.Operator operator, String left, String right) throws IOException;
}
