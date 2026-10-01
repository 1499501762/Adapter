package org.sinytra.adapter.patch.resolver.target;

import com.mojang.datafixers.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.sinytra.adapter.analysis.method.MethodAnalyzer;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.env.ctx.TargetPair;
import org.sinytra.adapter.env.param.Parameters;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.config.Configuration;
import org.sinytra.adapter.patch.config.MutableConfiguration;
import org.sinytra.adapter.patch.resolver.Resolver;
import org.sinytra.adapter.patch.resolver.SubResolver;
import org.sinytra.adapter.patch.resolver.injection.InjectionPointResolver;
import org.sinytra.adapter.util.AdapterUtil;
import org.sinytra.adapter.util.MethodQualifier;

import java.util.Comparator;
import java.util.List;

public class TargetMethodSubResolvers {
    /**
     * For example:
     * CLEAN: net/minecraft/server/MinecraftServer.lambda$reloadResources$28(Lcom/google/common/collect/ImmutableList;)Ljava/util/concurrent/CompletionStage;
     * DIRTY: net/minecraft/server/MinecraftServer.lambda$reloadResources$29(Lcom/google/common/collect/ImmutableList;)Ljava/util/concurrent/CompletionStage;
     */
    public static final SubResolver CHANGED_LAMBDA_INDEX = (MixinContext context, Recipe recipe) -> {
        MethodQualifier cleanQualifier = recipe.clean().getTargetMethod();
        if (!MethodAnalyzer.isLambda(cleanQualifier)) return null;

        ClassNode targetClass = context.dirtyLookup().getClass(recipe.clean().getTargetClass()).orElse(null);
        if (targetClass == null) return null;


        List<MethodNode> candidateMethods = targetClass.methods.stream()
            .filter(m -> MethodAnalyzer.isLambda(m) && m.desc.equals(cleanQualifier.desc()))
            .toList();
        if (candidateMethods.size() != 1) return null;

        MethodNode newTarget = candidateMethods.getFirst();
        return MutableConfiguration.create()
            .setTargetMethod(newTarget);
    };

    /**
     * Handle cases where the target method's parameters have changed
     * <p>
     * For example:
     * CLEAN: <code>Lnet/minecraft/server/level/ServerEntity;sendPairingData(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V</code>
     * <br>
     * DIRTY: <code>Lnet/minecraft/server/level/ServerEntity;sendPairingData(Lnet/minecraft/server/level/ServerPlayer;Lnet/neoforged/neoforge/network/bundle/PacketAndPayloadAcceptor;)V</code>
     */
    public static final SubResolver CHANGED_METHOD_PARAMS = (MixinContext context, Recipe recipe) -> {
        Configuration overloaded = resolveOverloadedReplacement(context, recipe);
        if (overloaded != null) return overloaded;

        MethodQualifier cleanQualifier = recipe.clean().getTargetMethod();

        Pair<ClassNode, List<MethodNode>> candidates = context.methods().findOwnMethodsByName(context.dirtyLookup(), cleanQualifier);
        if (candidates == null) return null;

        // Find single matching candidate
        Configuration resolved = resolveReplacementCandidate(context, recipe, candidates.getSecond());
        if (resolved == null) return null;

        // Only apply single candidate change when the target desc has changed
        if (resolved.getTargetMethod() != null && !resolved.getTargetMethod().matches(cleanQualifier)) {
            return resolved;
        }

        return null;
    };

    /**
     * Handle cases where the target instructions have been moved into a lambda inside the target method
     */
    public static final SubResolver MOVED_INTO_LAMBDA = (MixinContext context, Recipe recipe) -> {
        // Decline the relocation for handlers that use mixinextras parameter sugar (@Local / @Share / ...).
        // Measured with create-fly's ItemStackMixin#cacheState: relocating its target into the lambda made
        // Mixin fail with
        //   InjectionError: Critical injection failure ... (0/1) succeeded. Scanned 0 target(s)
        //   Suppressed: SugarApplicationException: Failed to validate sugar @Local Item
        // which is FATAL and cannot be stripped by the safety net, so the whole server refused to start.
        // Without the relocation the handler fails cleanly and is stripped instead (feature missing, server up).
        // The relocation itself is a supported feature (see DynamicMixinPatchTest#testChangeTargetToLambdaInPipeline),
        // so this is deliberately narrow: only the shape that is known to break is declined.
        if (usesMixinExtrasSugar(context)) return null;

        MethodQualifier cleanQualifier = recipe.clean().getTargetMethod();

        TargetPair target = context.methods().findOwnMethodPair(context.dirtyLookup(), cleanQualifier);
        if (target == null) return null;

        for (AbstractInsnNode insn : target.methodNode().instructions) {
            // Find lambda invocations and search for target insns inside the lambda
            if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle handle) {
                // Keep the handle's OWNER. Parsing only the name and letting findOwnMethodPair guess the
                // declaring class made it resolve a same-named lambda belonging to a DIFFERENT class - the
                // mixin's target class, which is where an owner-less lookup starts. The resulting target then
                // had nothing to do with the @At target, so Mixin could never apply it and the whole server
                // died at startup ("Critical injection failure"). Reference case: create-fly's
                // ItemStackMixin#cacheState, whose @At targets Item#useOn but which was re-pointed at
                // ItemStack's own lambda$useOn$0. See 12-issue草案.md entry 17.
                TargetPair lambda = MethodQualifier.parse(handle.getName())
                    .map(q -> context.methods().findOwnMethodPair(context.dirtyLookup(),
                        new MethodQualifier(Type.getObjectType(handle.getOwner()).getDescriptor(), q.name(), q.desc())))
                    .orElse(null);
                if (lambda == null) return null;

                if (!context.methods().findInjectionTargetInsns(lambda).isEmpty()) {
                    return MutableConfiguration.create()
                        .setTargetMethod(lambda.methodNode());
                }
            }
        }

