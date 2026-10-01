package org.sinytra.adapter.patch.mixin;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.sinytra.adapter.env.ctx.AuditTrail;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.TxResult;
import org.sinytra.adapter.patch.config.Configuration;
import org.sinytra.adapter.patch.config.MutableConfiguration;
import org.sinytra.adapter.patch.config.PropertyContainer;
import org.sinytra.adapter.patch.config.PropertyContainerTemplate;
import org.sinytra.adapter.patch.config.key.ControlKeys;
import org.sinytra.adapter.patch.config.key.MixinKeys;
import org.sinytra.adapter.patch.processor.Processors;
import org.sinytra.adapter.patch.resolver.Resolvers;
import org.sinytra.adapter.util.MethodQualifier;

/**
 * Handles {@code @Invoker} mixins.
 * <p>
 * Like {@code @Accessor}, an invoker is an <em>interface mixin</em>: Mixin <em>generates</em> a
 * method that calls the target, it does not inject into an existing method body. Running the
 * method-target injection pipeline on it therefore always finds no injection point and reports a
 * failure, which is why this type opts out with {@link #targetsMethod()} and validates itself
 * instead - the target method named by {@code value} (or {@code callXxx} -&gt; {@code xxx}) is looked
 * up in the patched target class, and only a genuinely missing method is reported.
 * <p>
 * Registering the type matters for the same reason as the others: before this, {@code MixinParser}
 * skipped invokers entirely, so an invoker whose target had moved stayed in the jar and failed at
 * runtime. {@code @Invoker} appears in 23 of the 46 mods of the reference corpus.
 */
public class InvokerMixin implements MixinType {
    private static final PropertyContainerTemplate TEMPLATE = PropertyContainerTemplate.builder()
        .require(ControlKeys.MIXIN_TYPE, ControlKeys.TARGET_CLASS, ControlKeys.RETURN_TYPE, ControlKeys.PARAMETERS)
        .keys(MixinKeys.ACCESSOR_VALUE, MixinKeys.REQUIRE)
        .build();

    @Override
    public PropertyContainerTemplate getConfigurationTemplate() {
        return TEMPLATE;
    }

    @Override
    public boolean targetsMethod() {
        return false;
    }

    @Override
    public TxResult preProcess(MixinContext context, MutableConfiguration clean, Resolvers resolvers, Processors processors) {
        return TxResult.SUCCESS;
    }

    @Override
    public TxResult postProcess(MixinContext context, Configuration clean, MutableConfiguration dirty, Recipe recipe) {
        return TxResult.SUCCESS;
    }

    @Override
    public void validateNonMethodTarget(MixinContext context, Configuration config) {
        MethodQualifier qualifier = deriveTarget(config, context.methodNode());
        String name = qualifier == null ? null : qualifier.name();

        String targetClass = config.getTargetClass();
        ClassNode target = targetClass == null ? null : context.dirtyLookup().getClass(targetClass).orElse(null);

        boolean found = target != null && name != null
            && target.methods.stream().anyMatch(m -> m.name.equals(name));

        context.environment().auditTrail().recordResult(context, config,
            found ? AuditTrail.Match.FULL : AuditTrail.Match.NONE);
    }

    /**
     * The invoker names its target with {@code value}; without one Mixin falls back to the handler's
     * own name with a {@code call}/{@code invoke}/{@code invoker} prefix removed.
     */
    @Nullable
    @Override
    public MethodQualifier deriveTarget(PropertyContainer properties, MethodNode method) {
        String name = properties.getProperty(MixinKeys.ACCESSOR_VALUE).orElse(null);
        if (name == null || name.isEmpty()) {
            name = method.name;
            for (String prefix : new String[]{"invoker", "invoke", "call"}) {
                if (name.startsWith(prefix) && name.length() > prefix.length()) {
                    name = name.substring(prefix.length());
                    break;
                }
            }
            if (name.isEmpty()) {
                return null;
            }
            name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        return new MethodQualifier(name, null);
    }
}
