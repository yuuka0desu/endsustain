package com.endsustain.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class EndSustainConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.BooleanValue KICK_PLAYERS_ON_OVERFLOW;
    public static final ForgeConfigSpec.BooleanValue ENABLE_FINALE_SPAWN_BOOST;
    public static final ForgeConfigSpec.IntValue FINALE_MOB_CAP_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue FINALE_SPAWN_ATTEMPT_MULTIPLIER;

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
        COMMON_SPEC = builder.build();
    }

    private EndSustainConfig() {}
}
