package com.endsustain.combat;

/**
 * 死亡判定轴路径：记录某个实体的"真实死亡判据"落在哪个字段上。
 *
 * <p>直接字段形态：{@code containerField == null}，判定轴是 {@code owner.fieldName}。
 * <br>容器穿透形态（深度 1）：判定轴是 {@code owner.containerField.fieldName}，
 * 其中 {@code containerType} 是该容器对象的声明类型。
 *
 * <p>两种形态的 owner 都必须是目标实体自身继承链上的类（且不在 {@code net.minecraft.} 包内），
 * 容器类型同样不得来自 {@code net.minecraft.} 包——避免把判定轴误解析到
 * {@code level} / {@code random} / {@code passengers} 这类共享状态上。
 */
public record AxisPath(Class<?> owner, String fieldName,
                       Class<?> containerType, String containerField) {

    /** 判定轴是否为"容器对象内部的数值字段"（深度 1 穿透）。 */
    public boolean isNested() {
        return containerField != null;
    }

    /** 供日志与缓存键使用的稳定描述。 */
    public String describe() {
        return isNested()
                ? owner.getName() + "." + containerField + " -> " + fieldName
                : owner.getName() + "." + fieldName;
    }
}
