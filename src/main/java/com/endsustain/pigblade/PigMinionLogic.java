package com.endsustain.pigblade;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 你已猪咪 —— Boss 分支：僵尸猪人仆从（Goety zpiglin_servant）。
 * - 命中 Boss 生成 3 个友方僵尸猪人仆从（攻击 Boss）；
 * - 原始仆从攻击到的生命上限 ≤40 的生物会被转化为友方仆从（每个原始仆从最多转化 1 个）；
 * - 场上最多 10 个此类仆从；仆从持续 1 分钟；
 * - 仆从存活期间，你已猪咪再次命中 Boss 无法召唤。
 */
public final class PigMinionLogic {
    public static final ResourceLocation GOETY_ZPIGLIN =
            new ResourceLocation("goety", "zpiglin_servant");
    public static final String MINION_KEY = "endsustain_pig_minion";
    public static final String ORIGINAL_KEY = "EndsustainPigMinionOriginal";
    public static final String CONVERTS_KEY = "EndsustainPigMinionConvertsLeft";
    public static final String EXPIRE_KEY = "EndsustainPigMinionExpireAt";
    public static final String OWNER_KEY = "EndsustainPigMinionOwner";
    public static final long LIFETIME_TICKS = 1200L; // 1 分钟
    public static final int MAX_MINIONS = 10;
    public static final float CONVERT_MAX_HEALTH = 40.0F;

    /** 仆从登记表：维度 → 存活仆从实体 ID 集合（避免全实体遍历计数）。 */
    private static final Map<ServerLevel, Set<Integer>> MINION_REGISTRY = new ConcurrentHashMap<>();

    /** Goety 反射方法缓存（一次解析，后续复用；解析失败置 null 不再重试）。 */
    private static volatile Method setTrueOwnerMethod;
    private static volatile Method setOwnerIdMethod;
    private static volatile Method setHasLifespanMethod;
    private static volatile Method setLifespanMethod;
    private static volatile Method overrideSetTargetMethod;
    private static volatile boolean reflectionResolved;
    private static volatile boolean warnedOnce;

    private PigMinionLogic() {
    }

