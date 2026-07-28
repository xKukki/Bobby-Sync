package com.kukki.bobbysync.network;

import com.kukki.bobbysync.BobbySync;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncStartPayload(String dimension, int totalFiles, long totalBytes) implements CustomPacketPayload {
    public static final Type<SyncStartPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BobbySync.MODID, "sync_start"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncStartPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SyncStartPayload::dimension,
            ByteBufCodecs.INT, SyncStartPayload::totalFiles,
            ByteBufCodecs.VAR_LONG, SyncStartPayload::totalBytes,
            SyncStartPayload::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}