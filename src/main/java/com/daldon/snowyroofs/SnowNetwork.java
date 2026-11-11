package com.daldon.snowyroofs;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;

public final class SnowNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SnowyRoofs.MOD_ID, "snow_sync"),
            () -> VERSION, VERSION::equals, VERSION::equals);
    private static int id;

    public static void init() {
        CHANNEL.registerMessage(id++, SnowUpdate.class,
                (msg, buf) -> { buf.writeBlockPos(msg.pos); buf.writeByte(msg.amount); },
                buf -> new SnowUpdate(buf.readBlockPos(), buf.readUnsignedByte()),
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> ClientSnowRenderer.acceptUpdate(msg.pos, msg.amount));
                    ctx.get().setPacketHandled(true);
                });
    }

    public static void send(ServerPlayer player, BlockPos pos, int amount) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SnowUpdate(pos, amount));
    }

    public static void syncAll(net.minecraft.server.level.ServerLevel level, BlockPos pos, int amount) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) < 128 * 128) {
                send(player, pos, amount);
            }
        }
    }

    public record SnowUpdate(BlockPos pos, int amount) {}
}
