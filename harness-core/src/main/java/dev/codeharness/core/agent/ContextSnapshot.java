package dev.codeharness.core.agent;

/** 压缩边界仅在源历史前缀指纹完全一致时复用；回退、更改模型后按新预算重新验证。 */
public record ContextSnapshot(int version, int boundary, String prefixFingerprint, ContextSummary summary) {
    public ContextSnapshot {
        if (version != 1 || boundary < 1 || prefixFingerprint == null || !prefixFingerprint.matches("[0-9a-f]{64}") || summary == null)
            throw new IllegalArgumentException("压缩检查点格式无效");
    }
}
