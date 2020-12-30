package org.abs_models.backend.c.compiler;

import org.abs_models.frontend.typechecker.Type;

public class NativeTypeRepresentation implements TypeRepresentation {
    public final Type type;
    public final String cname;

    public NativeTypeRepresentation(Type type, String cname) {
        this.type = type;
        this.cname = cname;
    }

    @Override
    public String getCType() {
        return cname;
    }
}