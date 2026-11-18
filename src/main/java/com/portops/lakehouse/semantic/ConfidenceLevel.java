package com.portops.lakehouse.semantic;

/**
 * 事实置信等级。
 * CONFIRMED 与推断等级严格区分：晚到证据只能撤销推断，不能倒退已证实事实。
 */
public enum ConfidenceLevel {
    /** 已证实：完整阶段链或权威单点证据（闸口/装船/盘点） */
    CONFIRMED,
    /** 高置信推断：必要阶段齐备，仅缺中间过程证据（如 HOIST+DROP 缺 TRAVEL） */
    HIGH,
    /** 中置信推断：仅见作业起始证据，终态证据未到且仍在容忍窗内 */
    MEDIUM,
    /** 低置信：证据超出容忍窗或存在矛盾，等待裁定 */
    LOW
}
