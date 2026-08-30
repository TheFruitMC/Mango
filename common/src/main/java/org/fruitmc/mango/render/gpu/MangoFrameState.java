package org.fruitmc.mango.render.gpu;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class MangoFrameState {

    private static final MangoFrameState INSTANCE = new MangoFrameState();
    private static final long FIRST_FRAME_SERIAL = 1L;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private boolean hasMatrices;
    private long frameSerial;
    private int cameraBlockX;
    private int cameraBlockY;
    private int cameraBlockZ;
    private float cameraOffsetX;
    private float cameraOffsetY;
    private float cameraOffsetZ;

    private MangoFrameState() {
    }

    public static MangoFrameState get() {
        return INSTANCE;
    }

    public void update(
        Matrix4fc projection,
        Matrix4fc modelView,
        int cameraBlockX,
        int cameraBlockY,
        int cameraBlockZ,
        float cameraOffsetX,
        float cameraOffsetY,
        float cameraOffsetZ
    ) {
        this.frameSerial = this.frameSerial == Long.MAX_VALUE
            ? FIRST_FRAME_SERIAL
            : this.frameSerial + FIRST_FRAME_SERIAL;
        this.projection.set(projection);
        this.modelView.set(modelView);
        this.viewProjection.set(this.projection).mul(this.modelView);
        this.hasMatrices = true;
        this.cameraBlockX = cameraBlockX;
        this.cameraBlockY = cameraBlockY;
        this.cameraBlockZ = cameraBlockZ;
        this.cameraOffsetX = cameraOffsetX;
        this.cameraOffsetY = cameraOffsetY;
        this.cameraOffsetZ = cameraOffsetZ;
    }

    public void clear() {
        this.hasMatrices = false;
    }

    @Nullable
    public Matrix4fc viewProjection() {
        return this.hasMatrices ? this.viewProjection : null;
    }

    public long frameSerial() {
        return this.frameSerial;
    }

    public int cameraBlockX() { return this.cameraBlockX; }
    public int cameraBlockY() { return this.cameraBlockY; }
    public int cameraBlockZ() { return this.cameraBlockZ; }
    public float cameraOffsetX() { return this.cameraOffsetX; }
    public float cameraOffsetY() { return this.cameraOffsetY; }
    public float cameraOffsetZ() { return this.cameraOffsetZ; }
}
