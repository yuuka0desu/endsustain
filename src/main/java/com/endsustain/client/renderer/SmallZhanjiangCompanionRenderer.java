package com.endsustain.client.renderer;

import com.endsustain.EndSustain;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SmallZhanjiangCompanionRenderer extends GeoEntityRenderer<SmallZhanjiangCompanionEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(EndSustain.MOD_ID, "textures/entity/finale/default.png");
    public SmallZhanjiangCompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new Model()); shadowRadius = 0.0F;
    }
    @Override public void render(SmallZhanjiangCompanionEntity entity, float yaw, float partialTick,
                                 PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose(); pose.scale(0.25F, 0.25F, 0.25F);
        super.render(entity, yaw, partialTick, pose, buffers, light); pose.popPose();
    }
    @Override public ResourceLocation getTextureLocation(SmallZhanjiangCompanionEntity entity) { return TEXTURE; }
    private static final class Model extends GeoModel<SmallZhanjiangCompanionEntity> {
        @Override public ResourceLocation getModelResource(SmallZhanjiangCompanionEntity e) { return new ResourceLocation(EndSustain.MOD_ID, "geo/finale.geo.json"); }
        @Override public ResourceLocation getTextureResource(SmallZhanjiangCompanionEntity e) { return TEXTURE; }
        @Override public ResourceLocation getAnimationResource(SmallZhanjiangCompanionEntity e) { return new ResourceLocation(EndSustain.MOD_ID, "animations/finale.animation.json"); }
    }
}
