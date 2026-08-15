package com.endsustain.compat.ftbquests;

import com.endsustain.EndSustain;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class FtbQuestsCompat {
    private static boolean initialized, available, warned;
    private static Object serverQuestFile;
    private static Method getQuest, teamDataGet, isCompleted;

    private FtbQuestsCompat() {}

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> serverFileClass = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
            Class<?> teamDataClass = Class.forName("dev.ftb.mods.ftbquests.quest.TeamData");
            Class<?> questClass = Class.forName("dev.ftb.mods.ftbquests.quest.Quest");
            Field instance = serverFileClass.getField("INSTANCE");
            serverQuestFile = instance.get(null);
            getQuest = serverFileClass.getMethod("getQuest", long.class);
            teamDataGet = teamDataClass.getMethod("get", net.minecraft.world.entity.player.Player.class);
            isCompleted = teamDataClass.getMethod("isCompleted",
                    Class.forName("dev.ftb.mods.ftbquests.quest.QuestObject"));
            available = serverQuestFile != null;
            EndSustain.LOGGER.info("[落幕终焉] FTB Quests 终末之路兼容绑定成功");
        } catch (Throwable t) {
            EndSustain.LOGGER.warn("[落幕终焉] FTB Quests 终末之路兼容未启用: {}", t.toString());
        }
    }

    public static boolean isQuestCompleted(ServerPlayer player, String hexId) {
        initialize();
        if (!available) return false;
        try {
            long id = Long.parseUnsignedLong(hexId, 16);
            Object quest = getQuest.invoke(serverQuestFile, id);
            if (quest == null) return false;
            Object data = teamDataGet.invoke(null, player);
            return data != null && (boolean) isCompleted.invoke(data, quest);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                EndSustain.LOGGER.warn("[落幕终焉] 读取 FTB Quest {} 失败: {}", hexId, t.toString());
            }
            return false;
        }
    }
}
