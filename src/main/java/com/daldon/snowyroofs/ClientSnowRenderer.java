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
    private static final ModelProperty<Direction> ROOF_FACING = new ModelProperty<>();
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
        event.register(new ResourceLocation(SnowyRoofs.MOD_ID, "block/side_soft_anchor"));
        event.register(new ResourceLocation(SnowyRoofs.MOD_ID, "block/attic_glass_snow_anchor"));
    }

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        for (Map.Entry<ResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            ResourceLocation key = entry.getKey();
            if (key.getNamespace().equals("mcwroofs") && key.getPath().contains("roof")
                    && !key.getPath().endsWith("inventory")) {
                entry.setValue(new SnowyRoofModel(entry.getValue(), key.getPath().contains("attic_roof")));
            }
        }
    }

    private static final class SnowyRoofModel extends BakedModelWrapper<BakedModel> {
        private final boolean attic;
        SnowyRoofModel(BakedModel original, boolean attic) {
            super(original);
            this.attic = attic;
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData existing) {
            ModelData original = super.getModelData(world, pos, state, existing);
            // Six stable snow variations with icy crystals on every roof.
            long mix = pos.asLong();
            mix ^= (mix >>> 33);
            mix *= 0xff51afd7ed558ccdL;
            mix ^= (mix >>> 33);
            int variant = Math.floorMod((int)(mix ^ (mix >>> 32)), 6);
            Direction facing = Direction.NORTH;
            if (state != null) {
                for (var property : state.getProperties()) {
                    if (property.getName().equals("facing") && property instanceof net.minecraft.world.level.block.state.properties.DirectionProperty dp) {
                        Direction candidate = state.getValue(dp);
                        if (candidate.getAxis().isHorizontal()) facing = candidate;
                        break;
                    }
                }
            }
            return original.derive()
                    .with(SNOW_LEVEL, SNOW.getOrDefault(pos.asLong(), 0))
                    .with(SNOW_VARIANT, variant)
                    .with(ROOF_FACING, facing).build();
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
            Direction facing = data.get(ROOF_FACING);
            if (facing == null) facing = Direction.NORTH;
            // Macaw's roof model climbs toward its FACING direction.
            // The visible lower eave is the OPPOSITE direction.
            Direction outward = facing.getOpposite();

            // Original roof geometry stays untouched. Snow on vertical sides is
            // a second cutout quad, offset slightly to prevent z-fighting.
            boolean cutout = RenderType.cutout().equals(renderType);
            boolean originalCutout = super.getRenderTypes(state, random, data).contains(RenderType.cutout());
            List<BakedQuad> originalQuads = super.getQuads(state, side, random, data,
                    cutout && !originalCutout ? null : renderType);
            List<BakedQuad> result = new ArrayList<>();
            if (!cutout || originalCutout) {
                for (BakedQuad quad : originalQuads) {
                    if (attic && isGlass(quad)) {
                        // Keep the transparent glazing fully intact: only an upper fringe.
                        result.add(quad);
                        if (cutout && isSnowSurface(quad)) {
                            result.add(atticGlassFringe(quad, stage, variant));
                        }
                    } else if (isSnowSurface(quad)) {
                        result.add(snowTop(quad, stage, variant));
                    } else result.add(quad);
                }
            }
            if (cutout && !attic) {
                // Snow is only allowed along the OUTER FRONT eave. NEVER on the
                // left/right sides, rear/interior or any attic window frame.
                for (BakedQuad quad : originalQuads) {
                    Direction face = quad.getDirection();
                    if (face != outward || isSnowSurface(quad) || isGlass(quad)) continue;
                    int edgeVariant = Math.floorMod(variant * 2 + face.ordinal() * 3, 12);
                    result.add(snowEdge(quad, stage, edgeVariant, true));
                }
            }
            // Attic blocks never use side/front overlays. Their upper frame
            // receives the normal top snow; glass only gets the thin top fringe.

            return result;
        }

        private static boolean isGlass(BakedQuad quad) {
            ResourceLocation texture = quad.getSprite().contents().name();
            String path = texture.getPath();
            return path.equals("block/glass") || path.contains("glass") || path.contains("window");
        }

        private static BakedQuad atticGlassFringe(BakedQuad quad, int stage, int variant) {
            TextureAtlasSprite snow = sprite("attic_glass_snow_" + stage + "_v" + variant);
            int[] vertices = quad.getVertices().clone();
            float high = highestY(quad);
            float lowZ = Float.MAX_VALUE, highZ = -Float.MAX_VALUE;
            float lowX = Float.MAX_VALUE, highX = -Float.MAX_VALUE;
            for (int n = 0; n < 4; n++) {
                float x = Float.intBitsToFloat(vertices[n * 8]);
                float z = Float.intBitsToFloat(vertices[n * 8 + 2]);
                lowX = Math.min(lowX, x); highX = Math.max(highX, x);
                lowZ = Math.min(lowZ, z); highZ = Math.max(highZ, z);
            }
            boolean alongZ = highZ - lowZ > highX - lowX;
            for (int n = 0; n < 4; n++) {
                int at = n * 8;
                float x = Float.intBitsToFloat(vertices[at]);
                float y = Float.intBitsToFloat(vertices[at + 1]);
                float z = Float.intBitsToFloat(vertices[at + 2]);
                // Sit slightly above glass without replacing its texture or transparency.
                vertices[at + 1] = Float.floatToRawIntBits(y + .0025f);
                float u = alongZ ? (z - lowZ) / Math.max(.001f, highZ - lowZ)
                        : (x - lowX) / Math.max(.001f, highX - lowX);
                float v = Math.min(1f, Math.max(0f, (high - y) / .75f));
                vertices[at + 4] = Float.floatToRawIntBits(snow.getU0() + u * (snow.getU1() - snow.getU0()));
                vertices[at + 5] = Float.floatToRawIntBits(snow.getV0() + v * (snow.getV1() - snow.getV0()));
            }
            return new BakedQuad(vertices, -1, quad.getDirection(), snow, false);
        }

        private static boolean isSnowSurface(BakedQuad quad) {
            // Compute the face normal from baked vertex geometry instead of the
            // Minecraft-facing enum (which may be horizontal on rotated attic
            // models). This remains correct for all four horizontal rotations.
            int[] data = quad.getVertices();
            if (data.length < 32 || quad.getDirection() == Direction.DOWN) return false;
            float x0 = Float.intBitsToFloat(data[0]);
            float y0 = Float.intBitsToFloat(data[1]);
            float z0 = Float.intBitsToFloat(data[2]);
            float x1 = Float.intBitsToFloat(data[8]);
            float y1 = Float.intBitsToFloat(data[9]);
            float z1 = Float.intBitsToFloat(data[10]);
            float x2 = Float.intBitsToFloat(data[16]);
            float y2 = Float.intBitsToFloat(data[17]);
            float z2 = Float.intBitsToFloat(data[18]);
            float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
            float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float size = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (size < 0.000001f) return quad.getDirection() == Direction.UP;
            float geometricUp = ny / size;
            if (geometricUp > 0.12f) return true;
            if (geometricUp < -0.12f) return false;
            return quad.getDirection() == Direction.UP;
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

        private static float highestY(BakedQuad quad) {
            int[] vertices = quad.getVertices();
            float top = -Float.MAX_VALUE;
            for (int n = 0; n < 4; n++) {
                top = Math.max(top, Float.intBitsToFloat(vertices[n * 8 + 1]));
            }
            return top;
        }

        private static BakedQuad snowEdge(BakedQuad quad, int stage, int variant, boolean front) {
            // Use the real baked quad geometry, including Macaw's 22.5 degree
            // rotations. Select the *upper sloping EDGE* of the face, and form
            // a narrow band inside that surface. It follows the slope exactly.
            int[] original = quad.getVertices();
            if (original.length < 32) return quad;
            int[] vertices = original.clone();

            float[] x = new float[4], y = new float[4], z = new float[4];
            for (int i = 0; i < 4; i++) {
                int at = i * 8;
                x[i] = Float.intBitsToFloat(vertices[at]);
                y[i] = Float.intBitsToFloat(vertices[at + 1]);
                z[i] = Float.intBitsToFloat(vertices[at + 2]);
            }

            // Cyclic quad: evaluate its two pairs of opposite edges.
            // The pair with the larger altitude difference runs across
            // the panel's vertical dimension, even when the upper edge slopes.
            float deltaA = Math.abs((y[0] + y[1]) - (y[2] + y[3]));
            float deltaB = Math.abs((y[1] + y[2]) - (y[3] + y[0]));
            int top0, top1, bottom0, bottom1;
            if (deltaA >= deltaB) {
                if (y[0] + y[1] >= y[2] + y[3]) {
                    top0 = 0; top1 = 1; bottom0 = 3; bottom1 = 2;
                } else {
                    top0 = 3; top1 = 2; bottom0 = 0; bottom1 = 1;
                }
            } else {
                if (y[1] + y[2] >= y[3] + y[0]) {
                    top0 = 1; top1 = 2; bottom0 = 0; bottom1 = 3;
                } else {
                    top0 = 0; top1 = 3; bottom0 = 1; bottom1 = 2;
                }
            }

            // Preserve each top vertex unchanged and pull its corresponding
            // lower vertex toward it. No horizontal floating overlays.
            float faceHeight = Math.abs((y[top0] + y[top1] - y[bottom0] - y[bottom1]) * .5f);
            if (faceHeight < .015f) return quad;
            float rimSize = (front ? .18f : .085f) * (0.45f + 0.55f * stage / 8f);
            float fraction = Math.min(1f, rimSize / faceHeight);

            int[] tops = {top0, top1};
            int[] bottoms = {bottom0, bottom1};
            TextureAtlasSprite snow = sprite((front ? "side_snow_" : "side_soft_") + stage + "_v" + variant);

            for (int j = 0; j < 2; j++) {
                int ti = tops[j], bi = bottoms[j];
                float nx = x[ti] + (x[bi] - x[ti]) * fraction;
                float ny = y[ti] + (y[bi] - y[ti]) * fraction;
                float nz = z[ti] + (z[bi] - z[ti]) * fraction;

                // Tiny normal offset avoids z-fighting but keeps the original angle.
                float ox = quad.getDirection().getStepX() * .0018f;
                float oz = quad.getDirection().getStepZ() * .0018f;
                int ta = ti * 8, ba = bi * 8;
                vertices[ta] = Float.floatToRawIntBits(x[ti] + ox);
                vertices[ta + 2] = Float.floatToRawIntBits(z[ti] + oz);
                vertices[ba] = Float.floatToRawIntBits(nx + ox);
                vertices[ba + 1] = Float.floatToRawIntBits(ny);
                vertices[ba + 2] = Float.floatToRawIntBits(nz + oz);

                float u = j;
                vertices[ta + 4] = Float.floatToRawIntBits(snow.getU0() + u * (snow.getU1() - snow.getU0()));
                vertices[ta + 5] = Float.floatToRawIntBits(snow.getV0());
                vertices[ba + 4] = Float.floatToRawIntBits(snow.getU0() + u * (snow.getU1() - snow.getU0()));
                vertices[ba + 5] = Float.floatToRawIntBits(snow.getV1());
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
