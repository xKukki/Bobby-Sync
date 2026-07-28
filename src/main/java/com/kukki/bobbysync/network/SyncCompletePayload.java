package com.kukki.bobbysync.network;

import com.kukki.bobbysync.BobbySync;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncCompletePayload(int actualFilesSent, long actualBytesSent) implements CustomPacketPayload {
    public static final Type<SyncCompletePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BobbySync.MODID, "sync_complete"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCompletePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, SyncCompletePayload::actualFilesSent,
            ByteBufCodecs.VAR_LONG, SyncCompletePayload::actualBytesSent,
            SyncCompletePayload::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}