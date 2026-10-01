package org.sinytra.adapter.patch;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.sinytra.adapter.transform.ClassTransformer;
import org.sinytra.adapter.transform.MethodTransformer;
import org.sinytra.adapter.transform.PipelineMethodTransformer;
import org.sinytra.adapter.transform.cls.DynamicAnonClassIndexPatch;
import org.sinytra.adapter.transform.cls.DynamicAnonymousShadowFieldTypePatch;
import org.sinytra.adapter.transform.patch.MethodPatch;
import org.sinytra.adapter.transform.preprocess.LocalCaptureUpgradeTransformer;
import org.sinytra.adapter.types.FieldAccessorTypeTransformer;
import org.sinytra.adapter.types.FieldTypeUsageTransformer;

import java.util.List;

public class DynamicPatches {
    public static final List<ClassTransformer> CLASS_PATCHES = List.of(
        new DynamicAnonClassIndexPatch(),
        new DynamicAnonymousShadowFieldTypePatch(),
        new FieldTypeUsageTransformer()
    );

    public static Multimap<TxPhase, MethodTransformer> methodTransformers(List<MethodPatch> patches) {
        return ImmutableMultimap.of(
            TxPhase.LOADED, new FieldAccessorTypeTransformer(),
            TxPhase.VALIDATED, new PipelineMethodTransformer(patches, false),
            // Captured-locals ("locals = LocalCapture...") signatures have to be re-derived from the PATCHED
            // method at the ADAPTED injection point, so this has to run after the pipeline: only then has
            // preProcess/the pipeline finished rewriting "method" and "at". Running it earlier (it used to be
            // in EARLY) meant it read the pre-adaptation target, found no injection point, and quietly left a
            // stale capture list behind - which Mixin then aborted on at runtime ("Critical injection failure:
            // LVT in ...", exactly what CAPTURE_FAILHARD is for).
            //
            // This ordering only became safe once Patcher#normalizeParameterAnnotations also re-derived the
            // annotableParameterCount values; without that, adding the captured parameters here while the
            // pipeline had already changed the descriptor threw ArrayIndexOutOfBoundsException inside ASM and
            // failed the whole mod's transform.
            TxPhase.VALIDATED, new LocalCaptureUpgradeTransformer()
        );
    }
}
