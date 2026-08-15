package com.endsustain.progress;

import com.endsustain.compat.ftbquests.FtbQuestsCompat;
import com.endsustain.item.ModItems;
import com.google.common.collect.ImmutableList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.List;
import java.util.UUID;

public final class FinalePathProgress {
    public static final String WITNESS = "EndsustainFinaleDropWitnessMask";
    public static final String TIER = "EndsustainFinaleSkillTier";
    private static final String SKILL_LAYOUT_VERSION = "EndsustainFinaleSkillLayoutVersion";
    public static final String[] STORY_QUESTS = {
            "", "5DD1DFBC650A5E8D", "2E234A7111AC8092", "325A12D3C0C4C071",
            "315964E756BC5D6D", "13326026494399C4", "26E61D8A06FAB278", "4677ACC569C2F349"
    };
    // 按实际挑战顺序：虚空之花、暗夜巫妖、先驱者、利维坦、焰魔、亚波伦。
    public static final String[] SKILL_QUESTS = {
            "549BE17C23DE035D", "51EB663D84DB73BF", "208360F9CF4FF6FD",
            "45A8FD4F56E42D00", "30EE9B04F1837DA3", "13326026494399C4"
    };
    public static final ResourceLocation[] SKILL_DROPS = {
            id("bosses_of_mass_destruction:void_thorn"), id("bosses_of_mass_destruction:ancient_anima"),
            id("cataclysm:witherite_block"), id("cataclysm:tidal_claws"), id("cataclysm:ignitium_ingot"),
            id("goety_revelation:ascension_halo")
    };
    private static final UUID HEALTH = UUID.fromString("fa3bb19a-856e-48d0-a20f-adc64d71cab1");
    private static final UUID DAMAGE = UUID.fromString("f5a9cb42-709e-4cd6-b70c-f82be3dfd9a0");
    private static final UUID ARMOR = UUID.fromString("168ae05b-a259-4ff6-830f-c6fe2f4b1334");
    private static final UUID TOUGHNESS = UUID.fromString("87686064-b5f6-4bde-833e-ce645e283682");
    private static final UUID SPEED = UUID.fromString("7e99c49c-209d-4e63-8280-ef0270eae6ee");

    private FinalePathProgress() {}
    private static ResourceLocation id(String value) { return new ResourceLocation(value); }

    public static boolean isWearingSmallZhanjiang(ServerPlayer player) {
        boolean equipped = CuriosApi.getCuriosInventory(player)
                .map(h -> h.findFirstCurio(ModItems.SMALL_ZHANJIANG.get()).isPresent()).orElse(false);
        // Curios 在登录重建栏位的短窗口内可能暂时查询不到物品，使用上一服务端 tick 的状态兜底。
        return equipped || player.getPersistentData().getBoolean("SmallZhanjiangActive");
    }

