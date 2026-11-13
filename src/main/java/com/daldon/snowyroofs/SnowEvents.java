package com.daldon.snowyroofs;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-authoritative eight-stage snow on Macaw's Roofs. */
@Mod.EventBusSubscriber(modid = SnowyRoofs.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SnowEvents {
    // Timers are per-world and per-roof: stage 8 is reached after 60-240 seconds of snowfall.
    private static final Map<ServerLevel, Map<Long, Long>> SNOW_STARTS = new WeakHashMap<>();

    public static boolean isRoof(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && "mcwroofs".equals(id.getNamespace()) && id.getPath().contains("roof");
    }

    private static void update(ServerLevel level, BlockPos pos, int amount) {
        SnowStorage data = SnowStorage.get(level);
        if (data.get(pos) == amount) return;
        data.set(pos, amount);
        SnowNetwork.syncAll(level, pos, amount);
    }

    private static boolean exposed(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.above()).isAir() && level.canSeeSky(pos.above());
    }

    private static boolean snowing(ServerLevel level, BlockPos pos) {
        // Avoid isRainingAt(): some roof shapes/blocks interfere with that test.
        // Weather is global in vanilla; biome and exposure determine whether it snows here.
        return level.isRaining()
                && level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW
                && exposed(level, pos);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !isRoof(event.getLevel().getBlockState(event.getPos()))) return;
        ItemStack held = event.getItemStack();
        boolean apply = held.is(Items.SNOW);
        boolean remove = held.getItem() instanceof ShovelItem;
        if (!apply && !remove) return;

        if (event.getLevel().isClientSide()) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        int current = SnowStorage.get(level).get(pos);
        if (apply && current < 8) {
            update(level, pos, current + 1);
            if (!event.getEntity().getAbilities().instabuild) held.shrink(1);
            // The next snowfall can continue from the new current stage.
            SNOW_STARTS.computeIfAbsent(level, unused -> new HashMap<>()).remove(pos.asLong());
        } else if (remove && current > 0) {
            update(level, pos, 0);
            SNOW_STARTS.computeIfAbsent(level, unused -> new HashMap<>()).remove(pos.asLong());
            int balls = 2 + level.random.nextInt(5);
            Block.popResource(level, pos, new ItemStack(Items.SNOWBALL, balls));
            if (!event.getEntity().getAbilities().instabuild) {
                held.hurtAndBreak(1, event.getEntity(), player -> player.broadcastBreakEvent(event.getHand()));
            }
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && isRoof(event.getState())) {
            update(level, event.getPos(), 0);
            Map<Long, Long> timers = SNOW_STARTS.get(level);
            if (timers != null) timers.remove(event.getPos().asLong());
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

        SnowStorage storage = SnowStorage.get(level);
        Map<Long, Long> starts = SNOW_STARTS.computeIfAbsent(level, unused -> new HashMap<>());
        long now = level.getGameTime();

        // Exhaustive nearby scan every 5 seconds, not random sampling: every roof
        // in loaded chunks is found, including flat, steep, corner and ridge roofs.
        for (ServerPlayer player : level.players()) {
            BlockPos center = player.blockPosition();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dx = -20; dx <= 20; dx++) {
                for (int dz = -20; dz <= 20; dz++) {
                    int x = center.getX() + dx, z = center.getZ() + dz;
                    for (int dy = -16; dy <= 20; dy++) {
                        int y = center.getY() + dy;
                        pos.set(x, y, z);
                        if (!level.hasChunkAt(pos) || !isRoof(level.getBlockState(pos))) continue;
                        BlockPos roofPos = pos.immutable();
                        long key = roofPos.asLong();
                        int current = storage.get(roofPos);
                        if (snowing(level, roofPos)) {
                            // Stable per-roof randomized duration, between 1 and 4 minutes.
                            int duration = 1200 + Math.floorMod(Long.hashCode(key * 0x9E3779B97F4A7C15L), 3601);
                            long started = starts.computeIfAbsent(key, unused -> now);
                            // Preserve manual layers as the initial stage.
                            int stage = Math.max(current, Math.min(8, (int) ((now - started) * 8 / duration)));
                            if (stage != current) update(level, roofPos, stage);
                        } else {
                            starts.remove(key);
                            // Warm weather gradually reduces the visible snow stages
                            // (and therefore the icy/drip textures), but NEVER melts
                            // established roof snow completely. Stage 1 is permanent.
                            // Only an explicit shovel action (or breaking the roof)
                            // is allowed to clear the last layer to stage 0.
                            if (current > 1 && !level.getBiome(roofPos).value().coldEnoughToSnow(roofPos)
                                    && exposed(level, roofPos) && level.random.nextInt(8) == 0) {
                                update(level, roofPos, Math.max(1, current - 1));
                            }
                        }
                    }
                }
            }
        }
    }
}
