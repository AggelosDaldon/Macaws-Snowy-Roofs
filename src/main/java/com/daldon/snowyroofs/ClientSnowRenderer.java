package com.daldon.snowyroofs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uses Macaw's existing baked roof geometry, replacing only upward-facing quad sprites
 * with snowy roof textures in cold climates. No second mesh or floating snow planes.
 */
@Mod.EventBusSubscriber(modid = SnowyRoofs.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSnowRenderer {
    private static final ModelProperty<Integer> SNOW_LEVEL = new ModelProperty<>();
    private static final ModelProperty<Integer> SNOW_VARIANT = new ModelProperty<>();
    private static final ConcurrentMap<Long, Integer> SNOW = new ConcurrentHashMap<>();

    public static void acceptUpdate(BlockPos pos, int amount) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (amount <= 0) SNOW.remove(pos.asLong());
        else SNOW.put(pos.asLong(), amount);
        BlockState state = mc.level.getBlockState(pos);
        mc.level.sendBlockUpdated(pos, state, state, 3);
    }

    public static void clear() { SNOW.clear(); }

    @SubscribeEvent
    public static void registerSnowModels(ModelEvent.RegisterAdditional event) {
        for (String name : new String[]{"base_roof", "white_roof", "black_roof", "gray_roof", "light_gray_roof", "thatch", "snow_generic"}) {
            event.register(new ResourceLocation(SnowyRoofs.MOD_ID, "block/" + name + "_snow_anchor"));
        }
        event.register(new ResourceLocation(SnowyRoofs.MOD_ID, "block/side_snow_anchor"));
    }

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        for (Map.Entry<ResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            ResourceLocation key = entry.getKey();
            if (key.getNamespace().equals("mcwroofs") && key.getPath().contains("roof")
                    && !key.getPath().endsWith("inventory")) {
                entry.setValue(new SnowyRoofModel(entry.getValue()));
            }
        }
    }

    private static final class SnowyRoofModel extends BakedModelWrapper<BakedModel> {
        SnowyRoofModel(BakedModel original) { super(original); }

        @Override
        public ModelData getModelData(BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData existing) {
            ModelData original = super.getModelData(world, pos, state, existing);
            // Six stable snow variations with icy crystals on every roof.
            long mix = pos.asLong();
            mix ^= (mix >>> 33);
            mix *= 0xff51afd7ed558ccdL;
            mix ^= (mix >>> 33);
            int variant = Math.floorMod((int)(mix ^ (mix >>> 32)), 6);
            return original.derive()
                    .with(SNOW_LEVEL, SNOW.getOrDefault(pos.asLong(), 0))
                    .with(SNOW_VARIANT, variant).build();
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
            return ChunkRenderTypeSet.union(super.getRenderTypes(state, random, data),
                    ChunkRenderTypeSet.of(RenderType.cutout()));
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                RandomSource random, ModelData data, @Nullable RenderType renderType) {
            Integer stage = data.get(SNOW_LEVEL);
            Integer variant = data.get(SNOW_VARIANT);
            if (stage == null || stage <= 0) {
                return super.getQuads(state, side, random, data, renderType);
            }
            if (variant == null) variant = 0;

            // Original roof geometry stays untouched. Snow on vertical sides is
            // a second cutout quad, offset slightly to prevent z-fighting.
            boolean cutout = RenderType.cutout().equals(renderType);
            boolean originalCutout = super.getRenderTypes(state, random, data).contains(RenderType.cutout());
            List<BakedQuad> originalQuads = super.getQuads(state, side, random, data,
                    cutout && !originalCutout ? null : renderType);
            List<BakedQuad> result = new ArrayList<>();
            if (!cutout || originalCutout) {
                for (BakedQuad quad : originalQuads) {
                    if (quad.getDirection() == Direction.UP) result.add(snowTop(quad, stage, variant));
                    else result.add(quad);
                }
            }
            if (cutout) {
                // Front and side faces use different stable patterns and snow drops.
                for (BakedQuad quad : originalQuads) {
                    if (!quad.getDirection().getAxis().isHorizontal()) continue;
                    int sideVariant = (variant * 2 + (quad.getDirection().ordinal() & 1)) % 12;
                    result.add(snowEdge(quad, stage, sideVariant));
                }
            }
            return result;
        }

        private static TextureAtlasSprite sprite(String name) {
            return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(new ResourceLocation(SnowyRoofs.MOD_ID, "block/" + name));
        }

        private static BakedQuad snowTop(BakedQuad quad, int stage, int variant) {
            TextureAtlasSprite oldSprite = quad.getSprite();
            ResourceLocation oldName = oldSprite.contents().name();
            String material = oldName.getNamespace().equals("mcwroofs") && oldName.getPath().startsWith("block/")
                    ? oldName.getPath().substring(6) : "snow_generic";
            if (!material.equals("base_roof") && !material.equals("white_roof")
                    && !material.equals("black_roof") && !material.equals("gray_roof")
                    && !material.equals("light_gray_roof") && !material.equals("thatch")) material = "snow_generic";
            return remap(quad, sprite(material + "_snow_" + stage + "_v" + variant), false);
        }

        private static BakedQuad snowEdge(BakedQuad quad, int stage, int variant) {
            // Project the edge texture with V=0 at the upper rim of this face.
            TextureAtlasSprite snow = sprite("side_snow_" + stage + "_v" + variant);
            int[] vertices = quad.getVertices().clone();
            float top = -Float.MAX_VALUE;
            float bottom = Float.MAX_VALUE;
            for (int n = 0; n < 4; n++) {
                float y = Float.intBitsToFloat(vertices[n * 8 + 1]);
                top = Math.max(top, y);
                bottom = Math.min(bottom, y);
            }
            for (int n = 0; n < 4; n++) {
                int offset = n * 8;
                float x = Float.intBitsToFloat(vertices[offset]);
                float y = Float.intBitsToFloat(vertices[offset + 1]);
                float z = Float.intBitsToFloat(vertices[offset + 2]);
                // Offset along the original face normal without making new solid geometry.
                Direction direction = quad.getDirection();
                x += direction.getStepX() * .002f;
                z += direction.getStepZ() * .002f;
                vertices[offset] = Float.floatToRawIntBits(x);
                vertices[offset + 2] = Float.floatToRawIntBits(z);
                float u = (direction.getAxis() == Direction.Axis.X ? z : x);
                float v = (top - y) / Math.max(.0625f, top - bottom);
                u = u - (float) Math.floor(u);
                // Per-face patterned stripes; not a whole white facade.
                vertices[offset + 4] = Float.floatToRawIntBits(snow.getU0() + u * (snow.getU1() - snow.getU0()));
                vertices[offset + 5] = Float.floatToRawIntBits(snow.getV0() + Math.min(1f, v) * (snow.getV1() - snow.getV0()));
            }
            return new BakedQuad(vertices, -1, quad.getDirection(), snow, quad.isShade());
        }

        private static BakedQuad remap(BakedQuad quad, TextureAtlasSprite snow, boolean notUsed) {
            TextureAtlasSprite old = quad.getSprite();
            int[] vertices = quad.getVertices().clone();
            for (int n = 0; n < 4; n++) {
                int i = n * 8;
                float u = Float.intBitsToFloat(vertices[i + 4]);
                float v = Float.intBitsToFloat(vertices[i + 5]);
                float fu = (u - old.getU0()) / (old.getU1() - old.getU0());
                float fv = (v - old.getV0()) / (old.getV1() - old.getV0());
                vertices[i + 4] = Float.floatToRawIntBits(snow.getU0() + fu * (snow.getU1() - snow.getU0()));
                vertices[i + 5] = Float.floatToRawIntBits(snow.getV0() + fv * (snow.getV1() - snow.getV0()));
            }
            return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), snow, quad.isShade());
        }
    }
}
