package com.endsustain.pigblade;

import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.endsustain.entity.boss.QunUEntity;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import com.endsustain.pigblade.registry.PigBladeRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 「你已猪咪」猪化逻辑：
 * - 命中敌人 → 原实体 NBT 快照 → 在原位置生成猪形态替身（继承血量/护甲/抗性），原实体移除；
 * - 猪形态持续 5 秒：超时还原并受「原伤害 + 3% 最大生命值」伤害；
 * - 猪形态被击杀：强制还原原形态并受「原伤害 + 10% 最大生命值」伤害。
 */
public final class PigFormLogic {
    public static final String PIG_FORM_TAG = "endsustain_pigform";
    public static final long PIG_FORM_DURATION_TICKS = 100L; // 5 秒

    private PigFormLogic() {
    }

    /** 将目标替换为猪形态替身，成功返回猪实体，失败返回 null。 */
    public static PigFormPig transform(LivingEntity target, LivingEntity attacker, float originalDamage) {
        if (target.level().isClientSide()) {
            com.endsustain.EndSustain.LOGGER.debug("[猪化] 客户端调用被忽略：{}", target.getName().getString());
            return null;
        }
        if (!isValidTarget(target)) {
            com.endsustain.EndSustain.LOGGER.debug("[猪化] 目标不合法：{}，类别={}",
                    target.getName().getString(), target.getType().getCategory());
            return null;
        }
        try {
            ServerLevel level = (ServerLevel) target.level();
            CompoundTag snapshot = target.serializeNBT();
            float health = target.getHealth();
            float maxHealth = target.getMaxHealth();

            PigFormPig pig = new PigFormPig(PigBladeRegistry.PIG_FORM.get(), level);
            pig.moveTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
            pig.setCustomName(Component.literal("猪咪·" + target.getName().getString()));
            pig.setCustomNameVisible(true);
            pig.addTag(PIG_FORM_TAG);

            // 只继承血量：护甲、护甲韧性与击退抗性均不再继承（猪化后为裸身，抗性 -50% 见 PigBladeEvents.onLivingHurt）
            setBase(pig, Attributes.MAX_HEALTH, Math.max(1.0D, maxHealth));
            pig.setHealth(Math.min(health, pig.getMaxHealth()));

            CompoundTag data = pig.getPersistentData();
            data.put("EndsustainPigFormSnapshot", snapshot);
            data.putFloat("EndsustainPigFormDamage", originalDamage);
            data.putFloat("EndsustainPigFormMaxHealth", maxHealth);
            data.putLong("EndsustainPigFormExpireAt", level.getGameTime() + PIG_FORM_DURATION_TICKS);
            if (attacker != null) {
                data.putUUID("EndsustainPigFormAttacker", attacker.getUUID());
            }

            level.addFreshEntity(pig);
            target.remove(Entity.RemovalReason.DISCARDED);
            com.endsustain.EndSustain.LOGGER.debug("[猪化] 成功：{} → 猪咪，血量={}/{}",
                    target.getName().getString(), health, maxHealth);
            return pig;
        } catch (Throwable exception) {
            com.endsustain.EndSustain.LOGGER.error("[猪化] 流程异常", exception);
            return null;
        }
    }

    /**
     * 还原原形态并结算惩罚伤害。
     *
     * @param timeout true=超时还原（3% 最大生命值惩罚）；false=猪形态被击杀（10% 最大生命值惩罚）
     */
    public static void revert(PigFormPig pig, boolean timeout) {
        if (pig.level().isClientSide() || pig.isRemoved()) return;
        ServerLevel level = (ServerLevel) pig.level();
        CompoundTag data = pig.getPersistentData();
        CompoundTag snapshot = data.getCompound("EndsustainPigFormSnapshot");
        float originalDamage = data.getFloat("EndsustainPigFormDamage");
        float maxHealth = data.getFloat("EndsustainPigFormMaxHealth");
        float penaltyPercent = timeout ? 0.03F : 0.10F;

        pig.remove(Entity.RemovalReason.KILLED);

        EntityType<?> type = EntityType.byString(snapshot.getString("id")).orElse(null);
        if (type == null || !(type.create(level) instanceof LivingEntity restored)) return;

        restored.deserializeNBT(snapshot);
        restored.moveTo(pig.getX(), pig.getY(), pig.getZ(), pig.getYRot(), pig.getXRot());
        if (restored.getHealth() > restored.getMaxHealth()) {
            restored.setHealth(restored.getMaxHealth());
        }
        level.addFreshEntity(restored);

        LivingEntity attacker = null;
        if (data.hasUUID("EndsustainPigFormAttacker")
                && level.getEntity(data.getUUID("EndsustainPigFormAttacker")) instanceof LivingEntity living) {
            attacker = living;
        }
        DamageSource source = attacker instanceof ServerPlayer player
                ? level.damageSources().playerAttack(player)
                : level.damageSources().mobAttack(attacker != null ? attacker : restored);
        // 强制变回原形态并受到原伤害 + 最大生命值 X% 的伤害
        restored.invulnerableTime = 0;
        restored.hurt(source, Math.max(1.0F, originalDamage) + maxHealth * penaltyPercent);
    }

    private static void setBase(LivingEntity entity, net.minecraft.world.entity.ai.attributes.Attribute attribute,
                                double value) {
        var instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** 只对普通怪物生效：排除玩家、Boss、猪形态与关键实体。 */
    public static boolean isValidTarget(LivingEntity target) {
        if (target instanceof Player
                || target instanceof Pig
                || target instanceof WitherBoss
                || target instanceof EnderDragon
                || target instanceof FinaleEndsustainEntity
                || target instanceof QunUEntity
                || target instanceof SmallZhanjiangCompanionEntity) {
            return false;
        }
        if (target.getPersistentData().contains("EndsustainPigFormSnapshot")) return false;
        return target.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
                || target.getType().getCategory() == net.minecraft.world.entity.MobCategory.CREATURE;
    }

    /** Boss 判定：龙/凋灵/蘸酱 Boss，或生命值 ≥200 的怪物。 */
    public static boolean isBossLike(LivingEntity target) {
        return target instanceof WitherBoss
                || target instanceof EnderDragon
                || target instanceof FinaleEndsustainEntity
                || (target instanceof net.minecraft.world.entity.Mob && target.getMaxHealth() >= 200.0F);
    }
}
