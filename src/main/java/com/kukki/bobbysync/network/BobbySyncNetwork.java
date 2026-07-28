package com.kukki.bobbysync.network;

import com.kukki.bobbysync.BobbySync;
import com.kukki.bobbysync.client.ClientChunkReceiver;
import com.kukki.bobbysync.server.ServerChunkStreamer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class BobbySyncNetwork {
    private static final Logger LOGGER = LogManager.getLogger();

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(BobbySyncNetwork::registerPayloads);
    }

    private static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(BobbySync.MODID).versioned("1.0");
        
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.CODEC, (payload, context) -> {
            Player playerEntity = context.player();
            if (playerEntity instanceof ServerPlayer player) {
                ResourceLocation dimLoc;
                try {
                    dimLoc = ResourceLocation.parse(payload.dimension());
                } catch (Exception e) {
                    player.sendSystemMessage(Component.literal("§c[BobbySync] §fInvalid dimension format."));
                    return;
                }

                ResourceKey<Level> targetDimKey = ResourceKey.create(Registries.DIMENSION, dimLoc);
                ServerLevel serverLevel = player.getServer().getLevel(targetDimKey);
                
                if (serverLevel == null) {
                    player.sendSystemMessage(Component.literal("§c[BobbySync] §fDimension not found on server: " + payload.dimension()));
                    return;
                }
                
                Path regionPath;
                if (targetDimKey.equals(Level.OVERWORLD)) {
                    regionPath = player.getServer().getWorldPath(LevelResource.ROOT).resolve("region");
                } else if (targetDimKey.equals(Level.NETHER)) {
                    regionPath = player.getServer().getWorldPath(LevelResource.ROOT).resolve("DIM-1").resolve("region");
                } else if (targetDimKey.equals(Level.END)) {
                    regionPath = player.getServer().getWorldPath(LevelResource.ROOT).resolve("DIM1").resolve("region");
                } else {
                    regionPath = player.getServer().getWorldPath(LevelResource.ROOT).resolve(dimLoc.getNamespace()).resolve(dimLoc.getPath()).resolve("region");
                }
                
                ServerChunkStreamer.startSync(
                    player, 
                    regionPath, 
                    targetDimKey,
                    payload.radiusChunks(),
                    payload.existingRegionsWithSizes(), 
                    payload.bandwidthLimitMBps()
                );
            }
        });
        
        registrar.playToClient(SyncStartPayload.TYPE, SyncStartPayload.CODEC, (payload, context) -> {
            ClientChunkReceiver.setSyncTarget(payload.dimension(), payload.totalFiles(), payload.totalBytes());
        });
        
        registrar.playToClient(RegionPayload.TYPE, RegionPayload.CODEC, (payload, context) -> {
            ClientChunkReceiver.handleRegionData(payload);
        });
        
        registrar.playToClient(SyncCompletePayload.TYPE, SyncCompletePayload.CODEC, (payload, context) -> {
            ClientChunkReceiver.onSyncComplete(payload.actualFilesSent(), payload.actualBytesSent());
        });
    }
}