    /** 命中 Boss：生成 3 个友方僵尸猪人仆从。已有仆从存活或达到上限时不召唤。 */
    public static void summon(LivingEntity boss, Entity attacker) {
        if (!(boss.level() instanceof ServerLevel level) || boss.level().isClientSide()) return;
        int existing = countMinions(level);
        if (existing > 0) {
            com.endsustain.EndSustain.LOGGER.debug("[猪咪仆从] 场上已有 {} 个仆从存活，跳过召唤", existing);
            return;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(GOETY_ZPIGLIN);
        if (type == null) {
            com.endsustain.EndSustain.LOGGER.error("[猪咪仆从] 未找到 goety:zpiglin_servant 实体类型");
            return;
        }

        int spawned = 0;
        for (int i = 0; i < 3; i++) {
            Entity created = type.create(level);
            if (!(created instanceof Mob minion)) {
                com.endsustain.EndSustain.LOGGER.warn("[猪咪仆从] 实体创建失败或类型不符：{}", created);
                continue;
            }
            double angle = Math.PI * 2.0D * i / 3.0D;
            minion.setPos(boss.getX() + Math.cos(angle) * 1.6D, boss.getY(),
                    boss.getZ() + Math.sin(angle) * 1.6D);
            minion.setCustomName(net.minecraft.network.chat.Component.literal("猪咪仆从"));
            minion.setCustomNameVisible(true);
            applyMinionStats(minion);
            bindGoetyOwner(minion, boss, attacker);
            CompoundTag data = minion.getPersistentData();
            data.putBoolean(MINION_KEY, true);
            data.putBoolean(ORIGINAL_KEY, true);
            data.putInt(CONVERTS_KEY, 1);
            data.putLong(EXPIRE_KEY, level.getGameTime() + LIFETIME_TICKS);
            if (attacker != null) {
                data.putUUID(OWNER_KEY, attacker.getUUID());
            }
            minion.setTarget(boss);
            boolean added = level.addFreshEntity(minion);
            if (added) {
                spawned++;
                registerMinion(level, minion);
                level.sendParticles(ParticleTypes.WITCH, minion.getX(), minion.getY() + 1.0D, minion.getZ(),
                        12, 0.3D, 0.5D, 0.3D, 0.08D);
            }
        }
        com.endsustain.EndSustain.LOGGER.debug("[猪咪仆从] 召唤完成：{} / 3，Boss={}", spawned, boss.getName().getString());
        if (spawned > 0) {
            level.playSound(null, boss.blockPosition(), SoundEvents.PIG_AMBIENT,
                    SoundSource.HOSTILE, 1.2F, 1.0F);
        }
    }

    /** 通过反射绑定 Goety 仆从的主人 / 1 分钟寿命 / 攻击目标（Method 对象缓存）。 */
    private static void bindGoetyOwner(Mob minion, LivingEntity boss, Entity attacker) {
        resolveReflectionMethods();
        if (attacker instanceof LivingEntity owner) {
            if (setTrueOwnerMethod != null) {
                try {
                    setTrueOwnerMethod.invoke(minion, owner);
                } catch (Throwable t) {
                    warnReflection("setTrueOwner", t);
                }
            }
            if (setOwnerIdMethod != null) {
                try {
                    setOwnerIdMethod.invoke(minion, owner.getUUID());
                } catch (Throwable t) {
                    warnReflection("setOwnerId", t);
                }
            }
        }
        // Goety 寿命机制：1 分钟
        if (setHasLifespanMethod != null) {
            try {
                setHasLifespanMethod.invoke(minion, true);
            } catch (Throwable t) {
                warnReflection("setHasLifespan", t);
            }
        }
        if (setLifespanMethod != null) {
            try {
                setLifespanMethod.invoke(minion, (int) LIFETIME_TICKS);
            } catch (Throwable t) {
                warnReflection("setLifespan", t);
            }
        }
        if (overrideSetTargetMethod != null) {
            try {
                overrideSetTargetMethod.invoke(minion, boss);
            } catch (Throwable t) {
                warnReflection("overrideSetTarget", t);
            }
        }
    }

    private static void resolveReflectionMethods() {
        if (reflectionResolved) return;
        synchronized (PigMinionLogic.class) {
            if (reflectionResolved) return;
            try {
                Class<?> base = Class.forName("com.Polarice3.Goety.common.entities.neutral.ZPiglinServant");
                setTrueOwnerMethod = base.getMethod("setTrueOwner", LivingEntity.class);
                setOwnerIdMethod = base.getMethod("setOwnerId", UUID.class);
                setHasLifespanMethod = base.getMethod("setHasLifespan", boolean.class);
                setLifespanMethod = base.getMethod("setLifespan", int.class);
                overrideSetTargetMethod = base.getMethod("overrideSetTarget", LivingEntity.class);
            } catch (Throwable t) {
                warnReflection("resolve", t);
            }
            reflectionResolved = true;
        }
    }

    private static void warnReflection(String method, Throwable t) {
        if (!warnedOnce) {
            warnedOnce = true;
            com.endsustain.EndSustain.LOGGER.warn("[猪咪仆从] Goety 反射调用 {} 失败：{}", method, t.toString());
        }
    }

    /**
     * 原始仆从攻击命中时尝试转化目标：
     * 目标生命上限 ≤40 且合法时，将其转化为友方僵尸猪人仆从。
     */
    public static void tryConvert(LivingEntity attackerMinion, LivingEntity victim) {
        if (!(victim.level() instanceof ServerLevel level) || victim.level().isClientSide()) return;
        if (!isOriginalMinion(attackerMinion)) return;
        if (!isValidConvertTarget(victim)) return;
        if (countMinions(level) >= MAX_MINIONS) return;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(GOETY_ZPIGLIN);
        if (type == null) return;

        Entity created = type.create(level);
        if (!(created instanceof Mob minion)) return;
        minion.setPos(victim.getX(), victim.getY(), victim.getZ());
        minion.setCustomName(net.minecraft.network.chat.Component.literal("猪咪仆从"));
        minion.setCustomNameVisible(true);
        applyMinionStats(minion);
        CompoundTag data = minion.getPersistentData();
        data.putBoolean(MINION_KEY, true);
        data.putBoolean(ORIGINAL_KEY, false);
        data.putInt(CONVERTS_KEY, 0);
        data.putLong(EXPIRE_KEY, attackerMinion.getPersistentData().getLong(EXPIRE_KEY));
        UUID owner = attackerMinion.getPersistentData().hasUUID(OWNER_KEY)
                ? attackerMinion.getPersistentData().getUUID(OWNER_KEY) : null;
        if (owner != null) {
            data.putUUID(OWNER_KEY, owner);
        }
        LivingEntity ownerEntity = null;
        if (owner != null && level.getEntity(owner) instanceof LivingEntity living) {
            ownerEntity = living;
        }
        LivingEntity target = attackerMinion instanceof Mob am ? am.getTarget() : null;
        bindGoetyOwner(minion, target != null ? target : minion, ownerEntity);
        boolean added = level.addFreshEntity(minion);
        if (!added) return;
        registerMinion(level, minion);

        // 每个原始仆从最多转化 1 个
        attackerMinion.getPersistentData().putInt(CONVERTS_KEY, 0);
        victim.remove(Entity.RemovalReason.DISCARDED);
        level.sendParticles(ParticleTypes.WITCH, victim.getX(), victim.getY() + 1.0D, victim.getZ(),
                12, 0.3D, 0.5D, 0.3D, 0.08D);
    }

    /** 在 LivingTickEvent 中调用：到期移除，并维持对 Boss 的攻击目标。 */
    public static void tickMinion(LivingEntity minion) {
        CompoundTag data = minion.getPersistentData();
        if (!data.getBoolean(MINION_KEY)) return;
        long expire = data.getLong(EXPIRE_KEY);
        if (expire != 0L && minion.level().getGameTime() >= expire) {
            unregisterMinion(minion);
            minion.remove(Entity.RemovalReason.DISCARDED);
            return;
        }
        if (minion instanceof Mob mob) {
            LivingEntity target = mob.getTarget();
            if (target == null || !target.isAlive() || target.isRemoved()) {
                LivingEntity next = findNearbyEnemy(minion);
                if (next != null) {
                    mob.setTarget(next);
                }
            }
        }
    }

    private static LivingEntity findNearbyEnemy(LivingEntity minion) {
        List<Monster> monsters = minion.level().getEntitiesOfClass(Monster.class,
                minion.getBoundingBox().inflate(16.0D),
                m -> m.isAlive() && !m.getPersistentData().getBoolean(MINION_KEY));
        return monsters.stream()
                .min(java.util.Comparator.comparingDouble(minion::distanceToSqr)).orElse(null);
    }

    private static void applyMinionStats(Mob minion) {
        var maxHp = minion.getAttribute(Attributes.MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(40.0D);
        }
        var attack = minion.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(20.0D); // 攻击力提升到原来的 2 倍
        }
        minion.setHealth(minion.getMaxHealth());
    }

