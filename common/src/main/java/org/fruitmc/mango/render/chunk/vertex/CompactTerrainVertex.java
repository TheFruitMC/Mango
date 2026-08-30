package org.fruitmc.mango.render.chunk.vertex;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * A 16-byte terrain vertex used by the non-translucent terrain pipelines. Positions are stored relative
 * to the section origin, which keeps the quantized range small enough for three 16-bit channels.
 */
public final class CompactTerrainVertex {

    public static final int STRIDE = 16;
    public static final int POSITION_OFFSET = 0;
    public static final int TEXTURE_OFFSET = 6;
    public static final int LIGHT_OFFSET = 10;
    public static final int COLOR_OFFSET = 12;

    public static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB16_UINT)
        .addAttribute("UV0", GpuFormat.RG16_UINT)
        .addAttribute("UV2", GpuFormat.RG8_UINT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .build();

    static final int POSITION_MAX_VALUE = 0xFFFF;
    // The range covers a section plus one block on each side for interpolation across section borders.
    static final float POSITION_MIN = -8.0F;
    static final float POSITION_RANGE = 32.0F;
    static final int POSITION_SCALE = 2048;
    static final int TEXTURE_VALUE_BITS = 15;
    static final int TEXTURE_SCALE = 1 << TEXTURE_VALUE_BITS;
    static final int TEXTURE_VALUE_MASK = TEXTURE_SCALE - 1;
    // The high bit records which side of the texel centre the original coordinate belonged to.
    static final int TEXTURE_DIRECTION_BIT = TEXTURE_SCALE;

    static int encodePosition(float position) {
        int quantized = Math.round((position - POSITION_MIN) * POSITION_SCALE);
        return Math.max(0, Math.min(POSITION_MAX_VALUE, quantized));
    }

    public static float decodePosition(int encoded) {
        return (encoded & POSITION_MAX_VALUE) * (1.0F / POSITION_SCALE) + POSITION_MIN;
    }

    static int encodeTexture(float center, float coordinate) {
        int bias = coordinate < center ? 1 : -1;
        int quantized = Math.round(coordinate * TEXTURE_SCALE) + bias;
        int direction = bias < 0 ? TEXTURE_DIRECTION_BIT : 0;
        return quantized & TEXTURE_VALUE_MASK | direction;
    }

    public static float decodeTexture(int encoded) {
        float value = (encoded & TEXTURE_VALUE_MASK) / (float) TEXTURE_SCALE;
        float correction = (encoded & TEXTURE_DIRECTION_BIT) == 0
            ? -1.0F / TEXTURE_SCALE
            : 1.0F / TEXTURE_SCALE;
        return value + correction;
    }

    private CompactTerrainVertex() {
    }
}
