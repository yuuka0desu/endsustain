package com.endsustain.client;

import com.endsustain.EndSustain;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class FinaleStoryData {
    private static String[] texts;
    private FinaleStoryData() {}

    public static String get(int index) {
        if (texts == null) load();
        return index >= 0 && index < texts.length ? texts[index] : "故事文本缺失";
    }

    private static void load() {
        texts = new String[11];
        try {
            ResourceLocation id = new ResourceLocation(EndSustain.MOD_ID, "finale_path/story.json");
            var resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id);
            try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                JsonArray nodes = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("nodes");
                for (int i = 0; i < Math.min(texts.length, nodes.size()); i++) {
                    JsonObject node = nodes.get(i).getAsJsonObject();
                    texts[i] = node.get("text").getAsString();
                }
            }
        } catch (Throwable t) {
            EndSustain.LOGGER.error("[落幕终焉] 加载完整故事文本失败: {}", t.toString());
        }
        for (int i = 0; i < texts.length; i++) if (texts[i] == null) texts[i] = "故事文本缺失";
    }
}
