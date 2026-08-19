package com.endsustain.client.renderer;

import com.endsustain.EndSustain;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtils;

/**
 * 落幕之终焉·末影蘸酱 的 GeckoLib 渲染器。<br>
 * 加载逆向恢复的末影蘸酱 Bedrock 模型，并由实体动画控制器驱动 idle / walk / 施法动作。
 */
public class FinaleRenderer extends GeoEntityRenderer<FinaleEndsustainEntity> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(EndSustain.MOD_ID, "textures/entity/finale/default.png");

    public FinaleRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new FinaleGeoModel());
        this.shadowRadius = 0.4F;
    }

    @Override
    protected void applyRotations(FinaleEndsustainEntity entity, PoseStack poseStack,
                                   float ageInTicks, float rotationYaw, float partialTick) {
        // 不调用 super，禁用自动朝向计算
        // 改为跟随实体本身的 yRot
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(
                180.0F - rotationYaw));
    }

    @Override
    public ResourceLocation getTextureLocation(FinaleEndsustainEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(FinaleEndsustainEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        try {
            // 逆向模型按玩家约 2 格高度制作，缩放到 1.8 格高并匹配 0.8 格宽碰撞箱
            poseStack.scale(0.9F, 0.9F, 0.9F);
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    // ===== GeoModel：提供模型、贴图与动画文件 =====

    private static class FinaleGeoModel extends GeoModel<FinaleEndsustainEntity> {
        private static final ResourceLocation MODEL =
                new ResourceLocation(EndSustain.MOD_ID, "geo/finale.geo.json");

        @Override
        public ResourceLocation getModelResource(FinaleEndsustainEntity entity) { return MODEL; }

        @Override
        public void handleAnimations(FinaleEndsustainEntity entity, long instanceId,
                                     AnimationState<FinaleEndsustainEntity> animationState) {
            super.handleAnimations(entity, instanceId, animationState);
            if (!entity.isSleeping()) {
                // 直接覆盖睡眠动画残留的骨骼快照，保证攻击唤醒后眼睛与眉毛立即恢复。
                // sleep 动画会把眼睑三轴缩为 0、眉毛下沉，必须完整复位而不是只改 Y 轴。
                getBone("RightEyesBase").ifPresent(bone -> { bone.setScaleX(1.0F); bone.setScaleY(1.0F); bone.setScaleZ(1.0F); });
                getBone("LeftEyesBase").ifPresent(bone -> { bone.setScaleX(1.0F); bone.setScaleY(1.0F); bone.setScaleZ(1.0F); });
                getBone("RightEyelid").ifPresent(bone -> { bone.setScaleX(1.0F); bone.setScaleY(1.0F); bone.setScaleZ(1.0F); });
                getBone("LeftEyelid").ifPresent(bone -> { bone.setScaleX(1.0F); bone.setScaleY(1.0F); bone.setScaleZ(1.0F); });
                getBone("RightEyebrow").ifPresent(bone -> { bone.setPosX(0.0F); bone.setPosY(0.0F); bone.setPosZ(0.0F); });
                getBone("LeftEyebrow").ifPresent(bone -> { bone.setPosX(0.0F); bone.setPosY(0.0F); bone.setPosZ(0.0F); });
                getBone("AllHead").ifPresent(bone -> bone.setRotX(0.0F));
            }
        }

        @Override
        public ResourceLocation getTextureResource(FinaleEndsustainEntity entity) { return TEXTURE; }

        @Override
        public ResourceLocation getAnimationResource(FinaleEndsustainEntity entity) {
            return new ResourceLocation(EndSustain.MOD_ID, "animations/finale.animation.json");
        }
    }
}
