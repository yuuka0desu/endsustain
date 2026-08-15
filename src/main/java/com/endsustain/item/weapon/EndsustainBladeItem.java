package com.endsustain.item.weapon;

import com.endsustain.entity.boss.EndsustainBladeEntity;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

public class EndsustainBladeItem extends TridentItem {
    public static final String THROW_TAG = "endsustain_blade_throw";
    public static final String STACK_DATA_KEY = "EndsustainBladeStack";
    private static final String PLAY_MINUTES_KEY = "EndsustainPlayMinutes";
    private static final long DAMAGE_PER_MINUTE = 745_700L;

    public EndsustainBladeItem(Properties properties) {
        super(properties);
    }

    public static int getPlayTimeMinutes(ServerPlayer player) {
        int ticks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        return Math.max(0, ticks / (20 * 60));
    }

    public static long getBladeDamageLong(ServerPlayer player) {
        return DAMAGE_PER_MINUTE * getPlayTimeMinutes(player);
    }

    public static boolean hasOverflowDamage(ServerPlayer player) {
        return getBladeDamageLong(player) > Integer.MAX_VALUE;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            int minutes = getPlayTimeMinutes(player);
            CompoundTag tag = stack.getOrCreateTag();
            if (tag.getInt(PLAY_MINUTES_KEY) != minutes) {
                tag.putInt(PLAY_MINUTES_KEY, minutes);
            }
        }
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot, ItemStack stack) {
        if (slot != EquipmentSlot.MAINHAND) return super.getAttributeModifiers(slot, stack);
        int minutes = stack.getOrCreateTag().getInt(PLAY_MINUTES_KEY);
        double whiteDamage = (double) DAMAGE_PER_MINUTE * minutes;
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_UUID,
                "Tool modifier", whiteDamage - 1.0D, AttributeModifier.Operation.ADDITION));
        builder.put(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_UUID,
                "Tool modifier", -2.9D, AttributeModifier.Operation.ADDITION));
        return builder.build();
    }

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
        return super.canApplyAtEnchantingTable(stack, enchantment)
                || enchantment.canApplyAtEnchantingTable(new ItemStack(Items.NETHERITE_SWORD));
    }

    @Override
    public int getEnchantmentValue(ItemStack stack) {
        return 22;
    }

    @Override
    public void onUseTick(Level level, LivingEntity living, ItemStack stack, int remainingUseDuration) {
        super.onUseTick(level, living, stack, remainingUseDuration);
        if (!(level instanceof ServerLevel server) || remainingUseDuration % 2 != 0) return;
        Vec3 look = living.getLookAngle();
        Vec3 origin = living.getEyePosition().add(look.scale(0.65D));
        server.sendParticles(ParticleTypes.PORTAL,
                origin.x, origin.y - 0.2D, origin.z,
                4, 0.22D, 0.22D, 0.22D, 0.08D);
        if (remainingUseDuration % 6 == 0) {
            server.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    origin.x, origin.y - 0.2D, origin.z,
                    2, 0.12D, 0.12D, 0.12D, 0.02D);
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        if (!(living instanceof Player player)) return;
        int charge = this.getUseDuration(stack) - timeLeft;
        if (charge < 10) return;

        int riptide = EnchantmentHelper.getRiptide(stack);
        if (riptide > 0) {
            super.releaseUsing(stack, level, living, timeLeft);
            return;
        }

        if (!level.isClientSide) {
            stack.hurtAndBreak(1, player, user -> user.broadcastBreakEvent(living.getUsedItemHand()));
            if (stack.isEmpty()) return;

            ItemStack thrownStack = stack.copy();
            Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(thrownStack);
            enchantments.put(Enchantments.LOYALTY, Math.max(3, enchantments.getOrDefault(Enchantments.LOYALTY, 0)));
            EnchantmentHelper.setEnchantments(enchantments, thrownStack);

            ItemStack carriedStack = player.getAbilities().instabuild ? ItemStack.EMPTY : thrownStack;
            EndsustainBladeEntity blade = new EndsustainBladeEntity(level, player, carriedStack);
            blade.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 2.8F, 0.4F);
            blade.addTag(THROW_TAG);
            blade.getPersistentData().put(STACK_DATA_KEY, thrownStack.save(new CompoundTag()));
            if (!level.addFreshEntity(blade)) {
                return;
            }
            level.playSound(null, blade, SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.0F);
            if (!player.getAbilities().instabuild) {
                player.getInventory().removeItem(stack);
            }
        }
        player.awardStat(Stats.ITEM_USED.get(this));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        int minutes = stack.getOrCreateTag().getInt(PLAY_MINUTES_KEY);
        long damage = DAMAGE_PER_MINUTE * minutes;
        tooltip.add(Component.literal("当前基础白值：" + damage));
        tooltip.add(Component.literal("伤害 = 745700 × 游戏时长（分钟）"));
        if (damage > Integer.MAX_VALUE) {
            tooltip.add(Component.literal("已突破 32 位上限：命中将触发终焉强杀"));
        }
        tooltip.add(Component.literal("兼容剑与三叉戟附魔 · 自带忠诚 III"));
    }
}
