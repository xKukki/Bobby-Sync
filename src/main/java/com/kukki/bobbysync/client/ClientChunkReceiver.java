package com.kukki.bobbysync.client;

import com.kukki.bobbysync.Config;
import com.kukki.bobbysync.network.RegionPayload;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class ClientChunkReceiver {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<String, ByteArrayOutputStream> activeTransfers = new HashMap<>();
    private static final LZ4Factory lz4Factory = LZ4Factory.fastestInstance();
    private static final LZ4FastDecompressor decompressor = lz4Factory.fastDecompressor();

    private static final long MIN_VALID_REGION_SIZE = 8192;
    private static String currentSyncDimension = "minecraft:overworld";

    private static int totalFiles = 0;
    private static long totalBytes = 0;
    private static int receivedFiles = 0;
    private static long receivedBytes = 0;
    private static long startTime = 0;
    private static long lastUpdateTime = 0;

    public static void setSyncTarget(String dimension, int files, long bytes) {
        currentSyncDimension = dimension;
        totalFiles = files;
        totalBytes = bytes;
        receivedFiles = 0;
        receivedBytes = 0;
        startTime = System.currentTimeMillis();
        lastUpdateTime = 0;
        
        if (Config.CHAT_LOGGING.get()) {
            if (files == 0) {
                sendChatMessage("§a[BobbySync] §fYou already have all those chunks! Nothing to download.");
            } else {
                sendChatMessage("§e[BobbySync] §fDownload started! Receiving " + files + " files (" + (bytes / (1024*1024)) + " MB)...");
            }
        }
    }

    // SYNCHRONOUS PROCESSING: Prevents race conditions and missing chunks
    public static void handleRegionData(RegionPayload payload) {
        String fileName = payload.regionName();
        ByteArrayOutputStream baos = activeTransfers.computeIfAbsent(fileName, k -> new ByteArrayOutputStream());
        
        try {
            byte[] decompressedData = decompressBytes(payload.data(), payload.uncompressedChunkSize());
            
            synchronized (baos) {
                baos.write(decompressedData);
            }
            
            if (payload.isLast()) {
                byte[] finalData;
                synchronized (baos) {
                    finalData = baos.toByteArray();
                }
                
                saveToBobbyFolder(fileName, finalData);
                activeTransfers.remove(fileName);
                
                synchronized (ClientChunkReceiver.class) {
                    receivedFiles++;
                    receivedBytes += payload.totalSize();
                }
                
                Minecraft.getInstance().execute(() -> {
                    updateProgressBar(fileName);
                });
            }
        } catch (Exception e) {
            LOGGER.error("[BobbySync] Failed to decompress or write region data for {}", fileName, e);
            activeTransfers.remove(fileName);
        }
    }

    // Called by SyncCompletePayload from the server
    public static void onSyncComplete(int actualFilesSent, long actualBytesSent) {
        Minecraft.getInstance().execute(() -> {
            if (!Config.CHAT_LOGGING.get()) return;
            
            String completeMsg;
            if (actualFilesSent == 0) {
                completeMsg = "§a[BobbySync] §fYou already have all those chunks! Nothing to download.";
            } else if (actualFilesSent < totalFiles) {
                completeMsg = "§e[BobbySync] §fDownload complete! Sent " + actualFilesSent + "/" + totalFiles + 
                    " files (" + (actualBytesSent / (1024*1024)) + " MB). Some files may have been skipped.";
            } else {
                completeMsg = "§a[BobbySync] §fDownload complete! All " + actualFilesSent + " files received successfully.";
            }
            
            if (Config.USE_ACTION_BAR.get()) {
                sendActionBar(completeMsg);
            } else {
                sendChatMessage(completeMsg);
            }
            sendChatMessage("§e[BobbySync] §fPlease rejoin the server to let Bobby load the chunks!");
        });
    }

    private static void updateProgressBar(String fileName) {
        if (!Config.CHAT_LOGGING.get()) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime < 250) return;
        lastUpdateTime = currentTime;
        
        int percent = totalBytes == 0 ? 100 : (int) ((receivedBytes * 100) / totalBytes);
        int bars = percent / 10;
        
        long elapsed = currentTime - startTime;
        double speedMBps = elapsed > 0 ? (receivedBytes / (1024.0 * 1024.0)) / (elapsed / 1000.0) : 0;
        long remainingBytes = totalBytes - receivedBytes;
        int etaSeconds = speedMBps > 0 ? (int) (remainingBytes / (speedMBps * 1024 * 1024)) : 0;
        
        StringBuilder sb = new StringBuilder("§a[BobbySync] §f[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < bars ? "█" : "░");
        }
        sb.append("] ").append(percent).append("% (").append(receivedFiles).append("/").append(totalFiles).append(") - ");
        sb.append(String.format("%.1f MB/s", speedMBps)).append(" - ETA: ").append(formatTime(etaSeconds));
        
        String message = sb.toString();
        
        if (Config.USE_ACTION_BAR.get()) {
            sendActionBar(message);
        } else {
            sendChatMessage(message);
        }
    }

    private static String formatTime(int seconds) {
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static byte[] decompressBytes(byte[] compressedData, int originalLength) {
        byte[] restored = new byte[originalLength];
        decompressor.decompress(compressedData, 0, restored, 0, originalLength);
        return restored;
    }

    private static void saveToBobbyFolder(String fileName, byte[] data) {
        try {
            Path bobbyPath = getBobbyDimensionPath(currentSyncDimension);
            if (!Files.exists(bobbyPath)) {
                Files.createDirectories(bobbyPath);
            }
            Path targetFile = bobbyPath.resolve(fileName);
            
            try (FileOutputStream fos = new FileOutputStream(targetFile.toFile())) {
                fos.write(data);
                fos.flush();
            }
        } catch (IOException e) {
            LOGGER.error("[BobbySync] Failed to save region file to Bobby folder", e);
        }
    }

    public static Path getBobbyDimensionPath(String dimensionStr) {
        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc.gameDirectory.toPath();
        String serverAddress = "unknown";
        
        if (mc.getConnection() != null) {
            SocketAddress addr = mc.getConnection().getConnection().getRemoteAddress();
            if (addr instanceof InetSocketAddress inet) {
                String host = inet.getHostString();
                int port = inet.getPort();
                serverAddress = (port != 25565) ? host + "_" + port : host;
            }
        }
        
        long seedHash = 0;
        if (mc.level != null) {
            try {
                Field f = net.minecraft.world.level.biome.BiomeManager.class.getDeclaredField("biomeZoomSeed");
                f.setAccessible(true);
                seedHash = f.getLong(mc.level.getBiomeManager());
            } catch (Exception ex) {
                LOGGER.warn("[BobbySync] Could not read biomeZoomSeed, defaulting to 0", ex);
            }
        }
        
        ResourceLocation dimLoc = ResourceLocation.parse(dimensionStr);
        String dimensionFolder = dimLoc.getNamespace().equals("minecraft") ? dimLoc.getPath() : dimLoc.getNamespace() + "_" + dimLoc.getPath();
        
        return gameDir.resolve(".bobby")
                .resolve(serverAddress)
                .resolve(String.valueOf(seedHash))
                .resolve("minecraft")
                .resolve(dimensionFolder);
    }

    public static Map<String, Long> getExistingRegionsWithSizes(String targetDimension) {
        Map<String, Long> existing = new HashMap<>();
        try {
            Path dimensionDir = getBobbyDimensionPath(targetDimension);
            
            if (Files.exists(dimensionDir) && Files.isDirectory(dimensionDir)) {
                try (var stream = Files.list(dimensionDir)) {
                    stream.filter(path -> path.getFileName().toString().endsWith(".mca"))
                          .forEach(path -> {
                              try {
                                  long size = Files.size(path);
                                  if (size >= MIN_VALID_REGION_SIZE) {
                                      existing.put(path.getFileName().toString(), size);
                                  } else if (size > 0) {
                                      LOGGER.warn("[BobbySync] Found corrupt region file {} ({} bytes). Deleting...", path.getFileName(), size);
                                      Files.delete(path);
                                  }
                              } catch (Exception e) {
                                  LOGGER.debug("[BobbySync] Could not check size of {} (Likely locked)", path.getFileName(), e);
                              }
                          });
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[BobbySync] Could not scan existing Bobby regions", e);
        }
        return existing;
    }

    public static void sendChatMessage(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);
        }
    }

    public static void sendActionBar(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), true);
        }
    }
}