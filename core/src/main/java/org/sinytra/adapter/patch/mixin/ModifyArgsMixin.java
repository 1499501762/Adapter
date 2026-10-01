package org.sinytra.adapter.patch.mixin;

import org.sinytra.adapter.env.ctx.MixinContext;
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
import org.sinytra.adapter.patch.resolver.injection.ArbitraryInjectionPointSubResolver;
import org.sinytra.adapter.patch.resolver.injection.InjectionPointResolver;

import java.util.List;

import static org.sinytra.adapter.env.param.MethodParameters.ParamGroup.SINGLE_ANY;

/**
 * Handles {@code @ModifyArgs} mixins.
 * <p>
 * The handler signature is fixed - {@code void handler(Args args)} - no matter what the injection
 * point looks like, so unlike {@link ModifyArgMixin} there is no argument type to re-derive. The
 * work here is registering the type at all: previously {@code MixinParser} skipped it, so a
 * {@code @ModifyArgs} mixin that could not be injected was neither adapted nor recorded and only
 * blew up at runtime.
 */
public class ModifyArgsMixin implements MixinType {
    private static final PropertyContainerTemplate TEMPLATE = ConfigurationTemplates.MIXIN_AT.extend()
        .pluralKeys(MixinKeys.TARGET_METHOD)
        .build();

    @Override
    public PropertyContainerTemplate getConfigurationTemplate() {
        return TEMPLATE;
    }

    @Override
    public TxResult preProcess(MixinContext context, MutableConfiguration clean, Resolvers resolvers, Processors processors) {
        resolvers.getOrThrow(InjectionPointResolver.class)
            .addSubResolver(new ArbitraryInjectionPointSubResolver());

        clean.setParameters(MethodParameters.create(context.methodNode(), List.of(SINGLE_ANY)));

        return TxResult.SUCCESS;
    }

    @Override
    public TxResult postProcess(MixinContext context, Configuration clean, MutableConfiguration dirty, Recipe recipe) {
        dirty.inheritProperyIfAbsent(MixinKeys.ORDINAL);

        // The handler always takes the argument list and returns void, so its signature never has to
        // follow the (possibly re-targeted) injection point.
        dirty.inheritParameters();
        dirty.inheritReturnType();

        return TxResult.SUCCESS;
    }
}
