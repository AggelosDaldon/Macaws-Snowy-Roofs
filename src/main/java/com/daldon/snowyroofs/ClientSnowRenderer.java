package com.daldon.snowyroofs;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Experimental VISUAL overlay. Does not replace blocks, change world saves,
 * or add collision. Roof geometry varies by Macaw's Roofs model variant.
 */
@Mod.EventBusSubscriber(modid = SnowyRoofs.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientSnowRenderer {
    private static final int HORIZONTAL_RADIUS = 16;
    private static final int VERTICAL_RADIUS = 9;
    private static final List<Roof> ROOFS = new ArrayList<>();
    private static BlockPos lastCameraBlock = BlockPos.ZERO;
    private static long nextScan = 0L;
    private static Level lastLevel;

    private record Roof(BlockPos pos, Direction facing, float low, float high) {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Level level = mc.level;
        BlockPos cameraBlock = BlockPos.containing(event.getCamera().getPosition());
        long now = level.getGameTime();
        if (lastLevel != level || now >= nextScan || cameraBlock.distManhattan(lastCameraBlock) > 4) {
            findRoofs(level, cameraBlock);
            lastLevel = level;
            lastCameraBlock = cameraBlock;
            nextScan = now + 30;
        }
        if (ROOFS.isEmpty()) return;

        // Snow uses Minecraft's texture atlas (honours resource packs).
        TextureAtlasSprite sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(new ResourceLocation("minecraft", "block/snow"));
        PoseStack stack = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        VertexConsumer out = mc.renderBuffers().bufferSource().getBuffer(RenderType.cutout());
        for (Roof roof : ROOFS) {
            stack.pushPose();
            stack.translate(roof.pos.getX() - camera.x, roof.pos.getY() - camera.y, roof.pos.getZ() - camera.z);
            float angle = switch (roof.facing) {
                case SOUTH -> 180f;
                case WEST -> 90f;
                case EAST -> 270f;
                default -> 0f;
            };
            stack.translate(.5, 0, .5);
            stack.mulPose(Axis.YP.rotationDegrees(angle));
            stack.translate(-.5, 0, -.5);
            drawSlope(stack, out, sprite, roof.low, roof.high);
            stack.popPose();
        }
        mc.renderBuffers().bufferSource().endBatch(RenderType.cutout());
    }

    private static void findRoofs(Level level, BlockPos center) {
        ROOFS.clear();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -HORIZONTAL_RADIUS; x <= HORIZONTAL_RADIUS; x++) {
            for (int z = -HORIZONTAL_RADIUS; z <= HORIZONTAL_RADIUS; z++) {
                for (int y = -VERTICAL_RADIUS; y <= VERTICAL_RADIUS; y++) {
                    p.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!level.hasChunkAt(p) || !level.isEmptyBlock(p.above())) continue;
                    BlockState state = level.getBlockState(p);
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    if (id == null || !id.getNamespace().equals("mcwroofs") || !id.getPath().contains("roof")) continue;
                    if (!level.canSeeSky(p.above())) continue;
                    Biome biome = level.getBiome(p).value();
                    if (biome.getPrecipitationAt(p) != Biome.Precipitation.SNOW || !biome.coldEnoughToSnow(p)) continue;
                    String name = id.getPath();
                    Direction facing = Direction.NORTH;
                    for (var property : state.getProperties()) {
                        if (property instanceof DirectionProperty dp) {
                            Direction d = state.getValue(dp);
                            if (d.getAxis().isHorizontal()) { facing = d; break; }
                        }
                    }
                    // Approximate roof pitches: configurable here for future model-specific corrections.
                    float low = name.contains("steep") ? 0.04f : name.contains("lower") ? 0.27f : 0.08f;
                    float high = name.contains("steep") ? 1.025f : name.contains("lower") ? 0.78f : 0.985f;
                    if (name.contains("top") || name.contains("attic")) { low = .83f; high = 1.015f; }
                    ROOFS.add(new Roof(p.immutable(), facing, low, high));
                }
            }
        }
    }

    private static void drawSlope(PoseStack stack, VertexConsumer out, TextureAtlasSprite sprite, float low, float high) {
        Matrix4f pose = stack.last().pose();
        Matrix3f normal = stack.last().normal();
        float a = sprite.getU0(), b = sprite.getU1(), c = sprite.getV0(), d = sprite.getV1();
        // 2 mm model offset to avoid z-fighting, visually snow follows a slanted roof.
        vertex(out, pose, normal, 0, low + .012f, 0, a, d);
        vertex(out, pose, normal, 0, high + .012f, 1, a, c);
        vertex(out, pose, normal, 1, high + .012f, 1, b, c);
        vertex(out, pose, normal, 1, low + .012f, 0, b, d);
    }

    private static void vertex(VertexConsumer out, Matrix4f pose, Matrix3f normal, float x, float y, float z, float u, float v) {
        out.vertex(pose, x, y, z).color(255, 255, 255, 255).uv(u, v)
                .overlayCoords(0).uv2(0xF000F0).normal(normal, 0, 1, 0).endVertex();
    }
}
