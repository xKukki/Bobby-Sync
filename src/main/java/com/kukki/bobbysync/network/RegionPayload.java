package com.kukki.bobbysync.network;

import com.kukki.bobbysync.BobbySync;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RegionPayload(String regionName, byte[] data, int offset, int totalSize, int uncompressedChunkSize, boolean isLast) implements CustomPacketPayload {
    public static final Type<RegionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BobbySync.MODID, "region_data"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, RegionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RegionPayload::regionName,
            ByteBufCodecs.BYTE_ARRAY, RegionPayload::data,
            ByteBufCodecs.INT, RegionPayload::offset,
            ByteBufCodecs.INT, RegionPayload::totalSize,
            ByteBufCodecs.INT, RegionPayload::uncompressedChunkSize,
            ByteBufCodecs.BOOL, RegionPayload::isLast,
            RegionPayload::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}