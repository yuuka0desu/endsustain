package com.endsustain.client;

import com.endsustain.EndSustain;
import com.endsustain.client.renderer.EndsustainBladeRenderer;
import com.endsustain.client.renderer.FinaleRenderer;
import com.endsustain.client.renderer.SmallZhanjiangCompanionRenderer;
import com.endsustain.entity.ModEntities;
import net.minecraft.client.renderer.entity.PigRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.FINALE.get(), FinaleRenderer::new);
        event.registerEntityRenderer(ModEntities.SMALL_ZHANJIANG_COMPANION.get(), SmallZhanjiangCompanionRenderer::new);
        event.registerEntityRenderer(ModEntities.QUN_U.get(), ctx -> new PigRenderer(ctx));
        event.registerEntityRenderer(ModEntities.ENDSUSTAIN_BLADE_ENTITY.get(), EndsustainBladeRenderer::new);
        event.registerEntityRenderer(ModEntities.ILUSI_ATE_PROJECTILE.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.ILUSI_HOMING_TRIDENT.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.SPELL_PROJECTILE.get(), NoopRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        EndSustain.LOGGER.info("[终焉维系] 客户端渲染初始化完成");
    }

    public static class NoopRenderer extends EntityRenderer<Entity> {
        private static final ResourceLocation TEX = new ResourceLocation(EndSustain.MOD_ID, "textures/entity/finale/default.png");
        public NoopRenderer(EntityRendererProvider.Context ctx) { super(ctx); }
        @Override public void render(Entity e, float y, float p, PoseStack s, MultiBufferSource b, int l) {}
        @Override public ResourceLocation getTextureLocation(Entity e) { return TEX; }
    }
}
