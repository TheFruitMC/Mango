package org.fruitmc.mango.mixin.vulkan.terrain;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.fruitmc.mango.render.fluid.FluidLightingAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin implements FluidLightingAccess {

    private static final int FIRST_VERTEX = 0;
    private static final int VERTEX_1 = 1;
    private static final int VERTEX_2 = 2;
    private static final int VERTEX_3 = 3;
    private static final int VERTICES_PER_QUAD = 4;

    private static final int GRID_NEGATIVE_INDEX = 0;
    private static final int GRID_CENTER_INDEX = 1;
    private static final int GRID_POSITIVE_INDEX = 2;
    private static final int GRID_SIZE = 3;
    private static final int GRID_SAMPLE_COUNT = 9;
    private static final int GRID_NEGATIVE_OFFSET = -1;
    private static final int GRID_CENTER_OFFSET = 0;
    private static final int GRID_POSITIVE_OFFSET = 1;
    private static final int SECTION_SIZE = 16;
    private static final int SECTION_SIZE_MASK = SECTION_SIZE - 1;

    private static final float BLOCK_MIN = 0.0F;
    private static final float BLOCK_CENTER = 0.5F;
    private static final float BLOCK_MAX = 1.0F;
    private static final float CORNER_AVERAGE_SCALE = 0.25F;
    private static final float DEPTH_EPSILON = 1.0E-5F;
    private static final float NORMAL_X = 0.0F;
    private static final float NORMAL_Y = 1.0F;
    private static final float NORMAL_Z = 0.0F;

    @Unique
    private final BlockPos.MutableBlockPos mango$samplePos = new BlockPos.MutableBlockPos();

    @Unique
    private final int[] mango$currentLights = new int[GRID_SAMPLE_COUNT];

    @Unique
    private final float[] mango$currentShades = new float[GRID_SAMPLE_COUNT];

    @Unique
    private final int[] mango$outwardLights = new int[GRID_SAMPLE_COUNT];

    @Unique
    private final float[] mango$outwardShades = new float[GRID_SAMPLE_COUNT];

    @Unique
    private final int[] mango$smoothLights = new int[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$smoothShades = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$vertexX = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$vertexY = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$vertexZ = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$vertexU = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$vertexV = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$relativeX = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$relativeY = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$relativeZ = new float[VERTICES_PER_QUAD];

    @Unique
    private final float[] mango$vertexDepths = new float[VERTICES_PER_QUAD];

    @Unique
    private BlockAndTintGetter mango$level;

    @Unique
    private BlockPos mango$blockPos;

    @Unique
    private int mango$originX;

    @Unique
    private int mango$originY;

    @Unique
    private int mango$originZ;

    @Unique
    private boolean mango$smoothFluidLight;

    @Unique
    private boolean mango$fluidSmoothLighting;

    @Override
    public void mango$setFluidSmoothLighting(boolean enabled) {
        this.mango$fluidSmoothLighting = enabled;
    }

    @Inject(method = "tesselate", at = @At("HEAD"), require = 1)
    private void mango$beginSmoothFluid(
        BlockAndTintGetter level,
        BlockPos pos,
        FluidRenderer.Output output,
        BlockState blockState,
        FluidState fluidState,
        CallbackInfo ci
    ) {
        this.mango$level = level;
        this.mango$blockPos = pos;
        this.mango$originX = pos.getX() & SECTION_SIZE_MASK;
        this.mango$originY = pos.getY() & SECTION_SIZE_MASK;
        this.mango$originZ = pos.getZ() & SECTION_SIZE_MASK;
        this.mango$smoothFluidLight = fluidState.is(FluidTags.WATER) && this.mango$fluidSmoothLighting;
    }

    @Inject(method = "addFace", at = @At("HEAD"), cancellable = true, require = 1)
    private void mango$writeSmoothFluidFace(
        VertexConsumer builder,
        float x0,
        float y0,
        float z0,
        float u0,
        float v0,
        float x1,
        float y1,
        float z1,
        float u1,
        float v1,
        float x2,
        float y2,
        float z2,
        float u2,
        float v2,
        float x3,
        float y3,
        float z3,
        float u3,
        float v3,
        int color,
        int lightCoords,
        boolean addBackFace,
        CallbackInfo ci
    ) {
        if (!this.mango$smoothFluidLight) {
            return;
        }

        this.mango$vertexX[FIRST_VERTEX] = x0;
        this.mango$vertexX[VERTEX_1] = x1;
        this.mango$vertexX[VERTEX_2] = x2;
        this.mango$vertexX[VERTEX_3] = x3;
        this.mango$vertexY[FIRST_VERTEX] = y0;
        this.mango$vertexY[VERTEX_1] = y1;
        this.mango$vertexY[VERTEX_2] = y2;
        this.mango$vertexY[VERTEX_3] = y3;
        this.mango$vertexZ[FIRST_VERTEX] = z0;
        this.mango$vertexZ[VERTEX_1] = z1;
        this.mango$vertexZ[VERTEX_2] = z2;
        this.mango$vertexZ[VERTEX_3] = z3;
        this.mango$vertexU[FIRST_VERTEX] = u0;
        this.mango$vertexU[VERTEX_1] = u1;
        this.mango$vertexU[VERTEX_2] = u2;
        this.mango$vertexU[VERTEX_3] = u3;
        this.mango$vertexV[FIRST_VERTEX] = v0;
        this.mango$vertexV[VERTEX_1] = v1;
        this.mango$vertexV[VERTEX_2] = v2;
        this.mango$vertexV[VERTEX_3] = v3;
        this.mango$relativeX[FIRST_VERTEX] = x0 - this.mango$originX;
        this.mango$relativeX[VERTEX_1] = x1 - this.mango$originX;
        this.mango$relativeX[VERTEX_2] = x2 - this.mango$originX;
        this.mango$relativeX[VERTEX_3] = x3 - this.mango$originX;
        this.mango$relativeY[FIRST_VERTEX] = y0 - this.mango$originY;
        this.mango$relativeY[VERTEX_1] = y1 - this.mango$originY;
        this.mango$relativeY[VERTEX_2] = y2 - this.mango$originY;
        this.mango$relativeY[VERTEX_3] = y3 - this.mango$originY;
        this.mango$relativeZ[FIRST_VERTEX] = z0 - this.mango$originZ;
        this.mango$relativeZ[VERTEX_1] = z1 - this.mango$originZ;
        this.mango$relativeZ[VERTEX_2] = z2 - this.mango$originZ;
        this.mango$relativeZ[VERTEX_3] = z3 - this.mango$originZ;

        Direction face = this.mango$faceDirection(
            this.mango$relativeX[FIRST_VERTEX],
            this.mango$relativeY[FIRST_VERTEX],
            this.mango$relativeZ[FIRST_VERTEX],
            this.mango$relativeX[VERTEX_1],
            this.mango$relativeY[VERTEX_1],
            this.mango$relativeZ[VERTEX_1],
            this.mango$relativeX[VERTEX_2],
            this.mango$relativeY[VERTEX_2],
            this.mango$relativeZ[VERTEX_2]
        );

        boolean useCurrent = false;
        boolean useOutward = false;
        for (int vertex = FIRST_VERTEX; vertex < VERTICES_PER_QUAD; vertex++) {
            float depth = this.mango$faceDepth(
                this.mango$relativeX[vertex],
                this.mango$relativeY[vertex],
                this.mango$relativeZ[vertex],
                face
            );
            this.mango$vertexDepths[vertex] = depth;
            useCurrent |= depth > DEPTH_EPSILON;
            useOutward |= depth < BLOCK_MAX - DEPTH_EPSILON;
        }
        if (useCurrent) {
            this.mango$buildSampleGrid(this.mango$blockPos, face, this.mango$currentLights, this.mango$currentShades);
        }
        if (useOutward) {
            this.mango$buildSampleGrid(
                this.mango$blockPos.relative(face),
                face,
                this.mango$outwardLights,
                this.mango$outwardShades
            );
        }

        for (int vertex = FIRST_VERTEX; vertex < VERTICES_PER_QUAD; vertex++) {
            float depth = this.mango$vertexDepths[vertex];
            if (depth <= DEPTH_EPSILON) {
                this.mango$smoothLights[vertex] = this.mango$cornerLight(
                    this.mango$outwardLights,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
                this.mango$smoothShades[vertex] = this.mango$cornerShade(
                    this.mango$outwardShades,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
            } else if (depth >= BLOCK_MAX - DEPTH_EPSILON) {
                this.mango$smoothLights[vertex] = this.mango$cornerLight(
                    this.mango$currentLights,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
                this.mango$smoothShades[vertex] = this.mango$cornerShade(
                    this.mango$currentShades,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
            } else {
                int currentLight = this.mango$cornerLight(
                    this.mango$currentLights,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
                int outwardLight = this.mango$cornerLight(
                    this.mango$outwardLights,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
                float currentShade = this.mango$cornerShade(
                    this.mango$currentShades,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
                float outwardShade = this.mango$cornerShade(
                    this.mango$outwardShades,
                    this.mango$relativeX[vertex],
                    this.mango$relativeY[vertex],
                    this.mango$relativeZ[vertex],
                    face
                );
                int block = Math.round(
                    LightCoordsUtil.block(currentLight) * depth + LightCoordsUtil.block(outwardLight) * (BLOCK_MAX - depth)
                );
                int sky = Math.round(
                    LightCoordsUtil.sky(currentLight) * depth + LightCoordsUtil.sky(outwardLight) * (BLOCK_MAX - depth)
                );
                this.mango$smoothLights[vertex] = LightCoordsUtil.pack(block, sky);
                this.mango$smoothShades[vertex] = currentShade * depth + outwardShade * (BLOCK_MAX - depth);
            }
        }

        for (int vertex = FIRST_VERTEX; vertex < VERTICES_PER_QUAD; vertex++) {
            this.mango$writeVertex(
                builder,
                this.mango$vertexX[vertex],
                this.mango$vertexY[vertex],
                this.mango$vertexZ[vertex],
                this.mango$vertexU[vertex],
                this.mango$vertexV[vertex],
                color,
                this.mango$smoothLights[vertex],
                this.mango$smoothShades[vertex]
            );
        }
        if (addBackFace) {
            this.mango$writeVertex(builder, x0, y0, z0, u0, v0, color, this.mango$smoothLights[FIRST_VERTEX], this.mango$smoothShades[FIRST_VERTEX]);
            this.mango$writeVertex(builder, x3, y3, z3, u3, v3, color, this.mango$smoothLights[VERTEX_3], this.mango$smoothShades[VERTEX_3]);
            this.mango$writeVertex(builder, x2, y2, z2, u2, v2, color, this.mango$smoothLights[VERTEX_2], this.mango$smoothShades[VERTEX_2]);
            this.mango$writeVertex(builder, x1, y1, z1, u1, v1, color, this.mango$smoothLights[VERTEX_1], this.mango$smoothShades[VERTEX_1]);
        }
        ci.cancel();
    }

    @Unique
    private void mango$writeVertex(
        VertexConsumer builder,
        float x,
        float y,
        float z,
        float u,
        float v,
        int color,
        int lightCoords,
        float shade
    ) {
        builder.addVertex(
            x,
            y,
            z,
            ARGB.scaleRGB(color, shade),
            u,
            v,
            OverlayTexture.NO_OVERLAY,
            lightCoords,
            NORMAL_X,
            NORMAL_Y,
            NORMAL_Z
        );
    }

    @Unique
    private void mango$buildSampleGrid(BlockPos base, Direction face, int[] lights, float[] shades) {
        Direction.Axis firstAxis = this.mango$firstTangentAxis(face);
        Direction.Axis secondAxis = this.mango$secondTangentAxis(face);
        int sample = GRID_NEGATIVE_INDEX;
        for (int firstOffset = GRID_NEGATIVE_OFFSET; firstOffset <= GRID_POSITIVE_OFFSET; firstOffset++) {
            for (int secondOffset = GRID_NEGATIVE_OFFSET; secondOffset <= GRID_POSITIVE_OFFSET; secondOffset++) {
                this.mango$samplePos.set(base);
                if (firstOffset != GRID_CENTER_OFFSET) {
                    this.mango$samplePos.move(this.mango$axisDirection(firstAxis, firstOffset == GRID_POSITIVE_OFFSET));
                }
                if (secondOffset != GRID_CENTER_OFFSET) {
                    this.mango$samplePos.move(this.mango$axisDirection(secondAxis, secondOffset == GRID_POSITIVE_OFFSET));
                }
                BlockState state = this.mango$level.getBlockState(this.mango$samplePos);
                lights[sample] = LightCoordsUtil.getLightCoords(
                    LightCoordsUtil.BrightnessGetter.DEFAULT,
                    this.mango$level,
                    state,
                    this.mango$samplePos
                );
                shades[sample] = state.getShadeBrightness(this.mango$level, this.mango$samplePos);
                sample++;
            }
        }
    }

    @Unique
    private int mango$cornerLight(int[] lights, float x, float y, float z, Direction face) {
        int firstIndex = this.mango$gridIndex(face, x, y, z, true);
        int secondIndex = this.mango$gridIndex(face, x, y, z, false);
        int center = this.mango$gridSampleIndex(GRID_CENTER_INDEX, GRID_CENTER_INDEX);
        int firstEdge = this.mango$gridSampleIndex(firstIndex, GRID_CENTER_INDEX);
        int secondEdge = this.mango$gridSampleIndex(GRID_CENTER_INDEX, secondIndex);
        int diagonal = this.mango$gridSampleIndex(firstIndex, secondIndex);
        return LightCoordsUtil.smoothBlend(lights[firstEdge], lights[secondEdge], lights[diagonal], lights[center]);
    }

    @Unique
    private float mango$cornerShade(float[] shades, float x, float y, float z, Direction face) {
        int firstIndex = this.mango$gridIndex(face, x, y, z, true);
        int secondIndex = this.mango$gridIndex(face, x, y, z, false);
        int center = this.mango$gridSampleIndex(GRID_CENTER_INDEX, GRID_CENTER_INDEX);
        int firstEdge = this.mango$gridSampleIndex(firstIndex, GRID_CENTER_INDEX);
        int secondEdge = this.mango$gridSampleIndex(GRID_CENTER_INDEX, secondIndex);
        int diagonal = this.mango$gridSampleIndex(firstIndex, secondIndex);
        return (shades[center] + shades[firstEdge] + shades[secondEdge] + shades[diagonal]) * CORNER_AVERAGE_SCALE;
    }

    @Unique
    private int mango$gridIndex(Direction face, float x, float y, float z, boolean first) {
        Direction.Axis axis = first ? this.mango$firstTangentAxis(face) : this.mango$secondTangentAxis(face);
        float coordinate = this.mango$axisCoordinate(axis, x, y, z);
        return coordinate >= BLOCK_CENTER ? GRID_POSITIVE_INDEX : GRID_NEGATIVE_INDEX;
    }

    @Unique
    private int mango$gridSampleIndex(int firstIndex, int secondIndex) {
        return firstIndex * GRID_SIZE + secondIndex;
    }

    @Unique
    private Direction.Axis mango$firstTangentAxis(Direction face) {
        return switch (face) {
            case UP, DOWN, NORTH, SOUTH -> Direction.Axis.X;
            case WEST, EAST -> Direction.Axis.Y;
        };
    }

    @Unique
    private Direction.Axis mango$secondTangentAxis(Direction face) {
        return switch (face) {
            case UP, DOWN, WEST, EAST -> Direction.Axis.Z;
            case NORTH, SOUTH -> Direction.Axis.Y;
        };
    }

    @Unique
    private Direction mango$axisDirection(Direction.Axis axis, boolean positive) {
        return switch (axis) {
            case X -> positive ? Direction.EAST : Direction.WEST;
            case Y -> positive ? Direction.UP : Direction.DOWN;
            case Z -> positive ? Direction.SOUTH : Direction.NORTH;
        };
    }

    @Unique
    private float mango$axisCoordinate(Direction.Axis axis, float x, float y, float z) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    @Unique
    private float mango$faceDepth(float x, float y, float z, Direction face) {
        return switch (face) {
            case UP -> BLOCK_MAX - Mth.clamp(y, BLOCK_MIN, BLOCK_MAX);
            case DOWN -> Mth.clamp(y, BLOCK_MIN, BLOCK_MAX);
            case NORTH -> Mth.clamp(z, BLOCK_MIN, BLOCK_MAX);
            case SOUTH -> BLOCK_MAX - Mth.clamp(z, BLOCK_MIN, BLOCK_MAX);
            case WEST -> Mth.clamp(x, BLOCK_MIN, BLOCK_MAX);
            case EAST -> BLOCK_MAX - Mth.clamp(x, BLOCK_MIN, BLOCK_MAX);
        };
    }

    @Unique
    private Direction mango$faceDirection(
        float x0,
        float y0,
        float z0,
        float x1,
        float y1,
        float z1,
        float x2,
        float y2,
        float z2
    ) {
        float edgeX = x1 - x0;
        float edgeY = y1 - y0;
        float edgeZ = z1 - z0;
        float otherX = x2 - x0;
        float otherY = y2 - y0;
        float otherZ = z2 - z0;
        float normalX = edgeY * otherZ - edgeZ * otherY;
        float normalY = edgeZ * otherX - edgeX * otherZ;
        float normalZ = edgeX * otherY - edgeY * otherX;
        float absX = Math.abs(normalX);
        float absY = Math.abs(normalY);
        float absZ = Math.abs(normalZ);
        if (absX >= absY && absX >= absZ) {
            return normalX >= BLOCK_MIN ? Direction.EAST : Direction.WEST;
        }
        if (absY >= absZ) {
            return normalY >= BLOCK_MIN ? Direction.UP : Direction.DOWN;
        }
        return normalZ >= BLOCK_MIN ? Direction.SOUTH : Direction.NORTH;
    }
}
