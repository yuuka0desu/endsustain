package com.endsustain.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class EndSustainConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.BooleanValue KICK_PLAYERS_ON_OVERFLOW;
    public static final ForgeConfigSpec.BooleanValue ENABLE_FINALE_SPAWN_BOOST;
    public static final ForgeConfigSpec.IntValue FINALE_MOB_CAP_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue FINALE_SPAWN_ATTEMPT_MULTIPLIER;
    /** 音乐系统是否启用（服务端权威：决定是否下发音乐包）。 */
    public static final ForgeConfigSpec.BooleanValue FINALE_MUSIC_ENABLED;
    /** 音乐下发半径（格）。与 Boss 血条追踪范围是两套独立机制。 */
    public static final ForgeConfigSpec.IntValue FINALE_MUSIC_RADIUS;

    /** 客户端专用配置：仅影响本机播放，不下发、不广播。 */
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.DoubleValue FINALE_MUSIC_VOLUME;
    public static final ForgeConfigSpec.IntValue FINALE_MUSIC_FADE_TICKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("endsustain_blade");
        KICK_PLAYERS_ON_OVERFLOW = builder
                .comment("超出 32 位整数伤害上限时，是否将命中的玩家踢出服务器")
                .define("kickPlayersOnOverflow", false);
        builder.pop();

        builder.push("finale_environment");
        ENABLE_FINALE_SPAWN_BOOST = builder
                .comment("末影蘸酱所在区块加载时，是否启用自然刷怪增强（默认关闭）")
                .define("enableFinaleSpawnBoost", false);
        FINALE_MOB_CAP_MULTIPLIER = builder
                .comment("自然刷怪容量上限倍率")
                .defineInRange("finaleMobCapMultiplier", 3, 1, 16);
        FINALE_SPAWN_ATTEMPT_MULTIPLIER = builder
                .comment("自然刷怪尝试频率倍率")
                .defineInRange("finaleSpawnAttemptMultiplier", 3, 1, 16);
        builder.pop();

        builder.push("finale_music");
        FINALE_MUSIC_ENABLED = builder
                .comment("是否为落幕之终焉启用战斗背景音乐")
                .define("enabled", true);
        FINALE_MUSIC_RADIUS = builder
                .comment("音乐下发半径（格）：仅该范围内的玩家会收到音轨")
                .defineInRange("radius", 64, 16, 256);
        builder.pop();

        COMMON_SPEC = builder.build();

        // ---- 客户端配置：只有本机播放相关的项 ----
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        clientBuilder.push("finale_music_client");
        FINALE_MUSIC_VOLUME = clientBuilder
                .comment("音乐播放音量（本机设置，不影响其他玩家）")
                .defineInRange("volume", 1.0D, 0.0D, 1.0D);
        FINALE_MUSIC_FADE_TICKS = clientBuilder
                .comment("淡入淡出时长（tick，本机设置）")
                .defineInRange("fadeTicks", 40, 0, 200);
        clientBuilder.pop();
        CLIENT_SPEC = clientBuilder.build();
    }

    private EndSustainConfig() {}
}
