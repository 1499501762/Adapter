package org.sinytra.adapter.patch.mixin;

import org.objectweb.asm.Type;
import org.sinytra.adapter.env.ann.ConstantData;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.env.ctx.TargetPair;
import org.sinytra.adapter.env.param.MethodParameters;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.TxResult;
import org.sinytra.adapter.patch.config.Configuration;
import org.sinytra.adapter.patch.config.ConfigurationTemplates;
import org.sinytra.adapter.patch.config.MutableConfiguration;
import org.sinytra.adapter.patch.config.PropertyContainerTemplate;
import org.sinytra.adapter.patch.config.key.MixinKeys;
import org.sinytra.adapter.patch.processor.Processors;
import org.sinytra.adapter.patch.resolver.Resolvers;
import org.spongepowered.asm.mixin.injection.points.BeforeConstant;

import java.util.List;

import static org.sinytra.adapter.env.param.MethodParameters.ParamGroup.SINGLE_ANY;

/**
 * Handles {@code @ModifyConstant} mixins.
 * <p>
 * Unlike the other handlers, {@code @ModifyConstant} does not carry an {@code @At}: the injection
 * point is derived from the {@code @Constant} annotation itself. The pipeline already knows how to
 * locate such constants ({@link PipelineMethodTransformer#computeConstantTargetInsns}), but until
 * this type existed the annotation was not registered in {@link MixinTypes}, so
 * {@code MixinParser} skipped the method outright: it was never resolved, never validated and never
 * recorded. A handler whose target constant had moved therefore stayed in the transformed jar and
 * blew up at runtime with {@code InjectionError: ... failed injection check, (0/1) succeeded}.
 * <p>
 * Registering the type fixes two things at once:
 * <ul>
 *     <li>mixins whose constant still resolves take the pipeline's normal path, and</li>
 *     <li>mixins whose constant no longer exists fail in {@link #postProcess} - which both drops
 *     them from the jar and, crucially, records the failure so Connector's mixin safeguard can
 *     refuse the mod instead of letting the game crash later.</li>
 * </ul>
 */
public class ModifyConstantMixin implements MixinType {
    private static final PropertyContainerTemplate TEMPLATE = ConfigurationTemplates.MIXIN_BASE.extend()
        .keys(MixinKeys.TARGET_CONSTANT, MixinKeys.ORDINAL)
        .pluralKeys(MixinKeys.SLICES)
        .build();

    @Override
    public PropertyContainerTemplate getConfigurationTemplate() {
        return TEMPLATE;
    }

    @Override
    public TxResult preProcess(MixinContext context, MutableConfiguration clean, Resolvers resolvers, Processors processors) {
        // The handler receives the constant it replaces and returns the replacement value.
        clean.setParameters(MethodParameters.create(context.methodNode(), List.of(SINGLE_ANY)));

        return TxResult.SUCCESS;
    }

    @Override
    public TxResult postProcess(MixinContext context, Configuration clean, MutableConfiguration dirty, Recipe recipe) {
        dirty.inheritProperyIfAbsent(MixinKeys.ORDINAL);

        // The template declares TARGET_CONSTANT, but nothing ever put a value in it: PropertyProcessor only
        // forwards keys that are ALREADY set, it does not read them off the annotation. A dirty config with
        // "constant" missing therefore failed validation ("invalid DIRTY config") and the mixin was dropped --
        // even after its target had been successfully retargeted to the method the constant moved into.
        if (!dirty.hasProperty(MixinKeys.TARGET_CONSTANT)) {
            context.methodAnnotation().getNested("constant")
                .flatMap(ConstantData::parse)
                .ifPresent(constant -> dirty.setProperty(MixinKeys.TARGET_CONSTANT, constant));
        }

        // MIXIN_BASE.require() mandates PARAMETERS and RETURN_TYPE, and every other mixin type populates both
        // here. This one did not, so its dirty config never validated ("invalid DIRTY config") and the handler
        // was dropped - even once its target had been retargeted and its constant resolved.
        dirty.setParameters(MethodParameters.create(context.methodNode(), List.of(SINGLE_ANY)));
        dirty.setReturnType(Type.getReturnType(context.methodNode().desc));

        if (dirty.getTargetMethod() == null) {
            return TxResult.FAIL;
        }

        TargetPair dirtyTarget = context.methods().findOwnMethodPair(context.dirtyLookup(), dirty.getTargetMethod());
        if (dirtyTarget == null) {
            return TxResult.FAIL;
        }

        // Ask the same machinery the pipeline uses, so that "can this constant be found?" is answered
        // exactly the way Mixin will answer it at runtime.
        List<AbstractInsnNode> constantInsns = context.methods().computeInjectionTargetInsns(
            dirtyTarget,
            () -> context.methodAnnotation().getNested("constant").orElse(null),
            (ctx, handle) -> new BeforeConstant(ctx, handle.unwrap(), Type.getReturnType(context.methodNode().desc).getDescriptor()),
            false
        );

        if (constantInsns.isEmpty()) {
            return TxResult.FAIL;
        }

        return TxResult.SUCCESS;
    }
}