    public static void witness(ServerPlayer player, ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) return;
        int mask = player.getPersistentData().getInt(WITNESS);
        for (int i = 0; i < SKILL_DROPS.length; i++) if (SKILL_DROPS[i].equals(key)) mask |= 1 << i;
        player.getPersistentData().putInt(WITNESS, mask);
    }

    public static void scanInventory(ServerPlayer player) {
        migrateSkillLayout(player);
        for (ItemStack stack : player.getInventory().items) witness(player, stack);
        for (ItemStack stack : player.getInventory().armor) witness(player, stack);
        for (ItemStack stack : player.getInventory().offhand) witness(player, stack);
        CuriosApi.getCuriosInventory(player).ifPresent(h -> {
            var equipped = h.getEquippedCurios();
            for (int i = 0; i < equipped.getSlots(); i++) witness(player, equipped.getStackInSlot(i));
        });
        reconcileTier(player);
    }

    private static void migrateSkillLayout(ServerPlayer player) {
        if (player.getPersistentData().getInt(SKILL_LAYOUT_VERSION) >= 2) return;
        int oldMask = player.getPersistentData().getInt(WITNESS);
        // 旧顺序：先驱、深渊、余烬、魂火、虚空、天启；新顺序：虚空、魂火、先驱、深渊、余烬、天启。
        int[] oldIndexForNew = {4, 3, 0, 1, 2, 5};
        int newMask = 0;
        for (int newIndex = 0; newIndex < oldIndexForNew.length; newIndex++) {
            if ((oldMask & (1 << oldIndexForNew[newIndex])) != 0) newMask |= 1 << newIndex;
        }
        player.getPersistentData().putInt(WITNESS, newMask);
        player.getPersistentData().putInt(SKILL_LAYOUT_VERSION, 2);
    }

    public static void reconcileTier(ServerPlayer player) {
        int witness = player.getPersistentData().getInt(WITNESS);
        int tier = 0;
        // 技能同样按阶位连续成长，不能因高阶 Boss 支线已完成而跳过前置阶位。
        for (int i = 0; i < 6; i++) {
            if ((witness & (1 << i)) != 0 && FtbQuestsCompat.isQuestCompleted(player, SKILL_QUESTS[i])) tier = i + 1;
            else break;
        }
        if (tier != player.getPersistentData().getInt(TIER)) player.getPersistentData().putInt(TIER, tier);
        applyTierAttributes(player, tier);
    }

    public static int storyMask(ServerPlayer player) {
        // 故事线必须严格按幕推进：前一幕未解锁时，后续即使支线 Quest 已完成也不提前显示。
        int mask = 1;
        for (int i = 1; i < 8; i++) {
            if ((mask & (1 << (i - 1))) == 0) break;
            if (!FtbQuestsCompat.isQuestCompleted(player, STORY_QUESTS[i])) break;
            mask |= 1 << i;
        }
        if ((mask & (1 << 7)) != 0 && FinaleMilestones.has(player, FinaleMilestones.RITUAL_STARTED)) mask |= 1 << 8;
        if ((mask & (1 << 8)) != 0 && FinaleMilestones.has(player, FinaleMilestones.BATTLE_STARTED)) mask |= 1 << 9;
        if ((mask & (1 << 9)) != 0 && FinaleMilestones.has(player, FinaleMilestones.DEFEATED)) mask |= 1 << 10;
        return mask;
    }

    private static void applyTierAttributes(ServerPlayer p, int tier) {
        remove(p, Attributes.MAX_HEALTH, HEALTH); remove(p, Attributes.ATTACK_DAMAGE, DAMAGE);
        remove(p, Attributes.ARMOR, ARMOR); remove(p, Attributes.ARMOR_TOUGHNESS, TOUGHNESS);
        remove(p, Attributes.MOVEMENT_SPEED, SPEED);
        double[] hp = {0,.10,.15,.20,.25,.30,.40}, dmg = {0,.05,.10,.15,.20,.25,.35}, speed = {0,0,.05,0,0,0,.10};
        double[] armor = {0,0,0,4,0,0,0}, tough = {0,0,0,0,0,4,0};
        if (tier > 0) {
            add(p, Attributes.MAX_HEALTH, HEALTH, "终末之路生命", hp[tier], AttributeModifier.Operation.MULTIPLY_TOTAL);
            add(p, Attributes.ATTACK_DAMAGE, DAMAGE, "终末之路攻击", dmg[tier], AttributeModifier.Operation.MULTIPLY_TOTAL);
            if (armor[tier] != 0) add(p, Attributes.ARMOR, ARMOR, "终末之路护甲", armor[tier], AttributeModifier.Operation.ADDITION);
            if (tough[tier] != 0) add(p, Attributes.ARMOR_TOUGHNESS, TOUGHNESS, "终末之路韧性", tough[tier], AttributeModifier.Operation.ADDITION);
            if (speed[tier] != 0) add(p, Attributes.MOVEMENT_SPEED, SPEED, "终末之路速度", speed[tier], AttributeModifier.Operation.MULTIPLY_TOTAL);
        }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static void remove(ServerPlayer p, Attribute a, UUID id) { AttributeInstance i = p.getAttribute(a); if (i != null) i.removeModifier(id); }
    private static void add(ServerPlayer p, Attribute a, UUID id, String name, double value, AttributeModifier.Operation op) {
        AttributeInstance i = p.getAttribute(a); if (i != null) i.addTransientModifier(new AttributeModifier(id, name, value, op));
    }

    public static float reductionForTier(int tier) { return tier >= 6 ? .15F : tier == 5 ? .08F : tier == 4 ? .05F : 0F; }
    public static float projectileBonusForTier(int tier) { return new float[]{0,.05F,.10F,.15F,.20F,.25F,.35F}[Math.max(0, Math.min(6, tier))]; }
}
