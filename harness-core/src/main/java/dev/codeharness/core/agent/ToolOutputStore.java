package dev.codeharness.core.agent;

/** 在工具输出进入会话前保存可检索原文，并生成有界的模型投影。 */
@FunctionalInterface
public interface ToolOutputStore {
    ToolOutputStore CLIP = (toolName, output, maxChars) -> ContextCompactor.clipMiddle(output, maxChars);

    /** 实现应先脱敏再存档；不能重放调用，存储失败必须返回明确说明。 */
    String store(String toolName, String output, int maxChars) throws Exception;
}
