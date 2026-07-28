package com.kukki.bobbysync;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@OnlyIn(Dist.CLIENT)
public class BobbySyncConfigScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();
    private final Screen parent;
    
    // Track the state of each delete button (0=normal, 1=confirm, 2=done)
    private final Map<String, Integer> deleteStates = new HashMap<>();
    
    public BobbySyncConfigScreen(Screen parent) {
        super(Component.literal("BobbySync Config"));
        this.parent = parent;
    }
    
    @Override
    protected void init() {
        super.init();
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Bandwidth slider
        this.addRenderableWidget(new BandwidthLimitSlider(centerX - 125, centerY - 80, 250, 20));
        
        // Toggle buttons
        this.addRenderableWidget(Button.builder(
            Component.literal("Use Action Bar: " + (Config.USE_ACTION_BAR.get() ? "ON" : "OFF")), 
            button -> {
                boolean current = Config.USE_ACTION_BAR.get();
                Config.USE_ACTION_BAR.set(!current);
                Config.SPEC.save();
                button.setMessage(Component.literal("Use Action Bar: " + (!current ? "ON" : "OFF")));
            }).pos(centerX - 100, centerY - 50).size(200, 20).build());
        
        this.addRenderableWidget(Button.builder(
            Component.literal("Chat Logging: " + (Config.CHAT_LOGGING.get() ? "ON" : "OFF")), 
            button -> {
                boolean current = Config.CHAT_LOGGING.get();
                Config.CHAT_LOGGING.set(!current);
                Config.SPEC.save();
                button.setMessage(Component.literal("Chat Logging: " + (!current ? "ON" : "OFF")));
            }).pos(centerX - 100, centerY - 25).size(200, 20).build());
        
        // Delete buttons - start at y = centerY + 10
        int currentY = centerY + 10;
        
        // "Delete All Data" button
        this.addRenderableWidget(createDeleteButton(centerX - 100, currentY, "Delete All Data", null));
        currentY += 25;
        
        // Get list of dimensions that have data
        List<String> dimensions = getAvailableDimensions();
        
        // Add buttons for each dimension
        for (String dim : dimensions) {
            String displayName = getDimensionDisplayName(dim);
            this.addRenderableWidget(createDeleteButton(centerX - 100, currentY, "Delete " + displayName, dim));
            currentY += 25;
        }
        
        // Done button at the bottom
        this.addRenderableWidget(Button.builder(
            Component.literal("Done"), 
            button -> this.minecraft.setScreen(parent)).pos(centerX - 50, currentY + 10).size(100, 20).build());
    }
    
    private Button createDeleteButton(int x, int y, String text, String dimensionFolder) {
        String buttonId = dimensionFolder == null ? "__all__" : dimensionFolder;
        deleteStates.put(buttonId, 0);
        
        return Button.builder(Component.literal(text), button -> {
            int currentState = deleteStates.get(buttonId);
            
            if (currentState == 0) {
                // First click: Show confirmation
                deleteStates.put(buttonId, 1);
                button.setMessage(Component.literal("Are you Sure ?").withStyle(ChatFormatting.RED));
            } else if (currentState == 1) {
                // Second click: Delete
                deleteStates.put(buttonId, 2);
                button.setMessage(Component.literal("Deleted!").withStyle(ChatFormatting.GREEN));
                button.active = false;
                
                CompletableFuture.runAsync(() -> {
                    Path targetDir;
                    if (dimensionFolder == null) {
                        // Delete all data
                        targetDir = getBobbyRoot();
                    } else {
                        // Delete specific dimension
                        targetDir = getBobbyRoot().resolve("minecraft").resolve(dimensionFolder);
                    }
                    deleteFolder(targetDir);
                });
            }
        }).pos(x, y).size(200, 20).build();
    }
    
    private List<String> getAvailableDimensions() {
        List<String> dimensions = new ArrayList<>();
        try {
            Path bobbyRoot = getBobbyRoot();
            Path minecraftFolder = bobbyRoot.resolve("minecraft");
            
            if (Files.exists(minecraftFolder) && Files.isDirectory(minecraftFolder)) {
                try (var stream = Files.list(minecraftFolder)) {
                    stream.filter(Files::isDirectory)
                          .forEach(path -> dimensions.add(path.getFileName().toString()));
                }
            }
        } catch (IOException e) {
            LOGGER.error("[BobbySync] Failed to scan dimensions", e);
        }
        
        // Ensure vanilla dimensions are always shown first in order
        List<String> ordered = new ArrayList<>();
        if (dimensions.contains("overworld")) {
            ordered.add("overworld");
            dimensions.remove("overworld");
        }
        if (dimensions.contains("the_nether")) {
            ordered.add("the_nether");
            dimensions.remove("the_nether");
        }
        if (dimensions.contains("the_end")) {
            ordered.add("the_end");
            dimensions.remove("the_end");
        }
        // Add remaining (modded) dimensions alphabetically
        dimensions.sort(String::compareTo);
        ordered.addAll(dimensions);
        
        return ordered;
    }
    
    private String getDimensionDisplayName(String folderName) {
        return switch (folderName) {
            case "overworld" -> "Overworld Data";
            case "the_nether" -> "Nether Data";
            case "the_end" -> "End Data";
            default -> formatModdedDimensionName(folderName);
        };
    }
    
    private String formatModdedDimensionName(String folderName) {
        // Convert "twilightforest_forest" to "Twilightforest Forest Data"
        String name = folderName.replace("_", " ");
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (capitalizeNext && Character.isLetter(c)) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
                if (c == ' ') capitalizeNext = true;
            }
        }
        return result.toString() + " Data";
    }
    
    private Path getBobbyRoot() {
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
        
        return gameDir.resolve(".bobby").resolve(serverAddress).resolve(String.valueOf(seedHash));
    }
    
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, "BobbySync Configuration", this.width / 2, 20, 0xFFFFFF);
        graphics.drawCenteredString(this.font, "Drag slider to adjust bandwidth (MB/s)", this.width / 2, 40, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void deleteFolder(Path targetDir) {
        try {
            if (Files.exists(targetDir)) {
                Files.walkFileTree(targetDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            LOGGER.error("[BobbySync] Failed to delete folder: {}", targetDir, e);
        }
    }

    private static class BandwidthLimitSlider extends AbstractSliderButton {
        public BandwidthLimitSlider(int x, int y, int width, int height) {
            super(x, y, width, height, 
                  Component.literal("Bandwidth Limit: " + Config.BANDWIDTH_LIMIT_MBPS.get() + " MB/s"), 
                  Config.BANDWIDTH_LIMIT_MBPS.get() / 100.0);
        }
        @Override
        protected void updateMessage() {
            int val = (int) Math.round(this.value * 100.0);
            this.setMessage(Component.literal("Bandwidth Limit: " + val + " MB/s"));
        }
        @Override
        protected void applyValue() {
            int val = (int) Math.round(this.value * 100.0);
            Config.BANDWIDTH_LIMIT_MBPS.set(val);
            Config.SPEC.save();
        }
    }
}