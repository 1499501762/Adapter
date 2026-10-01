package org.sinytra.adapter.patch.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.Type;
import org.sinytra.adapter.env.ctx.MethodHelper;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.env.ctx.TargetPair;
import org.sinytra.adapter.env.param.MethodParameters;
import org.sinytra.adapter.env.param.MethodParameters.ParamGroup;
import org.sinytra.adapter.env.param.Parameters;
import org.sinytra.adapter.env.util.MixinAnnotationConstants;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.TxResult;
import org.sinytra.adapter.patch.config.*;
import org.sinytra.adapter.patch.config.key.ControlKeys;
import org.sinytra.adapter.patch.config.key.MixinKeys;
import org.sinytra.adapter.patch.processor.ParametersProcessor;
import org.sinytra.adapter.patch.processor.Processors;
import org.sinytra.adapter.patch.processor.redirect.DivertRedirectProcessor;
import org.sinytra.adapter.patch.processor.redirect.ParameterUsageProcessor;
import org.sinytra.adapter.patch.resolver.Resolvers;
import org.sinytra.adapter.patch.resolver.injection.InjectionPointResolver;
import org.sinytra.adapter.patch.resolver.special.ResolverSyntheticInstanceof;
import org.sinytra.adapter.util.MethodQualifier;

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.sinytra.adapter.env.param.MethodParameters.ParamGroup.*;

public class RedirectMixin implements MixinType {
    private static final PropertyContainerTemplate TEMPLATE = ConfigurationTemplates.MIXIN_AT.extend()
        .pluralKeys(MixinKeys.TARGET_METHOD)
        .build();

    @Override
    public PropertyContainerTemplate getConfigurationTemplate() {
        return TEMPLATE;
    }

    @Override
    public Set<MixinFlag> getFlags() {
        return EnumSet.of(MixinFlag.AT_TARGET_SENSITIVE, MixinFlag.ACCEPTS_INSTANCE);
    }

    @Override
    public TxResult preProcess(MixinContext context, MutableConfiguration clean, Resolvers resolvers, Processors processors) {
        resolvers.
            addBefore(InjectionPointResolver.class, new ResolverSyntheticInstanceof(false));
        processors
            .addAfter(ParametersProcessor.class, new DivertRedirectProcessor())
            .addAfter(ParametersProcessor.class, new ParameterUsageProcessor());

        if (clean.getAtData() == null)
            return TxResult.FAIL;
        if (!MixinAnnotationConstants.AT_VAL_INVOKE.equals(clean.getAtData().getValue())) {
            // @Redirect is legal for FIELD (and other) targets too - e.g. carpet's
            // @Redirect(method = "causeExtraKnockback", at = @At(value = "FIELD",
            // target = "Lnet/minecraft/world/entity/Entity;hurtMarked:Z")). Failing unconditionally here
            // dropped such mixins outright even though the referenced member still exists and needs no
            // rewriting. Model the handler as written and let the pipeline's "does the injection point still
            // resolve?" check decide instead.
            clean.setParameters(MethodParameters.create(context.methodNode(),
                List.of(MethodParameters.ParamGroup.SINGLE_ANY)));
            clean.setReturnType(Type.getReturnType(context.methodNode().desc));
            return TxResult.SUCCESS;
        }

        MethodQualifier targetDesc = clean.getAtData().getTarget().flatMap(MethodQualifier::parse).orElse(null);
        if (targetDesc == null)
            return TxResult.FAIL;

        List<Type> methodParams = Parameters.getParameterTypes(context.methodNode().desc);
        List<Type> callTypes = Parameters.getParameterTypes(targetDesc.desc());
        boolean isStatic = isStaticRedirect(context, targetDesc, methodParams, callTypes);
        if (!isStatic) {
            callTypes.addFirst(methodParams.getFirst());
        }

        MethodParameters capturedMethodParams = MethodParameters.create(
            Parameters.parse(context.methodNode(), callTypes.size(), -1),
            List.of(ParamGroup.CAPTURED_PARAMS, ParamGroup.LOCALS)
        );

        MethodParameters params = MethodParameters.builder()
            .putTypes(METHOD_PARAMS, callTypes)
            .putTypes(CAPTURED_PARAMS, capturedMethodParams.getTypes(CAPTURED_PARAMS))
            .putTypes(LOCALS, capturedMethodParams.getTypes(LOCALS))
            .build();

        clean.setParameters(params);

        return TxResult.SUCCESS;
    }

