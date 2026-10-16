package dev.codeharness.core.model;

import java.util.Objects;
import java.util.Map;

/** 模型请求的工具调用；参数保留原始 JSON，避免绑定厂商专用 JSON 依赖。 */
public record ToolCall(String id, String name, String argumentsJson, Map<String, String> providerMetadata) {
    public ToolCall {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        argumentsJson = Objects.requireNonNullElse(argumentsJson, "{}");
        providerMetadata = Map.copyOf(Objects.requireNonNullElse(providerMetadata, Map.of()));
    }

    /** 保持源码兼容的构造器，适用于没有不透明元数据的厂商与已保存会话。 */
    public ToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, Map.of());
    }
}
