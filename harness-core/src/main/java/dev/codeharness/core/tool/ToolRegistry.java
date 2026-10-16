package dev.codeharness.core.tool;

import dev.codeharness.core.model.ToolDefinition;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 代理运行期间可用工具的明确注册表。 */
public final class ToolRegistry {
    private final Map<String, Tool> tools;

    public ToolRegistry(Collection<? extends Tool> tools) {
        Map<String, Tool> indexed = new LinkedHashMap<>();
        for (Tool tool : tools) {
            String name = tool.definition().name();
            if (indexed.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("工具名称重复：" + name);
        }
        this.tools = Collections.unmodifiableMap(indexed);
    }
    public static ToolRegistry empty() { return new ToolRegistry(List.of()); }
    public Optional<Tool> find(String name) { return Optional.ofNullable(tools.get(name)); }
    public List<ToolDefinition> definitions() { return tools.values().stream().map(Tool::definition).toList(); }
}
