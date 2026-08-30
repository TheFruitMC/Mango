package org.fruitmc.mango.render.gpu.entity;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.animal.cow.CowModel;
import net.minecraft.client.model.animal.pig.PigModel;
import net.minecraft.client.model.animal.sheep.BabySheepModel;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.model.animal.sheep.SheepModel;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.SheepRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import org.fruitmc.mango.render.gpu.skinning.BoneIndexMap;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

public final class EntityAnimationPoseCache {

    private static final Set<Class<?>> LIVING_MODEL_CLASSES = Set.of(
        CowModel.class,
        PigModel.class,
        SheepModel.class,
        BabySheepModel.class,
        SheepFurModel.class
    );
    private static final Set<Class<?>> BLOCK_ENTITY_MODEL_CLASSES = Set.of(
        ChestModel.class,
        loadModelClass("net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer$ShulkerBoxModel"),
        BannerFlagModel.class
    );
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;
    static final int NO_EXACT_OFFSET = -1;
    private static final int EXACT_REUSE_FRAMES = 1;
    private static final int LOD_REUSE_FRAMES = 4;
    private static final double LOD_DISTANCE_BLOCKS = 64.0D;
    private static final double LOD_DISTANCE_SQ = LOD_DISTANCE_BLOCKS * LOD_DISTANCE_BLOCKS;
    private static final float LOD_ROTATION_STEP_DEGREES = 2.0F;
    private static final float LOD_ROTATION_STEP_RADIANS = (float)Math.toRadians(2.0D);
    private static final float LOD_WALK_POS_STEP = 0.25F;
    private static final float LOD_WALK_SPEED_STEP = 0.05F;
    private static final float LOD_SCALE_STEP = 0.05F;

    private final Long2IntOpenHashMap exactOffsets = new Long2IntOpenHashMap();

    public EntityAnimationPoseCache() {
        this.exactOffsets.defaultReturnValue(NO_EXACT_OFFSET);
    }

    public void beginFrame() {
        this.exactOffsets.clear();
    }

    public void clear() {
        this.exactOffsets.clear();
    }

    public boolean supports(Model<?> model, Object state) {
        Class<?> modelClass = model.getClass();
        if (state instanceof LivingEntityRenderState) {
            return LIVING_MODEL_CLASSES.contains(modelClass);
        }
        if (state instanceof Float) {
            return BLOCK_ENTITY_MODEL_CLASSES.contains(modelClass);
        }
        return false;
    }

    int findExactOffset(long fingerprint) {
        return this.exactOffsets.get(fingerprint);
    }

    public OptionalInt findExact(long fingerprint) {
        int offset = findExactOffset(fingerprint);
        if (offset == NO_EXACT_OFFSET) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(offset);
    }

    boolean canUsePersistentPage(EntityRenderState state) {
        if (!(state instanceof MangoEntityRenderStateBridge bridge)) {
            return false;
        }
        return bridge.mango$entityId() != MangoEntityRenderStateBridge.NO_ENTITY_ID;
    }

    long persistentPageKey(Model<?> model, EntityRenderState state) {
        int entityId = ((MangoEntityRenderStateBridge)state).mango$entityId();
        long hash = mix(HASH_OFFSET_BASIS, System.identityHashCode(model.root()));
        hash = mix(hash, entityId);
        return hash;
    }

    public void rememberExact(long fingerprint, int paletteOffset) {
        this.exactOffsets.put(fingerprint, paletteOffset);
    }

