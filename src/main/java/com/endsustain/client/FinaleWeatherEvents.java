package com.endsustain.client;

import com.endsustain.EndSustain;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FinaleWeatherEvents {
    private static final ResourceLocation VOID_BLOSSOM_SPIKE = new ResourceLocation(
            "bosses_of_mass_destruction", "textures/entity/void_blossom_spike.png");
    private static final List<ClientThornSpike> THORN_SPIKES = new ArrayList<>();
    private static final int THORN_LIFETIME = 10;
    private static long screenTearUntil;
    private static long screenTearStart;

    private FinaleWeatherEvents() {}

    public static void startScreenTear(int durationTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        screenTearStart = minecraft.level.getGameTime();
        screenTearUntil = screenTearStart + durationTicks;
    }

    public static void addThornSpikes(List<BlockPos> positions) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        long startTick = minecraft.level.getGameTime();
        for (BlockPos position : positions) {
            THORN_SPIKES.add(new ClientThornSpike(position.immutable(), startTick,
                    Mth.getSeed(position), 4.0F + Math.floorMod(position.hashCode(), 6) * 0.1F));
        }
    }

    @SubscribeEvent
    public static void renderIndependentEffects(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (FinalePresenceEffects.isGlobalEnvironmentActive()) {
            FinaleWeatherRenderer.render(event.getPoseStack(), event.getPartialTick(), event.getCamera());
        }
        renderThornSpikes(event);
    }

    @SubscribeEvent
    public static void renderScreenTear(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level.getGameTime() >= screenTearUntil) return;
        float duration = Math.max(1.0F, screenTearUntil - screenTearStart);
        float age = minecraft.level.getGameTime() - screenTearStart + event.getPartialTick();
        float intensity = Mth.sin(Math.min(1.0F, age / duration) * Mth.PI);
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int shade = Math.min(150, Math.round(150.0F * intensity));
        graphics.fill(0, 0, width, height, shade << 24 | 0x16001F);
        for (int i = 0; i < 7; i++) {
            int x = (int) ((i + 0.5D) * width / 7.0D + Math.sin(age * 0.9D + i) * 14.0D);
            int tearWidth = 2 + (i % 3);
            graphics.fill(x - tearWidth, 0, x + tearWidth, height, 0xCC6D13A8);
            graphics.fill(x, 0, x + 1, height, 0xFFF2D7FF);
        }
        for (int i = 0; i < 5; i++) {
            int y = (int) ((i + 1.0D) * height / 6.0D + Math.cos(age + i) * 8.0D);
            graphics.fill(0, y, width, y + 1, 0x88470070);
        }
    }

    private static void renderThornSpikes(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || THORN_SPIKES.isEmpty()) return;
        long gameTime = minecraft.level.getGameTime();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        RenderType renderType = RenderType.entityTranslucent(VOID_BLOSSOM_SPIKE);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer vertices = buffers.getBuffer(renderType);

        try {
            Iterator<ClientThornSpike> iterator = THORN_SPIKES.iterator();
            while (iterator.hasNext()) {
                ClientThornSpike spike = iterator.next();
                float age = gameTime - spike.startTick + event.getPartialTick();
                if (age >= THORN_LIFETIME) {
                    iterator.remove();
                    continue;
                }
                renderThornSpike(poseStack, vertices, camera, spike, age);
            }
        } finally {
            buffers.endBatch(renderType);
        }
    }

    private static void renderThornSpike(PoseStack poseStack, VertexConsumer vertices, Vec3 camera,
                                         ClientThornSpike spike, float age) {
        float growth = Mth.sin(Math.min(age / 4.0F, 1.0F) * Mth.HALF_PI);
        float fade = age <= 8.0F ? 1.0F : Math.max(0.0F, (THORN_LIFETIME - age) / 2.0F);
        float height = spike.height;
        float width = 0.34375F * height;
        double buriedOffset = (growth - 1.0F) * height;
        double x = spike.position.getX() + 0.5D - camera.x;
        double y = spike.position.getY() + buriedOffset - camera.y;
        double z = spike.position.getZ() + 0.5D - camera.z;
        float rotation = (float) ((spike.seed & 0xFFFFL) / 65535.0D * Math.PI);

        poseStack.pushPose();
        try {
            poseStack.translate(x, y, z);
            PoseStack.Pose pose = poseStack.last();
            for (int plane = 0; plane < 2; plane++) {
                float angle = rotation + plane * Mth.HALF_PI;
                float dx = Mth.cos(angle) * width * 0.5F;
                float dz = Mth.sin(angle) * width * 0.5F;
                float normalX = Mth.sin(angle);
                float normalZ = -Mth.cos(angle);
                vertex(vertices, pose, -dx, 0.0F, -dz, 0.0F, 1.0F, fade, normalX, normalZ);
                vertex(vertices, pose, -dx, height, -dz, 0.0F, 0.0F, fade, normalX, normalZ);
                vertex(vertices, pose, dx, height, dz, 1.0F, 0.0F, fade, normalX, normalZ);
                vertex(vertices, pose, dx, 0.0F, dz, 1.0F, 1.0F, fade, normalX, normalZ);
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose,
                               float x, float y, float z, float u, float v, float alpha,
                               float normalX, float normalZ) {
        Matrix4f positionMatrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        vertices.vertex(positionMatrix, x, y, z)
                .color(255, 255, 255, Math.round(alpha * 255.0F))
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, normalX, 0.0F, normalZ)
                .endVertex();
    }

    private record ClientThornSpike(BlockPos position, long startTick, long seed, float height) {}
}
