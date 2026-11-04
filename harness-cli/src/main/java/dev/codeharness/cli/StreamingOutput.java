package dev.codeharness.cli;

import java.io.PrintWriter;

/** 立即输出厂商文本，仅保留当前响应以避免重复显示。 */
final class StreamingOutput {
    private final PrintWriter output;
    private final boolean enabled;
    private final boolean markdown;
    private final boolean color;
    private final StringBuilder current = new StringBuilder();
    private String displayedResponse = "";
    private boolean lineOpen;
    private final String indent;

    StreamingOutput(PrintWriter output, boolean enabled) {
        this(output, enabled, "", false, false);
    }

    StreamingOutput(PrintWriter output, boolean enabled, String indent) {
        this(output, enabled, indent, false, false);
    }

    StreamingOutput(PrintWriter output, boolean enabled, String indent, boolean markdown, boolean color) {
        this.output = output;
        this.enabled = enabled;
        this.indent = indent;
        this.markdown = markdown;
        this.color = color;
    }

    synchronized void startResponse() {
        finishLine();
        current.setLength(0);
        displayedResponse = "";
    }

    synchronized void delta(String delta) {
        if (!enabled || delta == null || delta.isEmpty()) return;
        current.append(delta);
        if (markdown) return;
        for (int index = 0; index < delta.length(); index++) {
            char character = delta.charAt(index);
            if (!lineOpen && character != '\n') output.print(indent);
            output.print(character);
            lineOpen = character != '\n';
        }
        output.flush();
    }

    synchronized void endResponse(String text) {
        if (markdown && enabled) {
            String full = text == null || text.isEmpty() ? current.toString() : text;
            output.print(MarkdownRenderer.render(full, color, indent));
            output.flush();
            displayedResponse = full;
            current.setLength(0);
            return;
        }
        if (enabled && !current.isEmpty() && text.startsWith(current.toString())) {
            // 某些传输方式的增量事件会遗漏最终文本片段。
            delta(text.substring(current.length()));
            displayedResponse = text;
        }
        finishLine();
    }

    synchronized boolean displayed(String text) {
        return enabled && !text.isEmpty() && displayedResponse.equals(text);
    }

    synchronized void finishLine() {
        if (markdown && enabled && !current.isEmpty()) {
            output.print(MarkdownRenderer.render(current.toString(), color, indent));
            output.flush();
            current.setLength(0);
            displayedResponse = "";
        }
        if (lineOpen) {
            output.println();
            output.flush();
            lineOpen = false;
        }
    }
}
