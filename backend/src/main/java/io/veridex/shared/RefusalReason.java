package io.veridex.shared;

/**
 * 拒答原因（平台共享枚举）：trace / generation / qa 模块共用。
 * 拒答是正常业务结果，不是系统失败。
 */
public enum RefusalReason {
    NO_RELEVANT_EVIDENCE,
    INSUFFICIENT_EVIDENCE,
    OUT_OF_SCOPE,
    CONFLICTING_EVIDENCE,
    CONTENT_NOT_EFFECTIVE,
    ACCESS_RESTRICTED,
    SAFETY_POLICY
}
