package com.kukki.bobbysync;

import com.kukki.bobbysync.client.ClientChunkReceiver;
import com.kukki.bobbysync.network.BobbySyncNetwork;
import com.kukki.bobbysync.network.RequestPayload;
import com.kukki.bobbysync.server.ServerChunkStreamer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mod(BobbySync.MODID)
public class BobbySync {
    public static final String MODID = "bobbysync";

    public BobbySync(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        BobbySyncNetwork.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bobbysync")
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal("cancel").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayer();
                    if (p != null && ServerChunkStreamer.isActive(p.getUUID())) {
                        ServerChunkStreamer.cancelSync(p.getUUID());
                        ctx.getSource().sendSuccess(() -> Component.literal("§c[BobbySync] §fCancelling sync..."), true);
                    } else {
                        ctx.getSource().sendFailure(Component.literal("§c[BobbySync] §fNo active sync to cancel."));
                    }
                    return 1;
                }))
        );
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerChunkStreamer.onPlayerLogout(player.getUUID());
        }
    }

    @SubscribeEvent
    @OnlyIn(Dist.CLIENT)
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        var syncCmd = Commands.literal("sync");

        // 1. /bobbysync sync (Current dimension, all chunks)
        syncCmd.executes(ctx -> executeClientSync(-1, null, ctx));

        // BRANCH A: RADIUS FIRST
        var radiusArg = Commands.argument("radius", IntegerArgumentType.integer(0))
            .executes(ctx -> executeClientSync(IntegerArgumentType.getInteger(ctx, "radius"), null, ctx));
            
        radiusArg.then(Commands.argument("dimension", ResourceLocationArgument.id())
            .suggests(this::suggestDimensions)
            .executes(ctx -> executeClientSync(
                IntegerArgumentType.getInteger(ctx, "radius"), 
                ResourceLocationArgument.getId(ctx, "dimension").toString(), 
                ctx))
        );
        syncCmd.then(radiusArg);

        // BRANCH B: DIMENSION FIRST
        var dimArg = Commands.argument("dimension", ResourceLocationArgument.id())
            .suggests(this::suggestDimensions)
            .executes(ctx -> executeClientSync(-1, ResourceLocationArgument.getId(ctx, "dimension").toString(), ctx));
            
        dimArg.then(Commands.argument("radius", IntegerArgumentType.integer(0))
            .executes(ctx -> executeClientSync(
                IntegerArgumentType.getInteger(ctx, "radius"), 
                ResourceLocationArgument.getId(ctx, "dimension").toString(), 
                ctx))
        );
        syncCmd.then(dimArg);

        event.getDispatcher().register(Commands.literal("bobbysync")
            .then(syncCmd)
            .then(Commands.literal("config").executes(ctx -> { 
                openConfigScreen(); 
                return 1; 
            }))
        );
    }

    // FIXED: Correct signature for Brigadier's SuggestionProvider
    // Takes (CommandContext, SuggestionsBuilder) and returns CompletableFuture<Suggestions>
    private CompletableFuture<Suggestions> suggestDimensions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        List<ResourceLocation> suggestions = new ArrayList<>();
        suggestions.add(ResourceLocation.parse("minecraft:overworld"));
        suggestions.add(ResourceLocation.parse("minecraft:the_nether"));
        suggestions.add(ResourceLocation.parse("minecraft:the_end"));
        
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.registryAccess() != null) {
                var dimLookup = mc.level.registryAccess().lookup(Registries.DIMENSION);
                if (dimLookup.isPresent()) {
                    dimLookup.get().listElementIds().map(ResourceKey::location).forEach(suggestions::add);
                }
            }
        } catch (Throwable t) {
            // Silently ignore registry errors to prevent TAB crashes
        }
        
        return SharedSuggestionProvider.suggestResource(suggestions, builder);
    }

    @OnlyIn(Dist.CLIENT)
    private void openConfigScreen() {
        Minecraft.getInstance().setScreen(new BobbySyncConfigScreen(null));
    }

    @OnlyIn(Dist.CLIENT)
    private int executeClientSync(int radiusChunks, String targetDimension, CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        
        String dimStr = (targetDimension != null) ? targetDimension : mc.level.dimension().location().toString();
        
        String radiusMsg = radiusChunks == -1 ? "entire" : radiusChunks + " chunks";
        ClientChunkReceiver.sendChatMessage("§e[BobbySync] §fRequesting sync for " + radiusMsg + " in " + dimStr + "...");

        RequestPayload payload = new RequestPayload(
                radiusChunks,
                dimStr,
                ClientChunkReceiver.getExistingRegionsWithSizes(dimStr),
                Config.BANDWIDTH_LIMIT_MBPS.get()
        );
        PacketDistributor.sendToServer(payload);
        return 1;
    }
}