    public OptionalLong pageKey(Model<?> model, EntityRenderState state) {
        if (!canUsePersistentPage(state)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(persistentPageKey(model, state));
    }

    public long exactFingerprint(Model<?> model, LivingEntityRenderState state, BoneIndexMap boneIndexMap) {
        long hash = baseLivingFingerprint(model, state, boneIndexMap);
        hash = mix(hash, Float.floatToRawIntBits(state.xRot));
        hash = mix(hash, Float.floatToRawIntBits(state.yRot));
        hash = mix(hash, Float.floatToRawIntBits(state.walkAnimationPos));
        hash = mix(hash, Float.floatToRawIntBits(state.walkAnimationSpeed));
        hash = mix(hash, Float.floatToRawIntBits(state.scale));
        hash = mix(hash, Float.floatToRawIntBits(state.ageScale));
        if (state instanceof SheepRenderState sheepState) {
            hash = mix(hash, Float.floatToRawIntBits(sheepState.headEatPositionScale));
            hash = mix(hash, Float.floatToRawIntBits(sheepState.headEatAngleScale));
        }
        return hash;
    }

    long exactFingerprint(Model<?> model, Object state, BoneIndexMap boneIndexMap) {
        if (state instanceof LivingEntityRenderState livingState) {
            return exactFingerprint(model, livingState, boneIndexMap);
        }
        if (state instanceof Float floatState) {
            long hash = baseFingerprint(model, state, boneIndexMap);
            hash = mix(hash, Float.floatToRawIntBits(floatState));
            return hash;
        }
        throw new IllegalArgumentException("Unsupported cached pose state: " + state.getClass().getName());
    }

    public long lodFingerprint(Model<?> model, LivingEntityRenderState state, BoneIndexMap boneIndexMap) {
        long hash = baseLivingFingerprint(model, state, boneIndexMap);
        hash = mix(hash, quantize(state.xRot, LOD_ROTATION_STEP_DEGREES));
        hash = mix(hash, quantize(state.yRot, LOD_ROTATION_STEP_DEGREES));
        hash = mix(hash, quantize(state.walkAnimationPos, LOD_WALK_POS_STEP));
        hash = mix(hash, quantize(state.walkAnimationSpeed, LOD_WALK_SPEED_STEP));
        hash = mix(hash, quantize(state.scale, LOD_SCALE_STEP));
        hash = mix(hash, quantize(state.ageScale, LOD_SCALE_STEP));
        if (state instanceof SheepRenderState sheepState) {
            hash = mix(hash, quantize(sheepState.headEatPositionScale, LOD_SCALE_STEP));
            hash = mix(hash, quantize(sheepState.headEatAngleScale, LOD_ROTATION_STEP_RADIANS));
        }
        return hash;
    }

    public boolean canUseLod(EntityRenderState state) {
        return state.distanceToCameraSq >= LOD_DISTANCE_SQ;
    }

    public int maxLodFrameAge() {
        return LOD_REUSE_FRAMES;
    }

    public int maxExactFrameAge() {
        return EXACT_REUSE_FRAMES;
    }

    private static long baseFingerprint(Model<?> model, Object state, BoneIndexMap boneIndexMap) {
        long hash = HASH_OFFSET_BASIS;
        hash = mix(hash, System.identityHashCode(model.root()));
        hash = mix(hash, model.getClass().getName().hashCode());
        hash = mix(hash, state.getClass().getName().hashCode());
        hash = mix(hash, boneIndexMap.boneCount());
        return hash;
    }

    private static long baseLivingFingerprint(Model<?> model, LivingEntityRenderState state, BoneIndexMap boneIndexMap) {
        long hash = baseFingerprint(model, state, boneIndexMap);
        hash = mix(hash, booleanBit(state.isBaby));
        hash = mix(hash, booleanBit(state.isInWater));
        hash = mix(hash, booleanBit(state.isFullyFrozen));
        hash = mix(hash, booleanBit(state.isAutoSpinAttack));
        hash = mix(hash, poseOrdinal(state.pose));
        hash = mix(hash, directionOrdinal(state.bedOrientation));
        return hash;
    }

    private static int quantize(float value, float step) {
        return Math.round(value / step);
    }

    private static int booleanBit(boolean value) {
        return value ? 1 : 0;
    }

    private static int poseOrdinal(Pose pose) {
        return pose.ordinal();
    }

    private static int directionOrdinal(Direction direction) {
        return direction == null ? NO_EXACT_OFFSET : direction.ordinal();
    }

    private static long mix(long hash, int value) {
        long mixed = hash ^ Integer.toUnsignedLong(value);
        return mixed * HASH_PRIME;
    }

    private static Class<?> loadModelClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
