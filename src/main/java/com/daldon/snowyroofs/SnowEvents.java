package com.daldon.snowyroofs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/**
 * Server-saved snow accumulation, manual snow-layer application and melting.
 * Snowfall checks run every 5 seconds, and a sampled roof advances a stage at
 * roughly four times the natural vanilla accumulation opportunity.
 */
@Mod.EventBusSubscriber(modid = SnowyRoofs.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SnowEvents {
    public static boolean isRoof(net.minecraft.world.level.block.state.BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && "mcwroofs".equals(id.getNamespace()) && id.getPath().contains("roof");
    }

    private static void update(ServerLevel level, BlockPos pos, int amount) {
        SnowStorage data = SnowStorage.get(level);
        if (data.get(pos) == amount) return;
        data.set(pos, amount);
        SnowNetwork.syncAll(level, pos, amount);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !event.getItemStack().is(Items.SNOW)
                || !isRoof(event.getLevel().getBlockState(event.getPos()))) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        int current = SnowStorage.get(level).get(pos);
        if (current >= 8) return;
        update(level, pos, current + 1);
        if (!event.getEntity().getAbilities().instabuild) {
            ItemStack stack = event.getItemStack();
            stack.shrink(1);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && isRoof(event.getState())) {
            update(level, event.getPos(), 0);
        }
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ServerPlayer player = event.getPlayer();
        ServerLevel level = player.serverLevel();
        SnowStorage.get(level).inChunk(event.getPos().x, event.getPos().z)
                .forEach((key, amount) -> SnowNetwork.send(player, BlockPos.of(key), Byte.toUnsignedInt(amount)));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
                || level.getGameTime() % 100 != 0) return;
        SnowStorage data = SnowStorage.get(level);
        for (ServerPlayer player : level.players()) {
            BlockPos origin = player.blockPosition();
            // 24 sampled columns per player, allowing dense roofing to accumulate gradually.
            for (int i = 0; i < 24; i++) {
                int x = origin.getX() + level.random.nextInt(33) - 16;
                int z = origin.getZ() + level.random.nextInt(33) - 16;
                int y = origin.getY() + level.random.nextInt(17) - 8;
                for (int dy = 0; dy < 9; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.hasChunkAt(p) || !isRoof(level.getBlockState(p))) continue;
                    if (!level.getBlockState(p.above()).isAir() || !level.canSeeSky(p.above())) continue;
                    int current = data.get(p);
                    Biome biome = level.getBiome(p).value();
                    boolean snowing = level.isRaining() &&
                            biome.getPrecipitationAt(p) == Biome.Precipitation.SNOW &&
                            level.isRainingAt(p.above());
                    if (snowing && current < 8 && level.random.nextInt(2) == 0) {
                        update(level, p, current + 1);
                    } else if (!snowing && current > 0 && !biome.coldEnoughToSnow(p)
                            && level.random.nextInt(5) == 0) {
                        update(level, p, current - 1);
                    }
                }
            }
        }
    }
}
