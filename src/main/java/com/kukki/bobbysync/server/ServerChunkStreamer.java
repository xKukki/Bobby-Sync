package com.kukki.bobbysync.server;

import com.kukki.bobbysync.network.RegionPayload;
import com.kukki.bobbysync.network.SyncCompletePayload;
import com.kukki.bobbysync.network.SyncStartPayload;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ServerChunkStreamer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int CHUNK_SIZE = 256 * 1024;
    private static final LZ4Factory lz4Factory = LZ4Factory.fastestInstance();
    private static final LZ4Compressor compressor = lz4Factory.fastCompressor();
    
    // Minimum valid size for a region file with actual chunk data (16KB)
    private static final long MIN_VALID_FILE_SIZE = 16384;

    private static final Set<UUID> activeSyncs = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> cancelledSyncs = ConcurrentHashMap.newKeySet();

    public static boolean isActive(UUID playerId) { return activeSyncs.contains(playerId); }
    public static void cancelSync(UUID playerId) { cancelledSyncs.add(playerId); }

    public static void onPlayerLogout(UUID playerId) {
        activeSyncs.remove(playerId);
        cancelledSyncs.remove(playerId);
    }

    public static void startSync(ServerPlayer player, Path serverRegionPath, ResourceKey<Level> targetDimKey, int radiusChunks, Map<String, Long> clientExistingRegionsWithSizes, int clientBandwidthLimitMBps) {
        UUID playerId = player.getUUID();
        
        if (activeSyncs.contains(playerId)) {
            player.sendSystemMessage(Component.literal("§c[BobbySync] §fA sync is already in progress!"));
            return;
        }
        
        activeSyncs.add(playerId);
        cancelledSyncs.remove(playerId);

        File regionDir = serverRegionPath.toFile();
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            activeSyncs.remove(playerId);
            return;
        }

        List<File> filesToSend = new ArrayList<>();
        long totalBytes = 0;
        
        ResourceKey<Level> currentDim = player.level().dimension();
        BlockPos centerPos;

        if (currentDim.equals(targetDimKey)) {
            centerPos = player.blockPosition();
        } else if (currentDim.equals(Level.OVERWORLD) && targetDimKey.equals(Level.NETHER)) {
            centerPos = new BlockPos(player.blockPosition().getX() / 8, 0, player.blockPosition().getZ() / 8);
        } else if (currentDim.equals(Level.NETHER) && targetDimKey.equals(Level.OVERWORLD)) {
            centerPos = new BlockPos(player.blockPosition().getX() * 8, 0, player.blockPosition().getZ() * 8);
        } else {
            ServerLevel targetLevel = player.getServer().getLevel(targetDimKey);
            centerPos = targetLevel != null ? targetLevel.getSharedSpawnPos() : BlockPos.ZERO;
        }

        int playerChunkX = centerPos.getX() >> 4;
        int playerChunkZ = centerPos.getZ() >> 4;

        LOGGER.info("[BobbySync] Syncing around chunk ({}, {}). Requested radius: {} chunks.", playerChunkX, playerChunkZ, radiusChunks);

        File[] mcaFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (mcaFiles != null) {
            for (File file : mcaFiles) {
                String[] parts = file.getName().split("\\.");
                if (parts.length < 3) continue;
                
                try {
                    int regionX = Integer.parseInt(parts[1]);
                    int regionZ = Integer.parseInt(parts[2]);
                    long serverSize = file.length();
                    
                    // Skip files that are too small to contain actual chunk data
                    if (serverSize < MIN_VALID_FILE_SIZE) {
                        LOGGER.debug("[BobbySync] Skipping {} (Too small: {} bytes)", file.getName(), serverSize);
                        continue;
                    }
                    
                    // Verify the file actually contains chunk data by reading the location table
                    if (!regionFileHasChunks(file)) {
                        LOGGER.debug("[BobbySync] Skipping {} (No chunks found in location table)", file.getName());
                        continue;
                    }
                    
                    Long clientSize = clientExistingRegionsWithSizes != null ? clientExistingRegionsWithSizes.get(file.getName()) : null;
                    
                    int regionMinX = regionX * 32;
                    int regionMaxX = regionMinX + 31;
                    int regionMinZ = regionZ * 32;
                    int regionMaxZ = regionMinZ + 31;
                    
                    int closestX = Math.max(regionMinX, Math.min(playerChunkX, regionMaxX));
                    int closestZ = Math.max(regionMinZ, Math.min(playerChunkZ, regionMaxZ));
                    
                    int distX = Math.abs(playerChunkX - closestX);
                    int distZ = Math.abs(playerChunkZ - closestZ);
                    
                    boolean inRadius = (distX <= radiusChunks && distZ <= radiusChunks) || (radiusChunks < 0);
                    
                    if (inRadius) {
                        if (clientSize == null) {
                            filesToSend.add(file);
                            totalBytes += serverSize;
                        } else {
                            if (clientSize != serverSize) {
                                filesToSend.add(file);
                                totalBytes += serverSize;
                                LOGGER.info("[BobbySync] Adding {} (Size mismatch: client={} server={})", file.getName(), clientSize, serverSize);
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warn("[BobbySync] Invalid region filename: {}", file.getName());
                }
            }
        }

        if (filesToSend.isEmpty()) {
            LOGGER.info("[BobbySync] Player {} already has all regions in radius.", player.getName().getString());
            player.sendSystemMessage(Component.literal("§a[BobbySync] §fYou already have all those chunks!"));
            player.connection.send(new SyncCompletePayload(0, 0));
            activeSyncs.remove(playerId);
            return;
        }

        filesToSend.sort((f1, f2) -> {
            int dist1 = getClosestDistance(playerChunkX, playerChunkZ, f1.getName());
            int dist2 = getClosestDistance(playerChunkX, playerChunkZ, f2.getName());
            return Integer.compare(dist1, dist2);
        });

        LOGGER.info("[BobbySync] Starting sync for {}. Files: {}, Size: {} MB. Limit: {} MB/s", 
                player.getName().getString(), filesToSend.size(), totalBytes / (1024 * 1024), clientBandwidthLimitMBps);

        String dimStr = targetDimKey.location().toString();
        player.connection.send(new SyncStartPayload(dimStr, filesToSend.size(), totalBytes));

        CompletableFuture.runAsync(() -> {
            try {
                long bytesSentThisSecond = 0;
                long lastTimeCheck = System.currentTimeMillis();
                long bytesPerSecondLimit = clientBandwidthLimitMBps > 0 
                        ? (long) clientBandwidthLimitMBps * 1024 * 1024
                        : Long.MAX_VALUE;
                
                int actualFilesSent = 0;
                long actualBytesSent = 0;
                
                for (File file : filesToSend) {
                    if (cancelledSyncs.contains(playerId)) {
                        player.getServer().execute(() -> player.sendSystemMessage(Component.literal("§c[BobbySync] §fSync cancelled.")));
                        break;
                    }

                    long[] result = streamFile(player, file, bytesPerSecondLimit, bytesSentThisSecond, lastTimeCheck);
                    bytesSentThisSecond = result[0];
                    lastTimeCheck = result[1];
                    
                    if (result[2] == 1) {
                        actualFilesSent++;
                        actualBytesSent += file.length();
                    }
                }
                
                if (!cancelledSyncs.contains(playerId)) {
                    LOGGER.info("[BobbySync] Sync complete for {}. Actually sent: {} files, {} MB", 
                        player.getName().getString(), actualFilesSent, actualBytesSent / (1024 * 1024));
                    
                    player.connection.send(new SyncCompletePayload(actualFilesSent, actualBytesSent));
                }
            } catch (Exception e) {
                LOGGER.error("[BobbySync] Sync failed for player {}", player.getName().getString(), e);
            } finally {
                activeSyncs.remove(playerId);
                cancelledSyncs.remove(playerId);
            }
        });
    }

    /**
     * Checks if a region file actually contains chunk data by reading the location table.
     */
    private static boolean regionFileHasChunks(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < 4096) return false;
            
            byte[] header = new byte[4096];
            raf.readFully(header);
            
            for (int i = 0; i < 1024; i++) {
                int offset = i * 4;
                int entry = ((header[offset] & 0xFF) << 16) | ((header[offset + 1] & 0xFF) << 8) | (header[offset + 2] & 0xFF);
                if (entry != 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOGGER.debug("[BobbySync] Could not read region file header for {}", file.getName(), e);
            return false;
        }
    }

    private static int getClosestDistance(int playerChunkX, int playerChunkZ, String fileName) {
        try {
            String[] parts = fileName.split("\\.");
            if (parts.length >= 3) {
                int regionX = Integer.parseInt(parts[1]);
                int regionZ = Integer.parseInt(parts[2]);
                int regionMinX = regionX * 32;
                int regionMaxX = regionMinX + 31;
                int regionMinZ = regionZ * 32;
                int regionMaxZ = regionMinZ + 31;
                int closestX = Math.max(regionMinX, Math.min(playerChunkX, regionMaxX));
                int closestZ = Math.max(regionMinZ, Math.min(playerChunkZ, regionMaxZ));
                return Math.abs(playerChunkX - closestX) + Math.abs(playerChunkZ - closestZ);
            }
        } catch (Exception ignored) {}
        return Integer.MAX_VALUE;
    }

    // Returns [bytesSent, lastTimeCheck, success (1=success, 0=failure)]
    private static long[] streamFile(ServerPlayer player, File file, long bytesPerSecondLimit, long bytesSentThisSecond, long lastTimeCheck) {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
            int totalSize = (int) file.length();
            int offset = 0;

            while (offset < totalSize) {
                if (!player.isAlive() || !player.connection.isAcceptingMessages()) {
                    return new long[]{bytesSentThisSecond, lastTimeCheck, 0};
                }

                int chunkSize = Math.min(CHUNK_SIZE, totalSize - offset);
                byte[] chunk = new byte[chunkSize];
                buffer.get(chunk, 0, chunkSize);

                byte[] compressedData = compressBytes(chunk, chunkSize);
                boolean isLast = (offset + chunkSize) >= totalSize;
                RegionPayload payload = new RegionPayload(file.getName(), compressedData, offset, totalSize, chunkSize, isLast);

                long currentTime = System.currentTimeMillis();
                long elapsed = currentTime - lastTimeCheck;
                
                if (elapsed >= 1000) {
                    bytesSentThisSecond = 0;
                    lastTimeCheck = currentTime;
                } else if (bytesSentThisSecond + chunkSize >= bytesPerSecondLimit) {
                    try {
                        Thread.sleep(1000 - elapsed);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new long[]{bytesSentThisSecond, lastTimeCheck, 0};
                    }
                    bytesSentThisSecond = 0;
                    lastTimeCheck = System.currentTimeMillis();
                }

                player.connection.send(payload);
                // Count UNCOMPRESSED size to make bandwidth limiter work in the End
                bytesSentThisSecond += chunkSize;
                offset += chunkSize;
            }
            return new long[]{bytesSentThisSecond, lastTimeCheck, 1};
        } catch (Exception e) {
            LOGGER.error("[BobbySync] Failed to stream file {}", file.getName(), e);
            return new long[]{bytesSentThisSecond, lastTimeCheck, 0};
        }
    }

    private static byte[] compressBytes(byte[] data, int length) {
        int maxCompressedLength = compressor.maxCompressedLength(length);
        byte[] compressed = new byte[maxCompressedLength];
        int compressedLength = compressor.compress(data, 0, length, compressed, 0, maxCompressedLength);
        byte[] result = new byte[compressedLength];
        System.arraycopy(compressed, 0, result, 0, compressedLength);
        return result;
    }
}