package dev.codeharness.cli;

import dev.codeharness.core.model.ChatMessage;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 本地有界分流；不确定时交给主模型，不把工具输出当成用户授权。 */
final class TaskIntent {
    enum Mode { AUTO, CHAT, ANALYZE, PLAN, EDIT, EXECUTE }
    record Decision(Mode mode, String reason) {
        String id() { return mode.name().toLowerCase(Locale.ROOT); }
        boolean allows(String tool) {
            return switch (mode) {
                case CHAT -> false;
                case ANALYZE, PLAN -> Set.of("list_files", "search_files", "read_file", "changes", "web_search", "web_fetch", "repository_map", "search_history", "read_tool_output").contains(tool);
                default -> true;
            };
        }
        String guidance() {
            return "\n当前任务意图：" + id() + "。" + switch (mode) {
                case CHAT -> "直接回答，不执行工具，不声称检查过工作区。";
                case ANALYZE -> "只读检查和解释；不修改文件、不执行 shell、构建或扩展工具。若用户只是让查看当前项目，先用一次小型 repository_map 获取结构，读取项目指引、README 和构建入口的必要片段（初次每个文件约 100 行），再抽样 2～3 个关键实现。证据足以说明用途、结构与主要问题后就先给出概览；不默认逐文件遍历、翻完所有分页或进行全库审计。只有具体问题或证据缺口才定向追加读取，已看过且未变化的内容不要重复读取；明确说明抽样范围与未验证事项。";
                case PLAN -> "只读调研并提出方案，等待用户要求实施；不修改文件、不执行 shell、构建或扩展工具。";
                case EDIT -> "按用户要求实施修改，保留无关变更，按需验证；继续遵守权限审核。";
                case EXECUTE -> "按用户要求执行或验证任务，继续遵守权限审核。";
                case AUTO -> "本地规则无法确定；按原始请求判断，问题和方案请求不代表修改授权。";
            };
        }
    }
    static Mode parse(String value) {
        try { return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("意图可选 auto、chat、analyze、plan、edit、execute"); }
    }
    static Decision resolve(List<ChatMessage> history, Mode override) {
        if (override != Mode.AUTO) return new Decision(override, "手动指定");
        int inspected = 0;
        boolean continued = false;
        for (int i = history.size() - 1; i >= 0 && inspected < 8; i--) {
            ChatMessage message = history.get(i);
            if (message.role() != ChatMessage.Role.USER) continue;
            inspected++;
            String raw = message.content();
            if (raw.length() > 8192) return new Decision(Mode.AUTO, "输入较长，交给主模型判断");
            String text = raw.replaceAll("(?s)\u0060{3}.*?(?:\u0060{3}|$)", " ")
                    .replaceAll("(?m)^\\s*>.*$", " ").replaceAll("\u0060[^\u0060]*\u0060", " ").strip().toLowerCase(Locale.ROOT);
            if (text.matches("(?:继续|继续吧|接着来|continue|go on)[。.!！\\s]*")) { continued = true; continue; }
            Mode mode = classify(text);
            return new Decision(mode, continued ? "继承最近用户任务" : mode == Mode.AUTO ? "交给主模型判断" : "本地规则识别");
        }
        return new Decision(Mode.AUTO, "没有可继承的明确用户任务");
    }
    private static Mode classify(String text) {
        if (text.matches("(?:你好|您好|嗨|hi|hello|谢谢|thanks)[。.!！\\s]*")) return Mode.CHAT;
        if (text.matches("(?s).*(?:不要|别|不许|无需|先不)(?:修改|改动|改|写入|执行|运行).*|.*(?:只读|仅分析|只分析|read.only|do not (?:edit|modify|execute)).*")) return Mode.ANALYZE;
        if (text.matches("(?s).*(?:先给.*方案|先.*设计|给我.*方案|制定计划|只.*方案|plan only|propose a plan).*")) return Mode.PLAN;
        if (text.matches("(?s).*(?:怎么|如何|为什么|怎样|是什么|啥意思|how (?:to|do|can)|why).*")) return Mode.ANALYZE;
        if (text.matches("(?s).*(?:修复|修改|实现)(?:了|完了|好了)(?:吗|么).*")) return Mode.ANALYZE;
        if (text.matches("(?s)(?:请)?(?:解释|分析|查看|看看|看(?:一)?下).*(?:实现原理|实现逻辑|修复记录|修改记录).*")) return Mode.ANALYZE;
        if (text.matches("(?s).*(?:实现|修复|修改|重构|新增|添加|删除|清理|优化|改进|implement|fix|refactor).*")) return Mode.EDIT;
        if (text.matches("(?s).*(?:运行|执行|编译|构建|跑.*测试|提交|推送|部署|run |build |test |deploy).*")) return Mode.EXECUTE;
        if (text.matches("(?s).*(?:分析|检查|看(?:一)?下|看看|查看|解释|评审|优缺点|review|analy[sz]e|explain|inspect).*")) return Mode.ANALYZE;
        return Mode.AUTO;
    }
}
