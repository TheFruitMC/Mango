package org.fruitmc.mango.render.gpu.entity;

import java.util.List;
import java.util.Locale;

public final class EntityRenderDebugMetrics {

    private static final double BYTES_PER_KIBIBYTE = 1024.0D;
    private static Snapshot publishedEntities = Snapshot.EMPTY;
    private static Snapshot publishedBlockEntities = Snapshot.EMPTY;

    private int modelSubmissions;
    private int modelPartSubmissions;
    private int staticSubmissions;
    private int skinnedSubmissions;
    private int skinnedModelPartSubmissions;
    private int pipelineFallbacks;
    private int outlineFallbacks;
    private int crumblingFallbacks;
    private int disabledSkinningFallbacks;
    private int overflowFallbacks;
    private int captureFallbacks;
    private int groupedFallbacks;

    void beginFrame() {
        this.modelSubmissions = 0;
        this.modelPartSubmissions = 0;
        this.staticSubmissions = 0;
        this.skinnedSubmissions = 0;
        this.skinnedModelPartSubmissions = 0;
        this.pipelineFallbacks = 0;
        this.outlineFallbacks = 0;
        this.crumblingFallbacks = 0;
        this.disabledSkinningFallbacks = 0;
        this.overflowFallbacks = 0;
        this.captureFallbacks = 0;
        this.groupedFallbacks = 0;
    }

    void recordModelSubmission() {
        this.modelSubmissions++;
    }

    void recordModelPartSubmission() {
        this.modelPartSubmissions++;
    }

    void recordStaticSubmission() {
        this.staticSubmissions++;
    }

    void recordSkinnedSubmission() {
        this.skinnedSubmissions++;
    }

    void recordSkinnedModelPartSubmission() {
        this.skinnedModelPartSubmissions++;
    }

    void recordFallback(FallbackReason reason) {
        switch (reason) {
            case PIPELINE -> this.pipelineFallbacks++;
            case OUTLINE -> this.outlineFallbacks++;
            case CRUMBLING -> this.crumblingFallbacks++;
            case SKINNING_DISABLED -> this.disabledSkinningFallbacks++;
            case SKINNING_OVERFLOW -> this.overflowFallbacks++;
            case CAPTURE -> this.captureFallbacks++;
            case GROUPED_SUBMISSION -> this.groupedFallbacks++;
        }
    }

    void publish(
        int instances,
        int batches,
        int directDraws,
        int indirectDraws,
        int renderPasses,
        int instanceBytes,
        int bonePaletteBytes,
        boolean blockEntities
    ) {
        Snapshot snapshot = new Snapshot(
            true,
            this.modelSubmissions,
            this.modelPartSubmissions,
            this.staticSubmissions,
            this.skinnedSubmissions,
            this.skinnedModelPartSubmissions,
            this.pipelineFallbacks,
            this.outlineFallbacks,
            this.crumblingFallbacks,
            this.disabledSkinningFallbacks,
            this.overflowFallbacks,
            this.captureFallbacks,
            this.groupedFallbacks,
            instances,
            batches,
            directDraws,
            indirectDraws,
            renderPasses,
            instanceBytes,
            bonePaletteBytes
        );
        if (blockEntities) {
            publishedBlockEntities = snapshot;
        } else {
            publishedEntities = snapshot;
        }
    }

    static void clearPublished(boolean blockEntities) {
        if (blockEntities) {
            publishedBlockEntities = Snapshot.EMPTY;
        } else {
            publishedEntities = Snapshot.EMPTY;
        }
    }

    public static void appendDebugLines(List<String> lines) {
        Snapshot snapshot = combine(publishedEntities, publishedBlockEntities);
        if (!snapshot.available()) {
            return;
        }
        lines.add("");
        lines.add(String.format(
            Locale.ROOT,
            "Mango submit: %d model + %d part; GPU %d static / %d skin / %d part",
            snapshot.modelSubmissions(),
            snapshot.modelPartSubmissions(),
            snapshot.staticSubmissions(),
            snapshot.skinnedSubmissions(),
            snapshot.skinnedModelPartSubmissions()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "Mango fallback: pipe %d, outline %d, crumble %d, skin off/overflow/capture %d/%d/%d, grouped %d",
            snapshot.pipelineFallbacks(),
            snapshot.outlineFallbacks(),
            snapshot.crumblingFallbacks(),
            snapshot.disabledSkinningFallbacks(),
            snapshot.overflowFallbacks(),
            snapshot.captureFallbacks(),
            snapshot.groupedFallbacks()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "Mango draw: %d instances / %d batches; direct %d + indirect %d in %d passes",
            snapshot.instances(),
            snapshot.batches(),
            snapshot.directDraws(),
            snapshot.indirectDraws(),
            snapshot.renderPasses()
        ));
        lines.add(String.format(
            Locale.ROOT,
            "Mango upload: %.1f KiB instances + %.1f KiB bones",
            snapshot.instanceBytes() / BYTES_PER_KIBIBYTE,
            snapshot.bonePaletteBytes() / BYTES_PER_KIBIBYTE
        ));
    }

    private static Snapshot combine(Snapshot entities, Snapshot blockEntities) {
        return new Snapshot(
            entities.available() || blockEntities.available(),
            entities.modelSubmissions() + blockEntities.modelSubmissions(),
            entities.modelPartSubmissions() + blockEntities.modelPartSubmissions(),
            entities.staticSubmissions() + blockEntities.staticSubmissions(),
            entities.skinnedSubmissions() + blockEntities.skinnedSubmissions(),
            entities.skinnedModelPartSubmissions() + blockEntities.skinnedModelPartSubmissions(),
            entities.pipelineFallbacks() + blockEntities.pipelineFallbacks(),
            entities.outlineFallbacks() + blockEntities.outlineFallbacks(),
            entities.crumblingFallbacks() + blockEntities.crumblingFallbacks(),
            entities.disabledSkinningFallbacks() + blockEntities.disabledSkinningFallbacks(),
            entities.overflowFallbacks() + blockEntities.overflowFallbacks(),
            entities.captureFallbacks() + blockEntities.captureFallbacks(),
            entities.groupedFallbacks() + blockEntities.groupedFallbacks(),
            entities.instances() + blockEntities.instances(),
            entities.batches() + blockEntities.batches(),
            entities.directDraws() + blockEntities.directDraws(),
            entities.indirectDraws() + blockEntities.indirectDraws(),
            entities.renderPasses() + blockEntities.renderPasses(),
            entities.instanceBytes() + blockEntities.instanceBytes(),
            entities.bonePaletteBytes() + blockEntities.bonePaletteBytes()
        );
    }

    enum FallbackReason {
        PIPELINE,
        OUTLINE,
        CRUMBLING,
        SKINNING_DISABLED,
        SKINNING_OVERFLOW,
        CAPTURE,
        GROUPED_SUBMISSION
    }

    private record Snapshot(
        boolean available,
        int modelSubmissions,
        int modelPartSubmissions,
        int staticSubmissions,
        int skinnedSubmissions,
        int skinnedModelPartSubmissions,
        int pipelineFallbacks,
        int outlineFallbacks,
        int crumblingFallbacks,
        int disabledSkinningFallbacks,
        int overflowFallbacks,
        int captureFallbacks,
        int groupedFallbacks,
        int instances,
        int batches,
        int directDraws,
        int indirectDraws,
        int renderPasses,
        int instanceBytes,
        int bonePaletteBytes
    ) {
        private static final Snapshot EMPTY = new Snapshot(
            false,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0
        );
    }

    EntityRenderDebugMetrics() {
    }
}
