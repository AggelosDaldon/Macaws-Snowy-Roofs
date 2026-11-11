package com.daldon.snowyroofs;

import net.minecraftforge.fml.common.Mod;

@Mod(SnowyRoofs.MOD_ID)
public final class SnowyRoofs {
    public static final String MOD_ID = "daldonssnowyroofs";
    public SnowyRoofs() { SnowNetwork.init(); }
}
