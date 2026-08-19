package com.endsustain.client;

import com.endsustain.EndSustain;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/** 独立世界特效雨，不调用或依赖原版雨雪渲染。 */
public final class FinaleWeatherRenderer {
    private static final ResourceLocation PURPLE_RAIN =
            new ResourceLocation(EndSustain.MOD_ID, "textures/environment/purple_rain.png");

    private FinaleWeatherRenderer() {}

    public static void render(PoseStack poseStack, float partialTick, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!FinalePresenceEffects.isGlobalEnvironmentActive() || minecraft.level == null) return;

        double cameraX = camera.getPosition().x;
        double cameraY = camera.getPosition().y;
        double cameraZ = camera.getPosition().z;
        int centerX = Mth.floor(cameraX);
        int centerY = Mth.floor(cameraY);
        int centerZ = Mth.floor(cameraZ);
        // 正向雨：纹理从天空向地面滚动，速度接近原版降雨。
        float scroll = ((minecraft.level.getGameTime() + partialTick) * 0.035F) % 1.0F;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, PURPLE_RAIN);

            poseStack.pushPose();
            try {
                Matrix4f matrix = poseStack.last().pose();
                Tesselator tessellator = Tesselator.getInstance();
                BufferBuilder buffer = tessellator.getBuilder();
                buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

                int radius = minecraft.options.graphicsMode().get().getId() >= 1 ? 10 : 6;
                for (int x = centerX - radius; x <= centerX + radius; x++) {
                    for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                        int dx = x - centerX;
                        int dz = z - centerZ;
                        int distanceSq = dx * dx + dz * dz;
                        if (distanceSq > radius * radius) continue;
                        // 采用棋盘式稀疏采样，密度约为当前实现的一半，接近原版雨幕观感。
                        if (((x * 31 + z * 17) & 1) != 0) continue;

                        double length = Math.max(1.0D, Math.sqrt(distanceSq));
                        double sideX = -dz / length * 0.48D;
                        double sideZ = dx / length * 0.48D;
                        double rx = x + 0.5D - cameraX;
                        double rz = z + 0.5D - cameraZ;
                        double minY = centerY - 8.0D - cameraY;
                        double maxY = centerY + 8.0D - cameraY;
                        double distance = Math.sqrt(distanceSq) / radius;
                        int alpha = (int) (Mth.clamp((1.0D - distance * 0.72D) * 0.62D, 0.10D, 0.62D) * 255.0D);
                        float v0 = scroll + ((x * 13 + z * 29) & 15) / 16.0F;
                        float v1 = v0 + 1.75F;

                        vertex(buffer, matrix, rx - sideX, maxY, rz - sideZ, 0.0F, v0, alpha);
                        vertex(buffer, matrix, rx + sideX, maxY, rz + sideZ, 1.0F, v0, alpha);
                        vertex(buffer, matrix, rx + sideX, minY, rz + sideZ, 1.0F, v1, alpha);
                        vertex(buffer, matrix, rx - sideX, minY, rz - sideZ, 0.0F, v1, alpha);
                    }
                }
                tessellator.end();
            } finally {
                poseStack.popPose();
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                               float u, float v, int alpha) {
        buffer.vertex(matrix, (float) x, (float) y, (float) z)
                .uv(u, v).color(176, 34, 255, alpha).endVertex();
    }
}
