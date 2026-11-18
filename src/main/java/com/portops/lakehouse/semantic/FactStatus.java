package com.portops.lakehouse.semantic;

/** 作业事实的生命周期状态。 */
public enum FactStatus {
    /** 已完成作业：最小必要证据齐备（场桥为 吊起+落下 完整链） */
    COMPLETED,
    /** 在途作业：链未闭合且仍在可容忍时间窗内（含部分证据推断） */
    IN_FLIGHT,
    /** 中断待核：链未闭合且超出可容忍时间窗，或证据矛盾，需人工核对 */
    INTERRUPTED,
    /** 不可能状态：违反设备/堆位/顺序约束，只能作为冲突事实 */
    IMPOSSIBLE
}
