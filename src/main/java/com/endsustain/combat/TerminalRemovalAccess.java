package com.endsustain.combat;

/**
 * 审计 P1：removalReason 字段直达接口。
 * 任何覆写 remove/setRemoved 拒绝移除的实体，其 isRemoved() 的唯一真相来源是
 * Entity.removalReason 字段——直写该字段并补全原版 setRemoved 的摘除语义
 * （乘客分离 + levelCallback.onRemove + invalidateCaps），无法被任何虚方法覆写拦截。
 */
public interface TerminalRemovalAccess {
    /** 强制建立移除状态并摘出世界结构；killed=true 用 KILLED 理由。 */
    void endsustain$forceRemoval(boolean killed);
}
