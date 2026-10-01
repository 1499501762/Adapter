package org.sinytra.adapter.patch.resolver.injection;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.sinytra.adapter.env.ann.AtData;
import org.sinytra.adapter.env.ctx.MixinContext;
import org.sinytra.adapter.env.ctx.TargetPair;
import org.sinytra.adapter.patch.Recipe;
import org.sinytra.adapter.patch.config.Configuration;
import org.sinytra.adapter.patch.config.MutableConfiguration;
import org.sinytra.adapter.patch.resolver.SubResolver;
import org.sinytra.adapter.util.MethodQualifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.sinytra.adapter.env.util.MixinAnnotationConstants.AT_VAL_INVOKE;
import static org.sinytra.adapter.env.util.MixinAnnotationConstants.AT_VAL_INVOKE_ASSIGN;

/**
 * Handles injection points whose target member MOVED to a different owner.
 * <p>
 * The reference case is NeoForge lifting a private static helper onto the class of its first parameter:
 *
 * <pre>
 *   vanilla      PistonStructureResolver.canStickToEachOther(BlockState, BlockState)Z   (private static)
 *   NeoForge     BlockState.canStickTo(BlockState)Z                                     (instance)
 * </pre>
 *
 * Both the owner and the name changed, so every owner/name based lookup - including
 * {@link ChangedMemberDescriptorSubResolver}, which keeps owner + name and only ignores the descriptor -
 * finds nothing, and the injection point is reported as unresolvable.
 * <p>
 * The relocation survives in the SHAPE of the call: the receiver is dropped and the remaining parameters
 * plus the return type stay identical. That is what this resolver matches on, deliberately without looking
 * at the new name.
 * <p>
 * <b>Why it scans the target method instead of the receiving class.</b> The natural approach - look for a
 * method with the shifted signature on the first parameter's class - does not work here, and that is not an
 * accident of this one case: NeoForge implements such helpers as {@code default} methods on its extension
 * interfaces, so the member is NOT declared by the receiving class at all. {@code canStickTo} lives on
 * {@code net/neoforged/neoforge/common/extensions/IBlockStateExtension} and {@code BlockState} merely
 * implements it. (The same holds for the {@code isStickyBlock} half of this pair.) A scan of the class
 * therefore finds nothing, and scanning interfaces instead would widen the search surface rather than
 * sharpen it.
 * <p>
 * Scanning the patched target method for the call itself is both simpler and better grounded: the call has to
 * actually be there, it carries the real owner and descriptor, and the instruction it yields is exactly what
 * the {@code @At} target has to be rewritten to.
 * <p>
 * Accepted only when unambiguous - the old member must be gone and have been static, the first parameter type
 * must be an object type, and exactly one call in the patched method must match the shifted shape. Anything
 * else bails out and leaves the original failure in place: guessing would silently point an injection at an
 * unrelated member, which is worse than an honest failure.
 * <p>
 * Only {@code @At} INVOKE targets are considered. A mixin that injects INTO a method which was itself moved
 * (carpet's {@code @Inject(at = @At("HEAD"), method = "isSticky")}) is a different mechanism.
 */
public class RelocatedMemberSubResolver implements SubResolver {
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
        if (original == null || original.owner() == null || original.name() == null || original.desc() == null) {
            return null;
        }

        TargetPair dirtyTarget = recipe.getDirtyTarget();
        if (dirtyTarget == null) {
            return null;
        }

        // The member has to be gone under its old owner+name. If it is still there, this resolver has nothing
        // to contribute - ChangedMemberDescriptorSubResolver covers descriptor-only changes.
        if (context.methods().findOwnMethodPair(context.dirtyLookup(), original.ignoreDesc()) != null) {
            return null;
        }

        // "Lifted onto the first parameter's class" only applies to a static helper.
        TargetPair cleanOrigin = context.methods().findOwnMethodPair(context.cleanLookup(), original.ignoreDesc());
        if (cleanOrigin == null || !isStatic(cleanOrigin.methodNode())) {
            return null;
        }

        Type[] oldArgs = Type.getArgumentTypes(original.desc());
        if (oldArgs.length < 1) {
            return null;
        }
        Type receiver = oldArgs[0];
        if (receiver.getSort() != Type.OBJECT) {
            return null;
        }
        Type newReturn = Type.getReturnType(original.desc());
        Type[] newArgs = Arrays.copyOfRange(oldArgs, 1, oldArgs.length);

        List<MethodInsnNode> matches = findShiftedCalls(dirtyTarget.methodNode(), receiver, newArgs, newReturn);
        // Several call sites to the SAME member are fine and expected - Mixin's @At without an ordinal
        // addresses the member, not one particular occurrence (carpet's addBlockLine calls canStickTo twice).
        // What has to be unambiguous is which MEMBER the call was relocated to, so compare the distinct
        // owner+name+descriptor, not the instruction count.
        if (matches.stream().map(call -> call.owner + call.name + call.desc).distinct().limit(2).count() != 1) {
            return null;
        }

        MethodInsnNode call = matches.getFirst();
        context.recordCtxAudit("Adjusting injection target for relocated member: %s -> %s.%s%s",
            original.asDescriptor(), call.owner, call.name, call.desc);

        return MutableConfiguration.create()
            .setAtData(at.withTarget(call));
    }

    /**
     * Finds the calls in {@code target} that match the relocated shape: made on {@code receiver}, taking
     * {@code newArgs} and returning {@code newReturn} - i.e. the old call with its receiver lifted out of the
     * argument list.
     */
    private static List<MethodInsnNode> findShiftedCalls(MethodNode target, Type receiver, Type[] newArgs, Type newReturn) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            int opcode = call.getOpcode();
            if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE && opcode != Opcodes.INVOKESPECIAL) {
                continue;
            }
            if (!call.owner.equals(receiver.getInternalName())) {
                continue;
            }
            if (!Arrays.equals(Type.getArgumentTypes(call.desc), newArgs)) {
                continue;
            }
            if (!Type.getReturnType(call.desc).equals(newReturn)) {
                continue;
            }
            matches.add(call);
        }
        return matches;
    }

    private static boolean isStatic(MethodNode methodNode) {
        return (methodNode.access & Opcodes.ACC_STATIC) != 0;
    }
}
