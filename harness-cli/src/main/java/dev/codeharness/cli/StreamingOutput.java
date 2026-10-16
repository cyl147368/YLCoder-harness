package dev.codeharness.cli;

import java.io.PrintWriter;

/** 立即输出厂商文本，仅保留当前响应以避免重复显示。 */
final class StreamingOutput {
    private final PrintWriter output;
    private final boolean enabled;
    private final StringBuilder current = new StringBuilder();
    private String displayedResponse = "";
    private boolean lineOpen;

    StreamingOutput(PrintWriter output, boolean enabled) {
        this.output = output;
        this.enabled = enabled;
    }

    synchronized void startResponse() {
        finishLine();
        current.setLength(0);
        displayedResponse = "";
    }

    synchronized void delta(String delta) {
        if (!enabled || delta == null || delta.isEmpty()) return;
        current.append(delta);
        output.print(delta);
        lineOpen = !delta.endsWith("\n");
        output.flush();
    }

    synchronized void endResponse(String text) {
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
        if (lineOpen) {
            output.println();
            output.flush();
            lineOpen = false;
        }
    }
}
