package com.endsustain.client.renderer;

import com.endsustain.entity.boss.EndsustainBladeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;

public class EndsustainBladeRenderer extends EntityRenderer<EndsustainBladeEntity> {
    private final ItemRenderer itemRenderer;

    public EndsustainBladeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(EndsustainBladeEntity blade, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        float yaw = Mth.lerp(partialTick, blade.yRotO, blade.getYRot());
        float pitch = Mth.lerp(partialTick, blade.xRotO, blade.getXRot());

        // 与原版 ThrownTridentRenderer 相同：将实体局部长轴对准飞行向量。
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(pitch + 90.0F));

        // 在原 45° 轴向校正基础上翻转 180°，确保刀尖朝向飞行方向、刀柄位于后方。
        poseStack.mulPose(Axis.ZP.rotationDegrees(225.0F));
        poseStack.scale(1.35F, 1.35F, 1.35F);

        this.itemRenderer.renderStatic(
                blade.getItem(),
                ItemDisplayContext.GROUND,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                buffer,
                blade.level(),
                blade.getId()
        );

        poseStack.popPose();
        super.render(blade, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(EndsustainBladeEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
