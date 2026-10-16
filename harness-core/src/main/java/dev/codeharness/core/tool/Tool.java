package dev.codeharness.core.tool;

import dev.codeharness.core.model.ToolDefinition;

/** 可以展示给模型、在本地执行的工具。 */
public interface Tool {
    ToolDefinition definition();
    String execute(String argumentsJson) throws Exception;
    /**
     * 仅只读且能安全地与其他读取并行的工具可以显式启用。
     * 实现必须线程安全并配合中断。修改、审批提示、命令及尚未分类的扩展
     * 必须保留默认设置。
     */
    default boolean isParallelSafeRead() { return false; }
}
