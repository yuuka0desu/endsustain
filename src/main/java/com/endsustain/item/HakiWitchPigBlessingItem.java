package com.endsustain.item;

import com.endsustain.EndSustain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class HakiWitchPigBlessingItem extends Item {
    private static final int LUCK_LEVEL = 20;
    private static final ResourceKey<DamageType> HAKI_BREATH_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            new ResourceLocation(EndSustain.MOD_ID, "haki_breath")
    );

    public HakiWitchPigBlessingItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player.getRandom().nextBoolean()) {
            DamageSource source = new DamageSource(
                    level.registryAccess()
                            .registryOrThrow(Registries.DAMAGE_TYPE)
                            .getHolderOrThrow(HAKI_BREATH_DAMAGE)
            );
            player.setInvulnerable(false);
            player.invulnerableTime = 0;
            player.hurt(source, (float) Integer.MAX_VALUE);
            if (player.isAlive()) {
                player.setHealth(0.0F);
                player.die(source);
            }
            player.invulnerableTime = 0;
        } else {
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 20 * 20, LUCK_LEVEL - 1, false, true, true));
        }

        if (player instanceof ServerPlayer serverPlayer && !serverPlayer.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
