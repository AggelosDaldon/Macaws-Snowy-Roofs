package com.daldon.snowyroofs;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** Server-authoritative, persistent 0-8 snow level per roof position. */
public final class SnowStorage extends SavedData {
    private static final String ID = "daldons_snowy_roofs";
    private final Map<Long, Byte> levels = new HashMap<>();

    public static SnowStorage get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SnowStorage::load, SnowStorage::new, ID);
    }

    public int get(BlockPos pos) { return Byte.toUnsignedInt(levels.getOrDefault(pos.asLong(), (byte) 0)); }

    public void set(BlockPos pos, int amount) {
        int value = Math.max(0, Math.min(8, amount));
        if (value == 0) levels.remove(pos.asLong());
        else levels.put(pos.asLong(), (byte) value);
        setDirty();
    }

    public Map<Long, Byte> inChunk(int cx, int cz) {
        Map<Long, Byte> result = new HashMap<>();
        for (var entry : levels.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            if ((pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static SnowStorage load(CompoundTag tag) {
        SnowStorage data = new SnowStorage();
        ListTag list = tag.getList("Roofs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int value = entry.getByte("Snow") & 0xFF;
            if (value >= 1 && value <= 8) data.levels.put(entry.getLong("Pos"), (byte) value);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var entry : levels.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putLong("Pos", entry.getKey());
            item.putByte("Snow", entry.getValue());
            list.add(item);
        }
        tag.put("Roofs", list);
        return tag;
    }
}
