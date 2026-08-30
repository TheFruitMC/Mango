package org.fruitmc.mango.mixin.vulkan.pipeline;

import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(TranslucentFeatureRenderPhase.class)
public abstract class TranslucentFeatureRenderPhaseMixin {

    @Unique
    private static final int EMPTY_SORT_CAPACITY = 0;
    @Unique
    private static final int SORT_CAPACITY_ALIGNMENT = 256;

    @Shadow
    @Final
    private List<TranslucentSubmit> submits;

    @Shadow
    @Final
    private FloatList distances;

    @Unique
    private int[] mango$sortIndices = new int[EMPTY_SORT_CAPACITY];
    @Unique
    private final IntComparator mango$distanceComparator = this::mango$compareDistance;

    @Inject(
            method = "sortInto(Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase$Output;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mango$sortIntoWithReusableScratch(FeatureRenderPhase.Output output, CallbackInfo ci) {
        if (this.submits.isEmpty()) {
            return;
        }
        int submitCount = this.submits.size();
        ensureSortCapacity(submitCount);
        for (int index = 0; index < submitCount; index++) {
            this.mango$sortIndices[index] = index;
        }
        IntArrays.unstableSort(this.mango$sortIndices, 0, submitCount, this.mango$distanceComparator);
        for (int index = 0; index < submitCount; index++) {
            output.accept(this.submits.get(this.mango$sortIndices[index]), true);
        }
        this.submits.clear();
        this.distances.clear();
        ci.cancel();
    }

    @Unique
    private void ensureSortCapacity(int submitCount) {
        if (this.mango$sortIndices.length >= submitCount) {
            return;
        }
        int capacity = Mth.roundToward(submitCount, SORT_CAPACITY_ALIGNMENT);
        this.mango$sortIndices = new int[capacity];
    }

    @Unique
    private int mango$compareDistance(int firstIndex, int secondIndex) {
        return Float.compare(this.distances.getFloat(secondIndex), this.distances.getFloat(firstIndex));
    }
}
