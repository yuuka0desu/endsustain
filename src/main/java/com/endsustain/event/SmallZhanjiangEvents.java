package com.endsustain.event;

import com.endsustain.EndSustain;
import com.endsustain.effect.ModEffects;
import com.endsustain.entity.ModEntities;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import com.endsustain.item.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SmallZhanjiangEvents {
    private static final UUID CURIO_SLOT = UUID.fromString("0f6e1d13-61e6-473c-94ce-d25db9d62491");
    private static final UUID BACK_SLOT = UUID.fromString("2ab076b4-2a2c-4351-a9bd-80528c561625");
    private static final UUID BELT_SLOT = UUID.fromString("f174b985-d31a-47e0-bbab-d1d710794de5");
    private static final String ACTIVE = "SmallZhanjiangActive";
    private static final String COMPANION = "SmallZhanjiangCompanion";
    private static final String FIRST_SPAWN_GIFT = "EndsustainReceivedFirstSmallZhanjiang";
    private static final String FIRST_SPAWN_GIFT_VERSION = "EndsustainFirstSmallZhanjiangGiftVersion";
    private static final int CURRENT_GIFT_VERSION = 2;
    private static final Set<UUID> INITIALIZED_SESSIONS = new HashSet<>();

    private SmallZhanjiangEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide
                || !(event.player instanceof ServerPlayer player)) return;
        grantFirstSpawnGift(player);
        boolean equipped = CuriosApi.getCuriosInventory(player)
                .map(handler -> handler.findFirstCurio(ModItems.SMALL_ZHANJIANG.get()).isPresent()).orElse(false);
        boolean firstTickThisSession = INITIALIZED_SESSIONS.add(player.getUUID());
        boolean wasActive = player.getPersistentData().getBoolean(ACTIVE);
        // 瞬时栏位修饰符不会跨登录保留；每次新会话必须重建一次，不能只依赖持久化 ACTIVE。
        if (firstTickThisSession) {
            setExtraSlots(player, equipped);
            wasActive = equipped;
        } else {
            if (equipped && !wasActive) setExtraSlots(player, true);
            if (!equipped && wasActive) setExtraSlots(player, false);
        }
        player.getPersistentData().putBoolean(ACTIVE, equipped);
        if (!equipped) { removeCompanion(player); return; }

        ensureCompanion(player);
        if (player.tickCount % 20 == 0) com.endsustain.progress.FinalePathProgress.scanInventory(player);
    }

    private static void setExtraSlots(ServerPlayer player, boolean add) {
        CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
            if (add) {
                handler.addTransientSlotModifier("curio", CURIO_SLOT, "小蘸酱额外栏位", 1.0D, AttributeModifier.Operation.ADDITION);
                handler.addTransientSlotModifier("back", BACK_SLOT, "小蘸酱额外栏位", 1.0D, AttributeModifier.Operation.ADDITION);
                handler.addTransientSlotModifier("belt", BELT_SLOT, "小蘸酱额外栏位", 1.0D, AttributeModifier.Operation.ADDITION);
            } else {
                handler.removeSlotModifier("curio", CURIO_SLOT);
                handler.removeSlotModifier("back", BACK_SLOT);
                handler.removeSlotModifier("belt", BELT_SLOT);
            }
        });
    }

    private static void ensureCompanion(ServerPlayer player) {
        SmallZhanjiangCompanionEntity companion = null;
        if (player.getPersistentData().hasUUID(COMPANION) && player.level() instanceof ServerLevel level) {
            Entity entity = level.getEntity(player.getPersistentData().getUUID(COMPANION));
            if (entity instanceof SmallZhanjiangCompanionEntity found) companion = found;
        }
        if (companion == null && player.level() instanceof ServerLevel level) {
            companion = new SmallZhanjiangCompanionEntity(ModEntities.SMALL_ZHANJIANG_COMPANION.get(), level);
            companion.setOwner(player); companion.positionAtOwner(player);
            level.addFreshEntity(companion);
            player.getPersistentData().putUUID(COMPANION, companion.getUUID());
        }
    }

    private static void removeCompanion(ServerPlayer player) {
        if (player.getPersistentData().hasUUID(COMPANION) && player.level() instanceof ServerLevel level) {
            Entity entity = level.getEntity(player.getPersistentData().getUUID(COMPANION));
            if (entity != null) entity.discard();
        }
        player.getPersistentData().remove(COMPANION);
    }

    @SubscribeEvent
    public static void onPickup(net.minecraftforge.event.entity.player.EntityItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.endsustain.progress.FinalePathProgress.witness(player, event.getItem().getItem());
        }
    }

    private static void grantFirstSpawnGift(ServerPlayer player) {
        if (player.tickCount < 40
                || player.getPersistentData().getInt(FIRST_SPAWN_GIFT_VERSION) >= CURRENT_GIFT_VERSION) return;
        boolean alreadyOwned = player.getInventory().contains(new ItemStack(ModItems.SMALL_ZHANJIANG.get()))
                || CuriosApi.getCuriosInventory(player)
                .map(handler -> handler.findFirstCurio(ModItems.SMALL_ZHANJIANG.get()).isPresent()).orElse(false);
        if (!alreadyOwned) {
            ItemStack gift = new ItemStack(ModItems.SMALL_ZHANJIANG.get());
            boolean inserted = player.getInventory().add(gift);
            if (!inserted && !gift.isEmpty()) player.drop(gift, false);
            EndSustain.LOGGER.info("首次出生小蘸酱已发放给 {}，放入背包={}",
                    player.getGameProfile().getName(), inserted);
            player.sendSystemMessage(Component.literal("小蘸酱来到了你的身边。"));
        }
        player.getPersistentData().putBoolean(FIRST_SPAWN_GIFT, true);
        player.getPersistentData().putInt(FIRST_SPAWN_GIFT_VERSION, CURRENT_GIFT_VERSION);
        player.inventoryMenu.broadcastChanges();
    }

    @SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        INITIALIZED_SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        INITIALIZED_SESSIONS.remove(player.getUUID());
        var old = event.getOriginal().getPersistentData();
        player.getPersistentData().putBoolean(FIRST_SPAWN_GIFT, old.getBoolean(FIRST_SPAWN_GIFT));
        player.getPersistentData().putInt(FIRST_SPAWN_GIFT_VERSION, old.getInt(FIRST_SPAWN_GIFT_VERSION));
        player.getPersistentData().putInt(com.endsustain.progress.FinalePathProgress.WITNESS,
                old.getInt(com.endsustain.progress.FinalePathProgress.WITNESS));
        player.getPersistentData().putInt(com.endsustain.progress.FinalePathProgress.TIER,
                old.getInt(com.endsustain.progress.FinalePathProgress.TIER));
        for (int i = 0; i < 6; i++) {
            String key = "EndsustainFinaleSkillCooldown" + i;
            player.getPersistentData().putLong(key, old.getLong(key));
        }
        player.getPersistentData().putLong(com.endsustain.progress.FinaleActiveSkills.DODGE_UNTIL,
                old.getLong(com.endsustain.progress.FinaleActiveSkills.DODGE_UNTIL));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void modifyDamage(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim) {
            float reduction = com.endsustain.progress.FinalePathProgress.reductionForTier(
                    victim.getPersistentData().getInt(com.endsustain.progress.FinalePathProgress.TIER));
            if (reduction > 0) event.setAmount(event.getAmount() * (1.0F - reduction));
        }
        var protection = event.getEntity().getEffect(ModEffects.FINALE_PROTECTION.get());
        if (protection != null) {
            float reduction = Math.min(0.10F * (protection.getAmplifier() + 1), 0.60F);
            event.setAmount(event.getAmount() * (1.0F - reduction));
        }
        Entity source = event.getSource().getEntity();
        if (source instanceof Player player) {
            var blessing = player.getEffect(ModEffects.FINALE_BLESSING.get());
            if (blessing != null) {
                float bonus = Math.min(0.10F * (blessing.getAmplifier() + 1), 1.20F);
                event.setAmount(event.getAmount() * (1.0F + bonus));
            }
        }
    }
}
