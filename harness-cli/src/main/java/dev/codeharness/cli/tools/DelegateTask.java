package dev.codeharness.cli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codeharness.core.agent.AgentListener;
import dev.codeharness.core.agent.AgentResult;
import dev.codeharness.core.agent.AgentRunOptions;
import dev.codeharness.core.agent.CodeAgent;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.ToolDefinition;
import dev.codeharness.core.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 子 agent：在独立对话里完成任务，只把最终总结交回主 agent。
 * 只读版本可与其他读取并行；可写版本串行执行，写入与命令沿用主 agent 的审核回调。
 */
public final class DelegateTask implements Tool {
    public static final String NAME = "delegate_task";
    public static final String WRITE_NAME = "delegate_write_task";
    /** 子 agent 可用的只读工具；不含自身，避免递归派生。 */
    public static final Set<String> READ_ONLY_TOOLS = Set.of("list_files", "search_files", "read_file",
            "repository_map", "search_history", "read_tool_output", "web_search", "web_fetch");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DESCRIPTION = 60;
    private static final String READ_PROMPT = """
            你是一个只读的代码调研子 agent。只能使用读取和搜索类工具，不能修改文件或执行命令。
            围绕交给你的任务高效检索：先定位相关文件，再分段读取必要片段，不要全库遍历。
            完成后用简洁的中文总结结论，列出关键文件路径和行号，并说明哪些内容没有验证。
            你的总结是交给主 agent 的唯一产出，主 agent 看不到你的中间工具输出。
            """;
    private static final String WRITE_PROMPT = """
            你是一个负责实施修改的子 agent。只做交给你的这一项改动，范围之外的文件和无关变更保持不动。
            修改前先读相关代码；写入和命令仍需通过权限审核，被拒绝时不要换个方式绕过，直接在总结里说明。
            改完后按需做最小验证（编译或相关测试）。
            完成后用简洁的中文总结：改了哪些文件和位置、验证结果、未完成或未验证的事项。
            你的总结是交给主 agent 的唯一产出，主 agent 看不到你的中间工具输出。
            """;

    /** 子任务进度回调；实现不应抛出异常。 */
    public interface Observer {
        Observer NOOP = new Observer() {};
        default void onStart(String description) {}
        default void onToolStart(String description, ToolCall call) {}
        default void onFinish(String description, AgentResult result) {}
    }

    private final Supplier<CodeAgent> parent;
    private final Supplier<AgentRunOptions> options;
    private final Observer observer;
    private final boolean writable;

    public DelegateTask(Supplier<CodeAgent> parent, Supplier<AgentRunOptions> options, Observer observer) {
        this(parent, options, observer, false);
    }

    private DelegateTask(Supplier<CodeAgent> parent, Supplier<AgentRunOptions> options, Observer observer, boolean writable) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.options = Objects.requireNonNull(options, "options");
        this.observer = observer == null ? Observer.NOOP : observer;
        this.writable = writable;
    }

    /** 可写子 agent：拿到除委派工具外的全部工具，写入和命令照常审核。 */
    public static DelegateTask writable(Supplier<CodeAgent> parent, Supplier<AgentRunOptions> options, Observer observer) {
        return new DelegateTask(parent, options, observer, true);
    }

    /** 只读子任务互不影响，可以并行；可写子任务必须是串行屏障，避免并发改同一文件。 */
    @Override public boolean isParallelSafeRead() { return !writable; }

    static boolean childAllows(boolean writable, String tool) {
        if (tool.equals(NAME) || tool.equals(WRITE_NAME)) return false;
        return writable || READ_ONLY_TOOLS.contains(tool);
    }

    @Override public ToolDefinition definition() {
        if (writable) return new ToolDefinition(WRITE_NAME, """
                把一项范围明确的修改交给独立的子 agent 实施，它可以读写文件和执行命令（照常经过权限审核），只返回最终总结。
                适合多处机械改动或改完需要反复编译验证的工作，让中间输出留在子 agent 里。可写子任务按顺序逐个执行，不会并行。
                prompt 必须具体：要改的文件路径和行号、改成什么、怎么验证、哪些文件不能碰。
                不要把理解外包，例如不要写“根据你的发现去修复”；先自己调研清楚再委派。拿到总结后自己检查结果。""",
                schema());
        return new ToolDefinition(NAME, """
                把一个只读调研任务交给独立的子 agent（只能列目录、搜索、读文件和查资料），只返回它的最终总结。
                适合开放式的代码探索，中间工具输出不会进入你的上下文；相互独立的问题可以在同一轮里发起多个，它们会并行执行。
                像给刚进门的同事交代一样写 prompt：
                说明目标和原因、已知路径与已排除的方向、期望的返回格式。不要把理解外包，例如不要写“根据你的发现去修复”。
                子 agent 不能修改文件；需要委派修改时用 delegate_write_task。""",
                schema());
    }

    private static Map<String, Object> schema() {
        return Map.of("type", "object",
                        "properties", Map.of(
                                "description", Map.of("type", "string", "description", "3～8 个字的任务简称，用于界面显示"),
                                "prompt", Map.of("type", "string", "description", "交给子 agent 的完整任务说明")),
                        "required", List.of("description", "prompt"));
    }

    @Override public String execute(String argumentsJson) throws Exception {
        JsonNode args = JSON.readTree(argumentsJson == null ? "{}" : argumentsJson);
        String prompt = text(args, "prompt");
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt 不能为空");
        String description = text(args, "description");
        if (description.isBlank()) description = "子任务";
        if (description.length() > MAX_DESCRIPTION) description = description.substring(0, MAX_DESCRIPTION);
        CodeAgent base = parent.get();
        if (base == null) throw new IllegalStateException("子 agent 尚未就绪");
        CodeAgent child = base.withTools(tool -> childAllows(writable, tool.definition().name()));
        AgentRunOptions inherited = options.get();
        AgentRunOptions childOptions = new AgentRunOptions(writable ? WRITE_PROMPT : READ_PROMPT, inherited.maxIterations(), inherited.contextTokenBudget(),
                inherited.maxOutputTokens(), inherited.maxToolOutputChars(), inherited.cancelled(), inherited.maxParallelReads());
        String label = description;
        observer.onStart(label);
        AgentResult result;
        try {
            result = child.run(prompt, childOptions, new AgentListener() {
                @Override public void onToolStart(int iteration, ToolCall call) {
                    try { observer.onToolStart(label, call); } catch (RuntimeException ignored) { /* 进度不能打断子任务。 */ }
                }
            });
        } finally {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("子任务已取消");
        }
        observer.onFinish(label, result);
        return format(result);
    }

    static String format(AgentResult result) {
        String usage = "（子任务 " + result.iterations() + " 次请求，输入 " + result.usage().inputTokens()
                + " / 输出 " + result.usage().outputTokens() + " token）";
        return switch (result.status()) {
            case COMPLETED -> result.finalAnswer().strip() + "\n\n" + usage;
            case CANCELLED -> "子任务已取消。" + usage;
            default -> "子任务未完成：" + (result.error().isBlank() ? result.status().name() : result.error())
                    + (result.finalAnswer().isBlank() ? "" : "\n已有输出：\n" + result.finalAnswer().strip()) + "\n" + usage;
        };
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        return node != null && node.isTextual() ? node.textValue().strip() : "";
    }
}
