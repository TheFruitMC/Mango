package org.fruitmc.mango.render.gpu.entity;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class InstancedEntityPipeline {

    private static final String CULL_LOCATION = "pipeline/mango/entity_instanced_cutout_cull";
    private static final String NO_CULL_LOCATION = "pipeline/mango/entity_instanced_cutout";
    private static final String SOLID_LOCATION = "pipeline/mango/entity_instanced_solid";
    private static final String ARMOR_LOCATION = "pipeline/mango/entity_instanced_armor";
    private static final String ARMOR_DECAL_LOCATION = "pipeline/mango/entity_instanced_armor_decal";
    private static final String BANNER_PATTERN_LOCATION = "pipeline/mango/entity_instanced_banner_pattern";
    private static final String SKINNED_CULL_LOCATION = "pipeline/mango/entity_instanced_skinned_cutout_cull";
    private static final String SKINNED_NO_CULL_LOCATION = "pipeline/mango/entity_instanced_skinned_cutout";
    private static final String SKINNED_SOLID_LOCATION = "pipeline/mango/entity_instanced_skinned_solid";
    private static final String SKINNED_ARMOR_LOCATION = "pipeline/mango/entity_instanced_skinned_armor";
    private static final String SKINNED_ARMOR_DECAL_LOCATION = "pipeline/mango/entity_instanced_skinned_armor_decal";
    private static final String SKINNED_BANNER_PATTERN_LOCATION = "pipeline/mango/entity_instanced_skinned_banner_pattern";
    private static final String ENTITY_SHADER = "core/entity_instanced";
    private static final String SKINNED_ENTITY_SHADER = "core/entity_instanced_skinned";
    private static final String PER_FACE_LIGHTING_DEFINE = "PER_FACE_LIGHTING";
    private static final String ALPHA_CUTOUT_DEFINE = "ALPHA_CUTOUT";
    private static final String NO_OVERLAY_DEFINE = "NO_OVERLAY";
    private static final float ALPHA_CUTOUT_THRESHOLD = 0.1F;
    private static final int MESH_BINDING = 0;
    private static final int INSTANCE_BINDING = 1;
    private static final int VARIANT_COUNT = 12;

    private static volatile RenderPipeline cachedCull;
    private static volatile RenderPipeline cachedNoCull;
    private static volatile RenderPipeline cachedSolid;
    private static volatile RenderPipeline cachedArmor;
    private static volatile RenderPipeline cachedArmorDecal;
    private static volatile RenderPipeline cachedBannerPattern;
    private static volatile RenderPipeline cachedSkinnedCull;
    private static volatile RenderPipeline cachedSkinnedNoCull;
    private static volatile RenderPipeline cachedSkinnedSolid;
    private static volatile RenderPipeline cachedSkinnedArmor;
    private static volatile RenderPipeline cachedSkinnedArmorDecal;
    private static volatile RenderPipeline cachedSkinnedBannerPattern;

    public static boolean isSupported(RenderPipeline source) {
        return source == RenderPipelines.ENTITY_CUTOUT_CULL
            || source == RenderPipelines.ENTITY_CUTOUT
            || source == RenderPipelines.ENTITY_SOLID
            || source == RenderPipelines.ENTITY_CUTOUT_Z_OFFSET
            || source == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD
            || source == RenderPipelines.ARMOR_CUTOUT_NO_CULL
            || source == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL
            || source == RenderPipelines.BANNER_PATTERN;
    }

    public static List<RenderPipeline> variants() {
        List<RenderPipeline> variants = new ArrayList<>(VARIANT_COUNT);
        for (RenderPipeline source : variantSources()) {
            variants.add(get(source));
            variants.add(getSkinned(source));
        }
        return variants;
    }

    private static List<RenderPipeline> variantSources() {
        return List.of(
            RenderPipelines.ENTITY_CUTOUT_CULL,
            RenderPipelines.ENTITY_CUTOUT,
            RenderPipelines.ENTITY_SOLID,
            RenderPipelines.ARMOR_CUTOUT_NO_CULL,
            RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL,
            RenderPipelines.BANNER_PATTERN
        );
    }

    public static RenderPipeline get(RenderPipeline source) {
        verifySupported(source);
        if (source == RenderPipelines.ARMOR_CUTOUT_NO_CULL) {
            RenderPipeline local = cachedArmor;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedArmor == null) {
                        cachedArmor = build(
                            ARMOR_LOCATION, ENTITY_SHADER, false, true, true, true, DepthStencilState.DEFAULT
                        );
                    }
                    local = cachedArmor;
                }
            }
            return local;
        }
        if (source == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
            RenderPipeline local = cachedArmorDecal;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedArmorDecal == null) {
                        cachedArmorDecal = build(
                            ARMOR_DECAL_LOCATION,
                            ENTITY_SHADER,
                            false,
                            true,
                            true,
                            true,
                            new DepthStencilState(CompareOp.EQUAL, false)
                        );
                    }
                    local = cachedArmorDecal;
                }
            }
            return local;
        }
        if (source == RenderPipelines.BANNER_PATTERN) {
            RenderPipeline local = cachedBannerPattern;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedBannerPattern == null) {
                        cachedBannerPattern = build(
                            BANNER_PATTERN_LOCATION,
                            ENTITY_SHADER,
                            false,
                            false,
                            false,
                            true,
                            new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false),
                            true
                        );
                    }
                    local = cachedBannerPattern;
                }
            }
            return local;
        }
        if (isSolid(source)) {
            RenderPipeline local = cachedSolid;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedSolid == null) {
                        cachedSolid = build(SOLID_LOCATION, ENTITY_SHADER, false, false, false, false, DepthStencilState.DEFAULT);
                    }
                    local = cachedSolid;
                }
            }
            return local;
        }
        if (isNoCull(source)) {
            RenderPipeline local = cachedNoCull;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedNoCull == null) {
                        cachedNoCull = build(NO_CULL_LOCATION, ENTITY_SHADER, false, true, true, false, DepthStencilState.DEFAULT);
                    }
                    local = cachedNoCull;
                }
            }
            return local;
        }

        RenderPipeline local = cachedCull;
        if (local == null) {
            synchronized (InstancedEntityPipeline.class) {
                if (cachedCull == null) {
                    cachedCull = build(CULL_LOCATION, ENTITY_SHADER, false, false, true, false, DepthStencilState.DEFAULT);
                }
                local = cachedCull;
            }
        }
        return local;
    }

    public static RenderPipeline getSkinned(RenderPipeline source) {
        verifySupported(source);
        if (source == RenderPipelines.BANNER_PATTERN) {
            RenderPipeline local = cachedSkinnedBannerPattern;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedSkinnedBannerPattern == null) {
                        cachedSkinnedBannerPattern = build(
                            SKINNED_BANNER_PATTERN_LOCATION,
                            SKINNED_ENTITY_SHADER,
                            true,
                            false,
                            false,
                            true,
                            new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false),
                            true
                        );
                    }
                    local = cachedSkinnedBannerPattern;
                }
            }
            return local;
        }
        if (source == RenderPipelines.ARMOR_CUTOUT_NO_CULL) {
            RenderPipeline local = cachedSkinnedArmor;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedSkinnedArmor == null) {
                        cachedSkinnedArmor = build(
                            SKINNED_ARMOR_LOCATION, SKINNED_ENTITY_SHADER, true, true, true, true, DepthStencilState.DEFAULT
                        );
                    }
                    local = cachedSkinnedArmor;
                }
            }
            return local;
        }
        if (source == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
            RenderPipeline local = cachedSkinnedArmorDecal;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedSkinnedArmorDecal == null) {
                        cachedSkinnedArmorDecal = build(
                            SKINNED_ARMOR_DECAL_LOCATION,
                            SKINNED_ENTITY_SHADER,
                            true,
                            true,
                            true,
                            true,
                            new DepthStencilState(CompareOp.EQUAL, false)
                        );
                    }
                    local = cachedSkinnedArmorDecal;
                }
            }
            return local;
        }
        if (isSolid(source)) {
            RenderPipeline local = cachedSkinnedSolid;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedSkinnedSolid == null) {
                        cachedSkinnedSolid = build(
                            SKINNED_SOLID_LOCATION, SKINNED_ENTITY_SHADER, true, false, false, false, DepthStencilState.DEFAULT
                        );
                    }
                    local = cachedSkinnedSolid;
                }
            }
            return local;
        }
        if (isNoCull(source)) {
            RenderPipeline local = cachedSkinnedNoCull;
            if (local == null) {
                synchronized (InstancedEntityPipeline.class) {
                    if (cachedSkinnedNoCull == null) {
                        cachedSkinnedNoCull = build(
                            SKINNED_NO_CULL_LOCATION, SKINNED_ENTITY_SHADER, true, true, true, false, DepthStencilState.DEFAULT
                        );
                    }
                    local = cachedSkinnedNoCull;
                }
            }
            return local;
        }

        RenderPipeline local = cachedSkinnedCull;
        if (local == null) {
            synchronized (InstancedEntityPipeline.class) {
                if (cachedSkinnedCull == null) {
                    cachedSkinnedCull = build(
                        SKINNED_CULL_LOCATION, SKINNED_ENTITY_SHADER, true, false, true, false, DepthStencilState.DEFAULT
                    );
                }
                local = cachedSkinnedCull;
            }
        }
        return local;
    }

    private static RenderPipeline build(
        String location,
        String shader,
        boolean skinned,
        boolean perFaceLighting,
        boolean alphaCutout,
        boolean noOverlay,
        DepthStencilState depthStencilState
    ) {
        return build(location, shader, skinned, perFaceLighting, alphaCutout, noOverlay, depthStencilState, false);
    }

    private static RenderPipeline build(
        String location,
        String shader,
        boolean skinned,
        boolean perFaceLighting,
        boolean alphaCutout,
        boolean noOverlay,
        DepthStencilState depthStencilState,
        boolean enableBlend
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("mango", location))
            .withVertexShader(Identifier.fromNamespaceAndPath("mango", shader))
            .withFragmentShader(Identifier.fromNamespaceAndPath("mango", shader))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.LIGHTING)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
            .withVertexBinding(
                MESH_BINDING,
                skinned ? InstancedEntityFormats.SKINNED_MESH_VERTEX_FORMAT : InstancedEntityFormats.MESH_VERTEX_FORMAT
            )
            .withVertexBinding(
                INSTANCE_BINDING,
                skinned ? InstancedEntityFormats.SKINNED_INSTANCE_FORMAT : InstancedEntityFormats.INSTANCE_FORMAT
            )
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(depthStencilState)
            .withCull(!perFaceLighting);

        if (enableBlend) {
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
        }
        if (!noOverlay) {
            builder.withBindGroupLayout(BindGroupLayouts.SAMPLER1);
        }

        if (alphaCutout) {
            builder.withShaderDefine(ALPHA_CUTOUT_DEFINE, ALPHA_CUTOUT_THRESHOLD);
        }
        if (skinned) {
            builder.withBindGroupLayout(InstancedEntityFormats.BONE_PALETTE_LAYOUT);
        }
        if (perFaceLighting) {
            builder.withShaderDefine(PER_FACE_LIGHTING_DEFINE);
        }
        if (noOverlay) {
            builder.withShaderDefine(NO_OVERLAY_DEFINE);
        }
        return builder.build();
    }

    private static boolean isNoCull(RenderPipeline source) {
        return source == RenderPipelines.ENTITY_CUTOUT
            || source == RenderPipelines.ENTITY_CUTOUT_Z_OFFSET;
    }

    private static boolean isSolid(RenderPipeline source) {
        return source == RenderPipelines.ENTITY_SOLID
            || source == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD;
    }

    private static void verifySupported(RenderPipeline source) {
        if (!isSupported(source)) {
            throw new IllegalArgumentException("Unsupported entity pipeline: " + source.getLocation());
        }
    }

    private InstancedEntityPipeline() {
    }
}
