package dev.codeharness.core.agent;

import dev.codeharness.core.model.ChatMessage;
import dev.codeharness.core.model.TokenUsage;
import java.time.Duration;
import java.util.List;

/** 编码任务的不可变结果与会话记录。 */
public record AgentResult(Status status, String finalAnswer, String error, int iterations,
                          TokenUsage usage, Duration elapsed, List<ChatMessage> transcript) {
    public AgentResult {
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
        error = error == null ? "" : error;
        usage = usage == null ? TokenUsage.ZERO : usage;
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        transcript = List.copyOf(transcript == null ? List.of() : transcript);
    }
    public enum Status { COMPLETED, MAX_ITERATIONS, CANCELLED, FAILED }
}
