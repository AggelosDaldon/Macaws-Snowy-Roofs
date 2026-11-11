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
    private static final ConcurrentMap<Long, Integer> SNOW = new ConcurrentHashMap<>();

    public static void acceptUpdate(BlockPos pos, int amount) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (amount <= 0) SNOW.remove(pos.asLong());
        else SNOW.put(pos.asLong(), amount);
        BlockState state = mc.level.getBlockState(pos);
        mc.level.sendBlockUpdated(pos, state, state, 3);
        mc.level.getModelDataManager().requestRefresh(pos);
    }

    public static void clear() { SNOW.clear(); }

    @SubscribeEvent
    public static void registerSnowModels(ModelEvent.RegisterAdditional event) {
        for (String name : new String[]{"base_roof", "white_roof", "black_roof", "gray_roof", "light_gray_roof", "thatch", "snow_generic"}) {
            event.register(new ResourceLocation(SnowyRoofs.MOD_ID, "block/" + name + "_snow_anchor"));
        }
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
            return original.derive().with(SNOW_LEVEL, SNOW.getOrDefault(pos.asLong(), 0)).build();
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                RandomSource random, ModelData data, @Nullable RenderType renderType) {
            List<BakedQuad> quads = super.getQuads(state, side, random, data, renderType);
            Integer stage = data.get(SNOW_LEVEL);
            if (stage == null || stage <= 0 || quads.isEmpty()) return quads;
            List<BakedQuad> result = new ArrayList<>(quads.size());
            for (BakedQuad quad : quads) {
                int packedNormal = quad.getVertices().length >= 8 ? quad.getVertices()[7] : 0;
                int normalY = (byte) ((packedNormal >> 8) & 255);
                if (quad.getDirection() != Direction.UP && normalY < 18) {
                    result.add(quad);
                    continue;
                }
                TextureAtlasSprite oldSprite = quad.getSprite();
                ResourceLocation oldName = oldSprite.contents().name();
                String material = oldName.getNamespace().equals("mcwroofs") && oldName.getPath().startsWith("block/")
                        ? oldName.getPath().substring(6) : "snow_generic";
                if (!material.equals("base_roof") && !material.equals("white_roof")
                        && !material.equals("black_roof") && !material.equals("gray_roof")
                        && !material.equals("light_gray_roof") && !material.equals("thatch")) {
                    material = "snow_generic";
                }
                TextureAtlasSprite snow = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                        .apply(new ResourceLocation(SnowyRoofs.MOD_ID, "block/" + material + "_snow_" + stage));
                int[] vertices = quad.getVertices().clone();
                for (int corner = 0; corner < 4; corner++) {
                    int i = corner * 8;
                    float u = Float.intBitsToFloat(vertices[i + 4]);
                    float v = Float.intBitsToFloat(vertices[i + 5]);
                    float fu = (u - oldSprite.getU0()) / (oldSprite.getU1() - oldSprite.getU0());
                    float fv = (v - oldSprite.getV0()) / (oldSprite.getV1() - oldSprite.getV0());
                    vertices[i + 4] = Float.floatToRawIntBits(snow.getU0() + fu * (snow.getU1() - snow.getU0()));
                    vertices[i + 5] = Float.floatToRawIntBits(snow.getV0() + fv * (snow.getV1() - snow.getV0()));
                }
                result.add(new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), snow, quad.isShade()));
            }
            return result;
        }
    }
}
