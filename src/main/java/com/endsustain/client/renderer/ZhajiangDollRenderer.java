package com.endsustain.client.renderer;

import com.endsustain.item.ZhajiangDollItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class ZhajiangDollRenderer extends GeoItemRenderer<ZhajiangDollItem> {
    public ZhajiangDollRenderer() {
        super(new DollModel());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext,
                             PoseStack poseStack, MultiBufferSource bufferSource,
                             int packedLight, int packedOverlay) {
        poseStack.pushPose();
        if (displayContext == ItemDisplayContext.GUI) {
            poseStack.translate(0.985D, -0.2925D, 0.0D);
        } else {
            poseStack.translate(0.36D, 0.02D, 0.0D);
        }
        poseStack.scale(0.52F, 0.52F, 0.52F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        super.renderByItem(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }

    private static final class DollModel extends GeoModel<ZhajiangDollItem> {
        private static final ResourceLocation MODEL =
                new ResourceLocation("endsustain", "geo/zhajiang_doll.geo.json");
        private static final ResourceLocation TEXTURE =
                new ResourceLocation("endsustain", "textures/item/zhajiang_doll.png");
        private static final ResourceLocation ANIMATION =
                new ResourceLocation("endsustain", "animations/zhajiang_doll.animation.json");

        @Override
        public ResourceLocation getModelResource(ZhajiangDollItem item) {
            return MODEL;
        }

        @Override
        public ResourceLocation getTextureResource(ZhajiangDollItem item) {
            return TEXTURE;
        }

        @Override
        public ResourceLocation getAnimationResource(ZhajiangDollItem item) {
            return ANIMATION;
        }
    }
}
