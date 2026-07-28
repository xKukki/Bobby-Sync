package com.kukki.bobbysync;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec.BooleanValue CHAT_LOGGING;
    public static final ModConfigSpec.BooleanValue USE_ACTION_BAR;
    public static final ModConfigSpec.IntValue BANDWIDTH_LIMIT_MBPS;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("BobbySync Client Configuration");
        
        CHAT_LOGGING = BUILDER
                .comment("Show download progress and status in the client's chat.")
                .define("chatLogging", true);
                
        USE_ACTION_BAR = BUILDER
                .comment("Show progress above the hotbar instead of in chat (prevents spam).")
                .define("useActionBar", true);
                
        BANDWIDTH_LIMIT_MBPS = BUILDER
                .comment("Maximum bandwidth to use for sync in MB/s. (0 = unlimited, we recommend 10+)")
                .defineInRange("bandwidthLimitMBps", 10, 0, 100);
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}