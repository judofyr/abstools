package org.abs_models.backend.rvsdg.builder;

import org.abs_models.backend.rvsdg.abs.StateOutput;
import org.abs_models.backend.rvsdg.core.Output;
import org.abs_models.backend.rvsdg.core.Region;
import org.abs_models.frontend.ast.MainBlock;
import org.abs_models.frontend.ast.Model;

/**
 * ModelBuilder is the entry point for building a complete Model.
 */
public class ModelBuilder {
    final public Model model;
    final public Region region;
    public Output finalState;

    private ModelBuilder(Model model) {
        this.model = model;
        this.region = new Region();
    }

    static public ModelBuilder build(Model model) {
        ModelBuilder mb = new ModelBuilder(model);
        mb.build();
        return mb;
    }

    private void build() {
        MainBlock mainBlock = model.getMainBlock();
        Scope scope = new Scope();
        Builder builder = new Builder(region, scope);
        StateOutput state = new StateOutput(model.getUnitType());
        this.finalState = builder.process(state, mainBlock);
    }
}
