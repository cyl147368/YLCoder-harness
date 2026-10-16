package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.ToolCall;
import java.util.List;

/** 可选的进度与指标观察者；循环忽略监听器失败。 */
public interface AgentListener {
    AgentListener NOOP = new AgentListener() {};
    default void onModelRequest(int iteration, ChatRequest request) {}
    default void onModelResponse(int iteration, ChatResponse response) {}
    default void onToolStart(int iteration, ToolCall call) {}
    default void onToolResult(int iteration, ToolCall call, String result, boolean error) {}
    default void onText(String text) {}
    /** 厂商逐段提供的可见文本，可能在其流式线程上通知。 */
    default void onTextDelta(String delta) {}
    /** 持久检查点；与进度回调不同，保存失败会在执行后续工具前停止任务。 */
    default void onCheckpoint(List<ChatMessage> transcript) {}
}
