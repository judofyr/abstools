package abs.backend.java.lib.runtime;

import abs.backend.java.lib.types.ABSBool;
import abs.backend.java.lib.types.ABSRef;
import abs.backend.java.lib.types.ABSType;
import abs.backend.java.lib.types.ABSValue;
import static abs.backend.java.lib.runtime.ABSRuntime.*;

public class ABSObject implements ABSRef {
    private final COG cog;
    
    public ABSObject() {
        cog = getCurrentCOG();
    }
    
    protected ABSObject(COG cog) {
        this.cog = cog;
    }
    
    public final COG getCOG() {
        return cog;
    }

    protected final void __ABS_checkSameCOG() {
        if (cog != getCurrentCOG()) {
            throw new ABSIllegalSynchronousCallException();
        }
    }
    
    @Override
    public ABSBool eq(ABSValue o) {
        return ABSBool.fromBoolean(this == o);
    }

    @Override
    public ABSBool notEq(ABSValue o) {
        return eq(o).negate();
    }

}
