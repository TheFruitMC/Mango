package org.fruitmc.mango.render.gpu.skinning;

import org.jetbrains.annotations.Nullable;

public final class BoneCaptureContext {

    private static final ThreadLocal<BoneCaptureContext> CURRENT = new ThreadLocal<>();

    private final BoneIndexMap boneIndexMap;
    private final BoneIndexReceiver receiver;

    private BoneCaptureContext(BoneIndexMap boneIndexMap, BoneIndexReceiver receiver) {
        this.boneIndexMap = boneIndexMap;
        this.receiver = receiver;
    }

    public static BoneCaptureContext forMeshCapture(BoneIndexMap boneIndexMap, BoneIndexReceiver receiver) {
        return new BoneCaptureContext(boneIndexMap, receiver);
    }

    public static void setCurrent(BoneCaptureContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static @Nullable BoneCaptureContext current() {
        return CURRENT.get();
    }

    public BoneIndexMap boneIndexMap() {
        return this.boneIndexMap;
    }

    public BoneIndexReceiver receiver() {
        return this.receiver;
    }

}
