package org.sinytra.adapter.patch.resolver.injection;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.MethodInsnNode;
import org.sinytra.adapter.analysis.method.MethodCallAnalyzer;
import org.sinytra.adapter.env.ann.AtData;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.env.ctx.TargetPair;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.config.Configuration;
import org.sinytra.adapter.patch.config.MutableConfiguration;
import org.sinytra.adapter.patch.resolver.SubResolver;
import org.sinytra.adapter.util.MethodQualifier;

import java.util.List;

import static org.sinytra.adapter.env.util.MixinAnnotationConstants.AT_VAL_INVOKE;
import static org.sinytra.adapter.env.util.MixinAnnotationConstants.AT_VAL_INVOKE_ASSIGN;

/**
 * Handles injection points whose target member survived but changed its descriptor.
 * <p>
 * Example: {@code Level.updateNeighborsAt(BlockPos, Block)} became
 * {@code Level.updateNeighborsAt(BlockPos, Block, Orientation)}. The owner and name still match, so the
 * call site is still there - only the original {@code @At} descriptor no longer matches anything, which
 * made the whole injection point unresolvable.
 * <p>
 * {@link OverloadedInjectionPointSubResolver} covers a related but narrower situation (the old
 * overload is still present and marked {@code @Deprecated}); when the old member is gone entirely that
 * resolver bails out, which is exactly the case handled here. Note that this is deliberately
 * conservative: the replacement has to be unambiguous, otherwise changing the target would be a guess.
 */
public class ChangedMemberDescriptorSubResolver implements SubResolver {
    @Nullable
    @Override
    public Configuration resolve(MixinContext context, Recipe recipe) {
        AtData at = recipe.clean().getAtData();
        if (at == null) {
            return null;
        }
        if (!AT_VAL_INVOKE.equals(at.getValue()) && !AT_VAL_INVOKE_ASSIGN.equals(at.getValue())) {
            return null;
        }

        String targetDesc = at.getTarget().orElse(null);
        if (targetDesc == null) {
            return null;
        }

        MethodQualifier original = MethodQualifier.parse(targetDesc).orElse(null);
        if (original == null || original.name() == null) {
            return null;
        }

        TargetPair dirtyPair = recipe.getDirtyTarget();
        if (dirtyPair == null) {
            return null;
        }

        // Match on owner + name only: the descriptor is precisely what changed.
        List<MethodInsnNode> calls = MethodCallAnalyzer.getMethodCallMinsns(dirtyPair.methodNode(), original.ignoreDesc());
        if (calls.isEmpty()) {
            return null;
        }

        // Only adapt when the replacement is unambiguous; two different overloads would be a guess.
        if (calls.stream().map(call -> call.name + call.desc).distinct().limit(2).count() > 1) {
            return null;
        }

        MethodInsnNode call = calls.getFirst();
        if (call.desc.equals(original.desc())) {
            // Descriptor unchanged - this resolver has nothing to contribute.
            return null;
        }

        return MutableConfiguration.create()
            .setAtData(at.withTarget(call));
    }
}
