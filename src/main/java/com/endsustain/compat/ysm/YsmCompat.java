package com.endsustain.compat.ysm;

import com.endsustain.EndSustain;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Yes Steve Model 兼容子模块。<p>
 * 运行时通过反射调用 YSM API 将「末影蘸酱1.0.ysm」模型绑定到落幕之终焉 Boss。
 * 若 YSM 未加载或反射失败，静默降级（使用 GeckoLib 内置模型）。
 */
public class YsmCompat {

    /** .ysm 模型文件在模组资源中的路径 */
    private static final ResourceLocation YSM_MODEL =
            new ResourceLocation(EndSustain.MOD_ID, "models/entity/finale/moying_zhajiang.ysm");

    private static final ResourceLocation TOUHOU_MAID =
            new ResourceLocation("touhou_little_maid", "maid");
    private static final Set<String> ZHAJIANG_MODEL_NAMES = Set.of(
            "末影蘸酱", "末影蘸酱1.0", "末影蘸酱1.0.ysm", "moying_zhajiang", "moying_zhajiang.ysm"
    );

    public static void init(IEventBus modBus) {
        try {
            applyYsmModel();
            EndSustain.LOGGER.info("[终焉维系] YSM 模型绑定成功");
        } catch (Throwable t) {
            EndSustain.LOGGER.warn("[终焉维系] YSM 模型绑定失败，回退到 GeckoLib 内置模型: {}", t.toString());
        }
    }

    /**
     * 服务端判断目标是否为使用“末影蘸酱”YSM 模型的车万女仆。
     * Touhou Little Maid 会把 YSM 数据同步到 EntityMaid，并公开
     * isYsmModel/getYsmModelId/getYsmModelName；这里使用反射保持软依赖。
     */
    public static boolean isZhajiangMaid(Entity target) {
        ResourceLocation entityId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        if (!TOUHOU_MAID.equals(entityId)) return false;

        try {
            Method isYsmModel = target.getClass().getMethod("isYsmModel");
            if (!Boolean.TRUE.equals(isYsmModel.invoke(target))) return false;

            for (String getter : new String[]{"getYsmModelName", "getYsmModelId", "getModelId"}) {
                try {
                    Object value = target.getClass().getMethod(getter).invoke(target);
                    if (value instanceof Component component && matchesZhajiangModel(component.getString())) return true;
                    if (value != null && matchesZhajiangModel(value.toString())) return true;
                } catch (ReflectiveOperationException ignored) { }
            }
        } catch (ReflectiveOperationException ignored) { }

        // EntityMaid 的存档字段也是服务端权威数据，作为跨版本 getter 兼容回退。
        CompoundTag saved = new CompoundTag();
        target.saveWithoutId(saved);
        if (!saved.getBoolean("IsYsmModel")) return false;
        return matchesZhajiangModel(saved.getString("YsmModelName"))
                || matchesZhajiangModel(saved.getString("YsmModelId"));
    }

    private static boolean matchesZhajiangModel(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String normalized = raw.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        return ZHAJIANG_MODEL_NAMES.contains(normalized);
    }

    /**
     * 通过反射调用 YSM 的模型加载 API。
     * <p>YSM 内部使用 {@code yes_steve_model.api.ClientAPI} 或类似入口将模型绑定到实体类。</p>
     */
    @SuppressWarnings("unchecked")
    private static void applyYsmModel() throws Exception {
        // 尝试路径: yes_steve_model.api.ClientAPI 或 yes_steve_model.api.YsmModelAPI
        Class<?> apiClass = null;
        for (String name : new String[]{
                "yes_steve_model.api.ClientAPI",
                "yes_steve_model.api.YsmModelAPI",
                "team.lodestar.lodestone.systems.model.LodestoneModel" // fallback dummy
        }) {
            try {
                apiClass = Class.forName(name);
                break;
            } catch (ClassNotFoundException ignored) { }
        }
        if (apiClass == null) {
            EndSustain.LOGGER.info("[终焉维系] 未找到 YSM API 类，跳过模型绑定");
            return;
        }
        // ClientAPI.registerCustomModel(EntityType<?>, ResourceLocation) 或类似签名
        try {
            var method = apiClass.getMethod("registerCustomModel",
                    net.minecraft.world.entity.EntityType.class, ResourceLocation.class);
            method.invoke(null, com.endsustain.entity.ModEntities.FINALE.get(), YSM_MODEL);
            return;
        } catch (NoSuchMethodException ignored) { }

        // 备选: 直接注入 ModelUploader
        try {
            var method = apiClass.getMethod("uploadModel",
                    net.minecraft.world.entity.EntityType.class, String.class);
            // .ysm 文件在 assets 中，运行时转为文件系统路径
            method.invoke(null, com.endsustain.entity.ModEntities.FINALE.get(),
                    "endsustain:models/entity/finale/末影蘸酱1.0.ysm");
        } catch (NoSuchMethodException e) {
            EndSustain.LOGGER.info("[终焉维系] YSM API 方法签名不匹配，跳过");
        }
    }
}
