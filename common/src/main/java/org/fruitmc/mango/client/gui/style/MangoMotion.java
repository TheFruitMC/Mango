package org.fruitmc.mango.client.gui.style;

import net.minecraft.util.ARGB;

public final class MangoMotion {

    private static final boolean REDUCED_MOTION = Boolean.getBoolean("mango.accessibility.reducedMotion");

    private MangoMotion() {
    }

    public static boolean reducedMotion() {
        return REDUCED_MOTION;
    }

    public static float lerp(float current, float target, float speed, float deltaTicks) {
        if (REDUCED_MOTION) {
            return target;
        }
        float dt = deltaTicks / 20.0F;
        float factor = 1.0F - (float) Math.exp(-speed * dt);
        return current + (target - current) * factor;
    }

    public static int color(int from, int to, float t, int alpha) {
        int r = (int) (ARGB.red(from) + (ARGB.red(to) - ARGB.red(from)) * t);
        int g = (int) (ARGB.green(from) + (ARGB.green(to) - ARGB.green(from)) * t);
        int b = (int) (ARGB.blue(from) + (ARGB.blue(to) - ARGB.blue(from)) * t);
        return ARGB.color(alpha, r, g, b);
    }

    public static float advanceProgress(float progress, float deltaTicks) {
        if (REDUCED_MOTION) {
            return 1.0F;
        }
        return Math.min(progress + deltaTicks / 4.0F, 1.0F);
    }

    public static float pulse(float progress) {
        if (REDUCED_MOTION) {
            return 0.0F;
        }
        float clamped = Math.clamp(progress, 0.0F, 1.0F);
        return (float) (Math.sin(clamped * Math.PI) * (1.0F - clamped));
    }
}