        return null;
    };

    // Resolve overloaded method by call for when the name doesn't match. Original method must be marked @Deprecated
    // Example: BoneMealItem#growCrop -> applyBonemeal
    @Nullable
    private static Configuration resolveOverloadedReplacement(MixinContext context, Recipe recipe) {
        TargetPair dirtyTarget = recipe.getNewCleanTarget();
        if (dirtyTarget == null || !AdapterUtil.isDeprecated(dirtyTarget.methodNode())) return null;

        List<MethodNode> invocations = MethodAnalyzer.getSelfTopTierMethodCalls(dirtyTarget);
        for (MethodNode invocation : invocations) {
            if (context.methods().hasInjectionTargetInsns(new TargetPair(dirtyTarget.classNode(), invocation))) {
                return MutableConfiguration.create()
                    .setTargetMethod(invocation);
            }
        }

        return null;
    }

    @Nullable
    private static Configuration resolveReplacementCandidate(MixinContext context, Recipe recipe, List<MethodNode> methods) {
        if (methods.size() == 1) {
            return MutableConfiguration.create()
                .setTargetMethod(methods.getFirst());
        }

        Resolver resolver = recipe.resolvers().get(InjectionPointResolver.class);

        List<Pair<MethodNode, Configuration>> valid = methods.stream()
            .sorted(Comparator.<MethodNode>comparingInt(m -> Parameters.getParameterTypes(m.desc).size()).reversed())
            .<Pair<MethodNode, Configuration>>flatMap(m -> {
                Configuration dirtyCopy = recipe.dirty().copy().setTargetMethod(m);
                context.pushAudit(resolver);
                Resolver.ResolutionResult result = resolver.resolve(context, recipe.withDirtyConfig(dirtyCopy));
                context.popAudit();
                return result
                    .maybePatch()
                    .stream()
                    .map(c -> Pair.of(m, c.copyClean().setTargetMethod(m)));
            })
            .toList();
        if (valid.size() == 1) {
            return valid.getFirst().getSecond();
        }

        List<MethodNode> nonDeprecated = valid.stream()
            .map(Pair::getFirst)
            .filter(m -> !isDirtyDeprecatedMethod(context, m))
            .toList();
        if (nonDeprecated.size() == 1) {
            return MutableConfiguration.create()
                .setTargetMethod(nonDeprecated.getFirst());
        }

        // Best effort: Handle cases where the target method is overloaded, but the mixin does not specify the target descriptor,
        // resulting in ambigous targets. So we just pick the one with the most parameters;
        if (!valid.isEmpty()) {
            return valid.getFirst().getSecond();
        }

        return null;
    }

    public static boolean isDirtyDeprecatedMethod(MixinContext context, MethodNode dirty) {
        TargetPair pair = context.methods().findOwnMethodPair(context.cleanLookup(), MethodQualifier.create(dirty));
        return (pair == null || !AdapterUtil.isDeprecated(pair.methodNode())) && AdapterUtil.isDeprecated(dirty);
    }

    /**
     * Whether any parameter of the handler carries a mixinextras sugar annotation (@Local, @Share, ...).
     * <p>
     * Used to keep {@link #MOVED_INTO_LAMBDA} away from handlers whose sugar has to be validated against the
     * injection point: moving the target into a lambda makes that validation fail fatally at runtime.
     */
    private static boolean usesMixinExtrasSugar(MixinContext context) {
        MethodNode method = context.methodNode();
        return hasSugar(method.visibleParameterAnnotations) || hasSugar(method.invisibleParameterAnnotations);
    }

    private static boolean hasSugar(java.util.List<AnnotationNode>[] parameterAnnotations) {
        if (parameterAnnotations == null) {
            return false;
        }
        for (java.util.List<AnnotationNode> annotations : parameterAnnotations) {
            if (annotations == null) {
                continue;
            }
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc != null && annotation.desc.startsWith("Lcom/llamalad7/mixinextras/")) {
                    return true;
                }
            }
        }
        return false;
    }
}




