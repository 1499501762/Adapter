package org.sinytra.adapter.env.ctx;

import org.jetbrains.annotations.Nullable;
import org.sinytra.adapter.analysis.InheritanceHandler;
import org.sinytra.adapter.types.BytecodeFixerUpper;
import org.sinytra.adapter.util.provider.ClassLookup;

import java.util.Collection;

public interface PatchEnvironment {
    static PatchEnvironment create(RefmapHolder refmapHolder, ClassLookup cleanClassLookup, @Nullable BytecodeFixerUpper bytecodeFixerUpper,
                                   int fabricLVTCompatibility, AuditTrail auditTrail, @Nullable Collection<String> pkgNamespaces) {
        return new PatchEnvironmentImpl(refmapHolder, cleanClassLookup, bytecodeFixerUpper, fabricLVTCompatibility, auditTrail, pkgNamespaces);
    }

    static PatchEnvironment create(RefmapHolder refmapHolder, ClassLookup cleanClassLookup, ClassLookup dirtyClassLookup, @Nullable BytecodeFixerUpper bytecodeFixerUpper, int fabricLVTCompatibility) {
        return new PatchEnvironmentImpl(refmapHolder, cleanClassLookup, dirtyClassLookup, bytecodeFixerUpper, fabricLVTCompatibility);
    }

    MixinClassGenerator classGenerator();

    ClassLookup cleanClassLookup();

    ClassLookup dirtyClassLookup();

    @Nullable
    BytecodeFixerUpper bytecodeFixerUpper();

    InheritanceHandler inheritanceHandler(ClassLookup lookup);

    RefmapHolder refmapHolder();

    int fabricLVTCompatibility();

    AuditTrail auditTrail();

    @Nullable
    Collection<String> getKnownNamespaces();

    boolean isKnownPackage(String pkg);

    /**
     * When enabled, mixin handlers that could not be adapted are <em>removed</em> from their class
     * instead of being left in the jar. The mod then loads with those features disabled.
     * <p>
     * This is the third option next to the existing two, both of which are dead ends: leaving the
     * handler alone makes Mixin throw at runtime (the game crashes), and refusing the whole mod costs
     * the user every working feature in it.
     * <p>
     * Read from the {@code connector.stripFailingMixins} system property so both the standalone
     * transformer and the in-game transformer honour it without extra plumbing.
     */
    default boolean stripFailingMixins() {
        return Boolean.getBoolean("connector.stripFailingMixins");
    }
}