    @Override
    public TxResult postProcess(MixinContext context, Configuration clean, MutableConfiguration dirty, Recipe recipe) {
        if (dirty.getTargetMethod() == null)
            return TxResult.FAIL;

        // A non-INVOKE @Redirect (e.g. FIELD) targets a member, not a call: there is no method to
        // resolve or re-describe, and the ref the mod wrote stays valid. Nothing to adapt.
        if (!MixinAnnotationConstants.AT_VAL_INVOKE.equals(dirty.getAtData().getValue()))
            return TxResult.SUCCESS;

        MethodQualifier targetDesc = dirty.getAtData().getTarget().flatMap(MethodQualifier::parse).orElse(null);
        if (targetDesc == null || targetDesc.desc() == null)
            return TxResult.FAIL;

        // Locate the call this @At addresses. The injection point IS a call instruction inside the method
        // being injected into, so find it THERE rather than resolving the @At's target through a class
        // lookup. That lookup has to see the declaring class, and NeoForge routinely declares such members on
        // its extension INTERFACES (BlockState implements IBlockStateExtension, which declares canStickTo) -
        // a lookup of the @At's owner then misses it and the mixin is dropped as "failed postProcess". The
        // call itself carries the real owner and descriptor regardless. What we find here is also exactly
        // what Mixin binds to, so the handler is built to match.
        TargetPair injected = context.methods().findOwnMethodPair(context.dirtyLookup(), dirty.getTargetMethod());
        MethodInsnNode callInsn = null;
        if (injected != null) {
            List<MethodInsnNode> calls = new ArrayList<>();
            for (AbstractInsnNode node : context.methods().findInjectionTargetInsns(injected, dirty.getAtData())) {
                if (node instanceof MethodInsnNode mi) {
                    calls.add(mi);
                }
            }
            // Several call sites to the SAME member are normal and expected - Mixin's @At without an ordinal
            // addresses the member, not one occurrence (carpet calls canStickTo twice inside addBlockLine).
            // What has to be unambiguous is which MEMBER it is, so compare the distinct owner+name+descriptor
            // rather than the instruction count.
            if (calls.stream().map(c -> c.owner + c.name + c.desc).distinct().limit(2).count() == 1) {
                callInsn = calls.getFirst();
            }
        }

        TargetPair dirtyTarget = context.methods().findOwnMethodPair(context.dirtyLookup(), targetDesc);
        if (dirtyTarget == null) {
            // Fall back to the descriptor being ignored (MethodQualifier#ignoreDesc leaves desc null, which
            // matches any descriptor) - the same owner+name strategy ChangedMemberDescriptorSubResolver
            // applies to call sites.
            dirtyTarget = context.methods().findOwnMethodPair(context.dirtyLookup(), targetDesc.ignoreDesc());
        }
        // Either the call itself or a resolvable target is enough - see the comment above for why the call is
        // the better source when both exist.
        if (dirtyTarget == null && callInsn == null)
            return TxResult.FAIL;

        List<Type> dirtyCaptured = context.methods().resolveCapturedMethodParams(recipe.clean(), recipe.dirty());

        // Build the handler's parameter model from the call we located when there is one. Deriving it from the
        // resolved method instead is what broke carpet's updateNeighborsMaybe: the @At names the CALL
        // (Level#updateNeighborsAt(BlockPos, Block)V, still 2-arg) while the method DECLARATION gained an
        // Orientation parameter, so the two disagree and Mixin rejected the handler.
        // See 12-issue草案.md entry 15.
        List<Type> callTypes = callInsn != null
            ? Parameters.getParameterTypes(callInsn.desc)
            : Parameters.getParameterTypes(targetDesc.desc());
        boolean staticCall = callInsn != null
            ? callInsn.getOpcode() == Opcodes.INVOKESTATIC
            : MethodHelper.isStatic(dirtyTarget.methodNode());
        if (!staticCall) {
            Type owner = callInsn != null
                ? Type.getObjectType(callInsn.owner)
                : Type.getObjectType(dirtyTarget.classNode().name);
            callTypes.addFirst(owner);
        }

        dirty.inheritProperyIfAbsent(ControlKeys.PARAMETERS);
        MethodParameters params = (dirty.getParameters() != null ? dirty.getParameters().mutableCopy() : MethodParameters.builder())
            .putTypes(METHOD_PARAMS, callTypes)
            .putTypes(CAPTURED_PARAMS, dirtyCaptured)
            .build();

        dirty.setParameters(params);
        dirty.setReturnType(Type.getReturnType(targetDesc.desc()));

        return TxResult.SUCCESS;
    }

    private boolean isStaticRedirect(MixinContext context, MethodQualifier targetDesc, List<Type> methodParams, List<Type> callTypes) {
        TargetPair cleanTarget = context.methods().findOwnMethodPair(context.cleanLookup(), targetDesc);
        if (cleanTarget != null) {
            return MethodHelper.isStatic(cleanTarget.methodNode());
        }
        return methodParams.size() >= callTypes.size() && methodParams.subList(0, callTypes.size()).equals(callTypes);
    }
}








