package com.github.claudecodegui.protocol;

public interface ProtocolValue {
    String value();

    /**
     * 该协议值的可选人类可读描述。
     * <p>C6:为 {@code desc} 约定提供接口级来源(AGENTS.md 附录"枚举 value/desc 统一"),
     * 消除"desc 约定空挂"。默认返回空串(表示"未提供描述"),现有 value-only 枚举
     * ({@link UpstreamAction}/{@link DownstreamEvent})无需改动即可合规;
     * 未来带描述的业务枚举(如 C2 的 PermissionMode/ReasoningEffort)覆盖此方法返回实际描述。
     *
     * @return 描述文本,未提供时为空串(始终非 null,便于序列化与前端消费)
     */
    default String desc() {
        return "";
    }
}