    public static boolean isMinion(LivingEntity entity) {
        return entity.getPersistentData().getBoolean(MINION_KEY);
    }

    public static boolean isOriginalMinion(LivingEntity entity) {
        return isMinion(entity) && entity.getPersistentData().getBoolean(ORIGINAL_KEY);
    }

    private static boolean isValidConvertTarget(LivingEntity victim) {
        if (victim instanceof Player
                || victim instanceof PigFormPig
                || isMinion(victim)
                || PigFormLogic.isBossLike(victim)) {
            return false;
        }
        return victim.getMaxHealth() <= CONVERT_MAX_HEALTH && victim.isAlive();
    }

    private static int countMinions(ServerLevel level) {
        Set<Integer> ids = MINION_REGISTRY.get(level);
        if (ids == null || ids.isEmpty()) return 0;
        // 清理已不存在的实体 ID（防御性）
        ids.removeIf(id -> !(level.getEntity(id) instanceof LivingEntity living)
                || !living.isAlive()
                || !living.getPersistentData().getBoolean(MINION_KEY));
        if (ids.isEmpty()) {
            MINION_REGISTRY.remove(level);
        }
        return ids.size();
    }

    private static void registerMinion(ServerLevel level, LivingEntity minion) {
        MINION_REGISTRY.computeIfAbsent(level, k ->
                java.util.concurrent.ConcurrentHashMap.newKeySet()).add(minion.getId());
    }

    static void unregisterMinion(LivingEntity minion) {
        if (minion.level() instanceof ServerLevel level) {
            Set<Integer> ids = MINION_REGISTRY.get(level);
            if (ids != null) {
                ids.remove(minion.getId());
                if (ids.isEmpty()) {
                    MINION_REGISTRY.remove(level);
                }
            }
        }
    }

    /** 服务器停止时清理登记表，防止跨存档残留。 */
    public static void clear() {
        MINION_REGISTRY.clear();
        warnedOnce = false;
    }
}
