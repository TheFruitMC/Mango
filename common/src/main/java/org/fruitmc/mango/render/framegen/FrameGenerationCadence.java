package org.fruitmc.mango.render.framegen;

/**
 * Tracks where the renderer is within one generated-frame cycle. Index zero presents the retained
 * rendered image; the remaining indices present interpolated phases.
 */
final class FrameGenerationCadence {

    private int multiplier = 2;
    private boolean historyValid;
    private int presentIndex;

    boolean hasHistory() {
        return this.historyValid;
    }

    boolean hasPendingPresent() {
        return this.historyValid && this.presentIndex > 0;
    }

    int currentPresentIndex() {
        return this.presentIndex;
    }

    int multiplier() {
        return this.multiplier;
    }

    boolean isInterpolatedPresent() {
        return this.presentIndex < this.multiplier - 1;
    }

    void configureMultiplier(int value) {
        this.multiplier = value;
    }

    void finishRenderedFrame() {
        this.historyValid = true;
        this.presentIndex = 1;
    }

    void markHistoryReady() {
        this.historyValid = true;
        this.presentIndex = 0;
    }

    void advancePresent() {
        this.presentIndex++;
        if (this.presentIndex >= this.multiplier) {
            this.presentIndex = 0;
        }
    }

    void reset() {
        this.historyValid = false;
        this.presentIndex = 0;
    }
}
