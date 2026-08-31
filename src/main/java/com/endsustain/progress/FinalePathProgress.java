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
    public static final String STORY_WITNESS = "EndsustainFinaleStoryWitnessMask";
    private static final String SKILL_LAYOUT_VERSION = "EndsustainFinaleSkillLayoutVersion";
    public static final String[] STORY_QUESTS = {
            "", "5DD1DFBC650A5E8D", "2E234A7111AC8092", "325A12D3C0C4C071",
            "315964E756BC5D6D", "13326026494399C4", "26E61D8A06FAB278", "4677ACC569C2F349"
    };
    public static final String[] SKILL_QUESTS = {
            "549BE17C23DE035D", "51EB663D84DB73BF", "208360F9CF4FF6FD",
            "45A8FD4F56E42D00", "30EE9B04F1837DA3", "13326026494399C4"
    };
    public static final ResourceLocation[] SKILL_DROPS = {
            id("bosses_of_mass_destruction:void_thorn"), id("bosses_of_mass_destruction:ancient_anima"),
            id("cataclysm:witherite_block"), id("cataclysm:tidal_claws"), id("cataclysm:ignitium_ingot"),
            id("goety_revelation:ascension_halo")
    };
    /** 与 SKILL_DROPS 一一对应的掉落来源实体：击杀即解锁对应等级。 */
    public static final ResourceLocation[] SKILL_BOSSES = {
            id("bosses_of_mass_destruction:void_blossom"), id("bosses_of_mass_destruction:gauntlet"),
            id("cataclysm:netherite_monstrosity"), id("cataclysm:the_leviathan"),
            id("cataclysm:ignis"), id("goety:apostle")
    };
    /**
     * 故事线解锁检测（击杀/事件/物品驱动）：
     * 幕 1 虚空之花；幕 2 暗夜巫妖；幕 3 到达下界（维度见证）；
     * 幕 4 先驱者；幕 5 末影龙；幕 6 末影守卫；幕 7 获得晋升之环；
     * 幕 8 完成落幕仪式；幕 9 加载范围内出现蘸酱 Boss；幕 10 击杀蘸酱 Boss。
     */
    public static final ResourceLocation[] STORY_BOSSES = {
            id("bosses_of_mass_destruction:void_blossom"),   // 幕 1
            id("bosses_of_mass_destruction:lich"),           // 幕 2
            id("cataclysm:the_harbinger"),                    // 幕 4
            id("minecraft:ender_dragon"),                     // 幕 5
            id("cataclysm:ender_guardian")                    // 幕 6
    };
    /** 与 STORY_BOSSES 一一对应的故事幕编号。 */
    public static final int[] STORY_BOSS_NODES = {1, 2, 4, 5, 6};
    /** 第七幕：获得晋升之环（goety_revelation:ascension_halo）。 */
    public static final ResourceLocation ASCENSION_HALO =
            id("goety_revelation:ascension_halo");
    public static final int STORY_HALO_NODE = 7;
    /** 到达下界解锁的幕。 */
    public static final int STORY_NETHER_NODE = 3;
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
        // 故事第七幕：获得晋升之环
        if (ASCENSION_HALO.equals(key)) {
            witnessStory(player, STORY_HALO_NODE);
        }
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
        // 老存档补发：按已解锁的技能见证同步故事见证（击杀开启机制迁移）
        syncStoryFromSkillWitness(player);
    }

    /** 按技能见证位补发故事见证：技能 Boss 已击杀则对应故事幕解锁。 */
    public static void syncStoryFromSkillWitness(ServerPlayer player) {
        int skillWitness = player.getPersistentData().getInt(WITNESS);
        for (int i = 0; i < SKILL_BOSSES.length; i++) {
            if ((skillWitness & (1 << i)) == 0) continue;
            ResourceLocation boss = SKILL_BOSSES[i];
            for (int j = 0; j < STORY_BOSSES.length; j++) {
                if (STORY_BOSSES[j].equals(boss)) {
                    witnessStory(player, STORY_BOSS_NODES[j]);
                    break;
                }
            }
        }
    }

    /** 测试指令：一键解锁全部故事幕与技能阶位。 */
    public static void unlockAll(ServerPlayer player) {
        int mask = (1 << 6) - 1;
        player.getPersistentData().putInt(WITNESS, mask);
        int story = 0;
        for (int node = 1; node <= 7; node++) story |= 1 << node;
        player.getPersistentData().putInt(STORY_WITNESS, story);
        reconcileTier(player);
        FinaleMilestones.award(player, FinaleMilestones.RITUAL_STARTED);
        FinaleMilestones.award(player, FinaleMilestones.BATTLE_STARTED);
        FinaleMilestones.award(player, FinaleMilestones.DEFEATED);
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

    /**
     * 击杀检测：击杀对应实体且上一级已解锁时直接解锁该等级（无需 FTB 任务）。
     * 实体与 SKILL_DROPS 掉落一一对应，由 LivingDeathEvent 触发。
     */
    public static void onBossKilled(ServerPlayer player, ResourceLocation entityTypeId) {
        int tier = player.getPersistentData().getInt(TIER);
        for (int i = 0; i < SKILL_BOSSES.length; i++) {
            if (!SKILL_BOSSES[i].equals(entityTypeId)) continue;
            if (i > 0 && tier < i) return; // 上一级未解锁，不越级
            int mask = player.getPersistentData().getInt(WITNESS);
            if ((mask & (1 << i)) == 0) {
                player.getPersistentData().putInt(WITNESS, mask | (1 << i));
            }
            reconcileTier(player);
            return;
        }
    }

    public static void reconcileTier(ServerPlayer player) {
        int witness = player.getPersistentData().getInt(WITNESS);
        int tier = 0;
        // 技能按阶位连续成长：检测到击杀对应实体即解锁该等级，不能跳过前置阶位。
        for (int i = 0; i < 6; i++) {
            if ((witness & (1 << i)) != 0) tier = i + 1;
            else break;
        }
        if (tier != player.getPersistentData().getInt(TIER)) player.getPersistentData().putInt(TIER, tier);
        applyTierAttributes(player, tier);
    }

    /**
     * 故事事件见证：检测到对应事件（如进入下界/末地）时直接记录该幕。
     * 解锁仍按幕连续推进（storyMask 中合并计算），不依赖 FTB 任务。
     */
    public static void witnessStory(ServerPlayer player, int node) {
        if (node < 0 || node > 7) return;
        int mask = player.getPersistentData().getInt(STORY_WITNESS);
        if ((mask & (1 << node)) == 0) {
            player.getPersistentData().putInt(STORY_WITNESS, mask | (1 << node));
        }
    }

    /**
     * 击杀故事 Boss 时见证对应幕（与终末技能一致，均为实体击杀开启）。
     */
    public static void witnessStoryForBossKill(ServerPlayer player, ResourceLocation entityTypeId) {
        for (int i = 0; i < STORY_BOSSES.length; i++) {
            if (STORY_BOSSES[i].equals(entityTypeId)) {
                witnessStory(player, STORY_BOSS_NODES[i]);
                return;
            }
        }
    }

    public static int storyMask(ServerPlayer player) {
        // 故事线必须严格按幕推进：前一幕未解锁时，后续即使支线 Quest 已完成也不提前显示。
        int mask = 1;
        for (int i = 1; i < 8; i++) {
            if ((mask & (1 << (i - 1))) == 0) break;
            if (!FtbQuestsCompat.isQuestCompleted(player, STORY_QUESTS[i])) break;
            mask |= 1 << i;
        }
        // 事件见证：检测到对应事件且上一级已解锁（任务线或事件线合并）则直接解锁该幕。
        int witness = player.getPersistentData().getInt(STORY_WITNESS);
        for (int i = 1; i < 8; i++) {
            if ((mask & (1 << (i - 1))) == 0) break;
            if ((witness & (1 << i)) != 0) mask |= 1 << i;
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
