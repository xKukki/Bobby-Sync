package com.kukki.bobbysync.network;

import com.kukki.bobbysync.BobbySync;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public record RequestPayload(int radiusChunks, String dimension, Map<String, Long> existingRegionsWithSizes, int bandwidthLimitMBps) implements CustomPacketPayload {
    public static final Type<RequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BobbySync.MODID, "request"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, RequestPayload::radiusChunks,
            ByteBufCodecs.STRING_UTF8, RequestPayload::dimension,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_LONG), RequestPayload::existingRegionsWithSizes,
            ByteBufCodecs.INT, RequestPayload::bandwidthLimitMBps,
            RequestPayload::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}