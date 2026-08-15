package com.endsustain.item;

import com.endsustain.entity.projectile.IlusiAteProjectileEntity;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class IlusiAteItem extends Item {
    private final Multimap<Attribute, AttributeModifier> mainHandModifiers;

    public IlusiAteItem(Properties properties) {
        super(properties);
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_UUID,
                "Tool modifier", 30.0D, AttributeModifier.Operation.ADDITION));
        builder.put(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_UUID,
                "Tool modifier", 3.0D, AttributeModifier.Operation.ADDITION));
        this.mainHandModifiers = builder.build();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.25F);
        if (!level.isClientSide) {
            IlusiAteProjectileEntity projectile = new IlusiAteProjectileEntity(level, player);
            Vec3 look = player.getLookAngle();
            projectile.setDeltaMovement(look.scale(1.6D).add(0.0D, 0.12D, 0.0D));
            level.addFreshEntity(projectile);
        }
        player.getCooldowns().addCooldown(this, 20);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? this.mainHandModifiers : super.getDefaultAttributeModifiers(slot);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("！？白色系外冷内齁杂鱼傲娇雌小鬼小男娘？！"));
        tooltip.add(Component.literal("使用行为：投掷后在落点召唤三个自动追踪敌对生物的三叉戟"));
    }
}
