package org.sinytra.adapter.patch.mixin;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.MethodNode;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.TxResult;
import org.sinytra.adapter.patch.config.Configuration;
import org.sinytra.adapter.patch.config.MutableConfiguration;
import org.sinytra.adapter.patch.config.PropertyContainer;
import org.sinytra.adapter.patch.config.PropertyContainerTemplate;
import org.sinytra.adapter.patch.processor.Processors;
import org.sinytra.adapter.patch.resolver.Resolvers;
import org.sinytra.adapter.util.MethodQualifier;

import java.util.EnumSet;
import java.util.Set;

/**
 * Handles configuration and behavior specific to a Mixin type
 */
public interface MixinType {
    PropertyContainerTemplate getConfigurationTemplate();

    default Set<MixinFlag> getFlags() {
        return EnumSet.noneOf(MixinFlag.class);
    }

    default boolean canInject(MixinContext context, Configuration config) {
        return true;
    }

    /**
     * Types whose target is not named by a {@code method} property derive it from the mixin method
     * itself: {@code @Overwrite} replaces the method of the same name, while {@code @Invoker} calls
     * the method named by its {@code value} (falling back to {@code callXxx} -&gt; {@code xxx}).
     * <p>
     * Parsers use this to fill in the target qualifier before the usual resolution runs.
     *
     * @return the qualifier to use as {@code TARGET_METHOD}, or {@code null} when this type does not
     * derive one
     */
    @Nullable
    default MethodQualifier deriveTarget(PropertyContainer properties, MethodNode method) {
        return null;
    }

    /**
     * Whether this handler targets a method (and therefore belongs in the method-target pipeline).
     * <p>
     * {@code @Accessor} returns {@code false}: it exposes a field, so the method-first pipeline would
     * report every accessor as unadapted. Such types instead implement
     * {@link #validateNonMethodTarget(MixinContext, Configuration)} and are dispatched there.
     */
    default boolean targetsMethod() {
        return true;
    }

    /**
     * Validation for types that opted out of the method pipeline via {@link #targetsMethod()}.
     * Implementations must record an audit result themselves - the pipeline does not run for them.
     * The default does nothing, so no result is recorded and the mixin stays invisible.
     */
    default void validateNonMethodTarget(MixinContext context, Configuration config) {
    }

    TxResult preProcess(MixinContext context, MutableConfiguration clean, Resolvers resolvers, Processors processors);

    TxResult postProcess(MixinContext context, Configuration clean, MutableConfiguration dirty, Recipe recipe);
}
