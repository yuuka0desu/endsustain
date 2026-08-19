package com.endsustain.client.renderer;

import com.endsustain.EndSustain;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SmallZhanjiangCompanionRenderer extends GeoEntityRenderer<SmallZhanjiangCompanionEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(EndSustain.MOD_ID, "textures/entity/finale/default.png");
    public SmallZhanjiangCompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new Model()); shadowRadius = 0.0F;
    }
    @Override public void render(SmallZhanjiangCompanionEntity entity, float yaw, float partialTick,
                                 PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        try {
            pose.scale(0.25F, 0.25F, 0.25F);
            super.render(entity, yaw, partialTick, pose, buffers, light);
        } finally {
            pose.popPose();
        }
    }
    @Override public ResourceLocation getTextureLocation(SmallZhanjiangCompanionEntity entity) { return TEXTURE; }
    private static final class Model extends GeoModel<SmallZhanjiangCompanionEntity> {
        @Override public ResourceLocation getModelResource(SmallZhanjiangCompanionEntity e) { return new ResourceLocation(EndSustain.MOD_ID, "geo/small_zhanjiang_companion.geo.json"); }
        @Override public ResourceLocation getTextureResource(SmallZhanjiangCompanionEntity e) { return TEXTURE; }
        @Override public ResourceLocation getAnimationResource(SmallZhanjiangCompanionEntity e) { return new ResourceLocation(EndSustain.MOD_ID, "animations/finale.animation.json"); }

        @Override
        public void handleAnimations(SmallZhanjiangCompanionEntity entity, long instanceId,
                                     AnimationState<SmallZhanjiangCompanionEntity> animationState) {
            super.handleAnimations(entity, instanceId, animationState);
            resetBone("Root");
            resetBone("AllBody");
            resetBone("AllHead");
            resetBone("FLongHair");
            resetBone("MLongHair");
            resetBone("LongHair");
            resetBone("hl_side_L");
            resetBone("hl_side_R");
            resetBone("hl_m");
            resetBone("bone4");
            resetBone("bone6");
            resetBone("bone7");
            resetBone("bone8");
            resetBone("RightEyelid");
            resetBone("LeftEyelid");
            resetBone("RightEyebrow");
            resetBone("LeftEyebrow");
        }

        private void resetBone(String name) {
            getBone(name).ifPresent(this::resetBone);
        }

        private void resetBone(GeoBone bone) {
            bone.setPosX(0.0F); bone.setPosY(0.0F); bone.setPosZ(0.0F);
            bone.setRotX(0.0F); bone.setRotY(0.0F); bone.setRotZ(0.0F);
            bone.setScaleX(1.0F); bone.setScaleY(1.0F); bone.setScaleZ(1.0F);
        }
    }
}
