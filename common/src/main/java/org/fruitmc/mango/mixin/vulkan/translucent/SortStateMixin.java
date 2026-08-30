package org.fruitmc.mango.mixin.vulkan.translucent;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.fruitmc.mango.render.translucent.BspSortDataHolder;
import org.fruitmc.mango.render.translucent.MangoVertexSorting;
import org.fruitmc.mango.render.translucent.bsp.BspSortData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MeshData.SortState.class)
public abstract class SortStateMixin implements BspSortDataHolder {

    @Unique
    private BspSortData mango$bspSortData = BspSortData.empty();

    @Override
    public BspSortData mango$getBspSortData() {
        return this.mango$bspSortData;
    }

    @Override
    public void mango$setBspSortData(BspSortData data) {
        this.mango$bspSortData = data;
    }

    @WrapOperation(
        method = "buildSortedIndexBuffer",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexSorting;sort(Lcom/mojang/blaze3d/vertex/CompactVectorArray;)[I"
        ),
        require = 1
    )
    private int[] mango$injectBspSort(VertexSorting sorting, CompactVectorArray points, Operation<int[]> original) {
        if (sorting instanceof MangoVertexSorting mangoSorting && this.mango$bspSortData.hasTree()) {
            mangoSorting.setBspSortData(this.mango$bspSortData);
        }
        return original.call(sorting, points);
    }
}
