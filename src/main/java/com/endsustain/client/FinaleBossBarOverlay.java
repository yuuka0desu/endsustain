package com.endsustain.client;

import com.endsustain.EndSustain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FinaleBossBarOverlay {
    private static final int BAR_WIDTH = 220;
    private static final int BAR_HEIGHT = 12;
    private static final java.util.List<String> FINALE_NAME_KEYWORDS = java.util.List.of(
            "落幕之终焉", "末影蘸酱", "Finale Endsustain", "Endsustain", "Ender Zhajiang");

    private FinaleBossBarOverlay() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void renderFinaleBossBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
        LerpingBossEvent bossEvent = event.getBossEvent();
        String bossName = bossEvent.getName().getString();
        if (FINALE_NAME_KEYWORDS.stream().noneMatch(bossName::contains)) return;

        event.setCanceled(true);
        event.setIncrement(38);

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int centerX = event.getWindow().getGuiScaledWidth() / 2;
        int x = centerX - BAR_WIDTH / 2;
        int y = event.getY() + 2;
        float progress = Mth.clamp(bossEvent.getProgress(), 0.0F, 1.0F);
        PhaseStyle phase = PhaseStyle.fromProgress(progress);

        long time = System.currentTimeMillis();
        float pulse = 0.5F + 0.5F * (float)Math.sin(time / 180.0D);
        int glowAlpha = 38 + (int)(42.0F * pulse);
        int glowColor = argb(glowAlpha, phase.glowRed, phase.glowGreen, phase.glowBlue);

        // 魔法光晕与深色底板。
        graphics.fill(x - 7, y - 6, x + BAR_WIDTH + 7, y + BAR_HEIGHT + 6, glowColor);
        graphics.fill(x - 5, y - 4, x + BAR_WIDTH + 5, y + BAR_HEIGHT + 4, 0xD80A0613);

        // 紫金双层边框。
        graphics.fill(x - 3, y - 2, x + BAR_WIDTH + 3, y + BAR_HEIGHT + 2, phase.frameColor);
        graphics.fill(x - 2, y - 1, x + BAR_WIDTH + 2, y + BAR_HEIGHT + 1, 0xFF311640);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xF20C0912);

        int filled = Mth.floor((BAR_WIDTH - 4) * progress);
        if (filled > 0) {
            int fillLeft = x + 2;
            int fillRight = fillLeft + filled;
            graphics.fillGradient(fillLeft, y + 2, fillRight, y + BAR_HEIGHT - 2,
                    phase.fillTopColor, phase.fillBottomColor);
            graphics.fill(fillLeft, y + 2, fillRight, y + 3, phase.highlightColor);
        }

        // 阶段刻度：10%、15%、50%，另加规则分段线。
        drawThreshold(graphics, x, y, 0.10F, 0xFF71F4FF);
        drawThreshold(graphics, x, y, 0.15F, 0xFFFFC857);
        drawThreshold(graphics, x, y, 0.50F, 0xFFE77CFF);
        for (int i = 1; i < 10; i++) {
            int markerX = x + Mth.floor(BAR_WIDTH * (i / 10.0F));
            graphics.fill(markerX, y + 4, markerX + 1, y + BAR_HEIGHT - 3, 0x503D244B);
        }

        // 两侧魔法菱形符文。
        drawRune(graphics, x - 11, y + BAR_HEIGHT / 2, phase.runeColor, pulse);
        drawRune(graphics, x + BAR_WIDTH + 10, y + BAR_HEIGHT / 2, phase.runeColor, pulse);

        Component title = bossEvent.getName();
        graphics.drawCenteredString(font, title, centerX, y - 12, phase.titleColor);

        String percent = String.format(java.util.Locale.ROOT, "%.1f%%", progress * 100.0F);
        graphics.drawCenteredString(font, Component.literal(percent), centerX, y + 2, 0xFFF8F2FF);
        graphics.drawCenteredString(font, Component.literal(phase.label), centerX, y + BAR_HEIGHT + 5, phase.labelColor);
    }

    private static void drawThreshold(GuiGraphics graphics, int x, int y, float threshold, int color) {
        int markerX = x + Mth.floor(BAR_WIDTH * threshold);
        graphics.fill(markerX - 1, y - 1, markerX + 1, y + BAR_HEIGHT + 1, 0xA0000000);
        graphics.fill(markerX, y, markerX + 1, y + BAR_HEIGHT, color);
    }

    private static void drawRune(GuiGraphics graphics, int centerX, int centerY, int color, float pulse) {
        int bright = 130 + (int)(125.0F * pulse);
        int pulsed = (color & 0x00FFFFFF) | (bright << 24);
        graphics.fill(centerX - 1, centerY - 5, centerX + 2, centerY + 6, pulsed);
        graphics.fill(centerX - 5, centerY - 1, centerX + 6, centerY + 2, pulsed);
        graphics.fill(centerX - 3, centerY - 3, centerX + 4, centerY + 4, color);
        graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, 0xFFF9F1FF);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (Mth.clamp(alpha, 0, 255) << 24)
                | (Mth.clamp(red, 0, 255) << 16)
                | (Mth.clamp(green, 0, 255) << 8)
                | Mth.clamp(blue, 0, 255);
    }

    private enum PhaseStyle {
        FIRST("第一乐章 · 魔法降临", 0xFFB44CFF, 0xFF8E2DE2, 0xFF3A0D61,
                0xFFC774FF, 0xFFBF76FF, 0xFFDDA6FF, 180, 68, 255),
        CHARM("第二乐章 · 魅惑终幕", 0xFFFF4FD8, 0xFFE52DAA, 0xFF65043E,
                0xFFFF8BE8, 0xFFFF67DE, 0xFFFFA8EC, 255, 52, 194),
        ULTIMATE("终结乐章 · 必杀解放", 0xFFFFC857, 0xFFFF4A3D, 0xFF720006,
                0xFFFFE18A, 0xFFFFD05F, 0xFFFFE3A6, 255, 64, 32),
        FINAL("最终乐章 · 星辰裁决", 0xFF65F6FF, 0xFF6E4BFF, 0xFF21005C,
                0xFFA6FCFF, 0xFF85F7FF, 0xFFBCFAFF, 55, 222, 255);

        private final String label;
        private final int frameColor;
        private final int fillTopColor;
        private final int fillBottomColor;
        private final int highlightColor;
        private final int titleColor;
        private final int labelColor;
        private final int runeColor;
        private final int glowRed;
        private final int glowGreen;
        private final int glowBlue;

        PhaseStyle(String label, int frameColor, int fillTopColor, int fillBottomColor,
                   int highlightColor, int titleColor, int labelColor,
                   int glowRed, int glowGreen, int glowBlue) {
            this.label = label;
            this.frameColor = frameColor;
            this.fillTopColor = fillTopColor;
            this.fillBottomColor = fillBottomColor;
            this.highlightColor = highlightColor;
            this.titleColor = titleColor;
            this.labelColor = labelColor;
            this.runeColor = frameColor;
            this.glowRed = glowRed;
            this.glowGreen = glowGreen;
            this.glowBlue = glowBlue;
        }

        private static PhaseStyle fromProgress(float progress) {
            if (progress < 0.10F) return FINAL;
            if (progress < 0.15F) return ULTIMATE;
            if (progress < 0.50F) return CHARM;
            return FIRST;
        }
    }
}
