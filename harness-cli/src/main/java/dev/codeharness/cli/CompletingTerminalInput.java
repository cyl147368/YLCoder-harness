package dev.codeharness.cli;

import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/** JLine 负责 Unicode 行编辑和 Tab；不接管鼠标、不使用备用屏幕、不保存输入历史到磁盘。 */
final class CompletingTerminalInput implements TerminalInput {
    private final Terminal terminal;
    private final LineReader reader;
    private Supplier<List<String>> candidates = List::of;
    private Runnable interruptHandler = () -> { };
    private Runnable escapeHandler = () -> { };
    private TaskKeyMonitor taskKeys;
    private boolean taskInput;
    private boolean approvalModeToggle;
    private String inputDraft;
    private boolean modeSwitchAllowed;

    static TerminalInput open() {
        if ("dumb".equals(System.getenv("TERM"))) return null;
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).jna(false).jni(true).dumb(false)
                    .encoding(StandardCharsets.UTF_8).nativeSignals(true).build();
            if (terminal.getType().startsWith("dumb")) { terminal.close(); return null; }
            return new CompletingTerminalInput(terminal);
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            if (terminal != null) try { terminal.close(); } catch (IOException ignored) { }
            return null;
        }
    }

    CompletingTerminalInput(Terminal terminal) {
        this.terminal = terminal;
        var parser = new DefaultParser().escapeChars(null).quoteChars(null)
                .eofOnUnclosedQuote(false).eofOnEscapedNewLine(false);
        reader = LineReaderBuilder.builder().terminal(terminal).parser(parser)
                .completer((ignored, line, completions) -> {
                    // 仅编辑行尾的斜杠命令，避免改写普通任务或光标后的文本。
                    if (line.cursor() != line.line().length() || !line.line().startsWith("/")) return;
                    for (String word : CommandCompletion.words(line.line(), line.word(), candidates.get()))
                        completions.add(new Candidate(word));
                }).build();
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        reader.setOpt(LineReader.Option.AUTO_LIST);
        reader.setOpt(LineReader.Option.AUTO_MENU);
        reader.unsetOpt(LineReader.Option.AUTO_FRESH_LINE);
        reader.setVariable(LineReader.BELL_STYLE, "none");
        reader.setVariable(LineReader.HISTORY_SIZE, 100);
        // 单个匹配直接补全，多个匹配显示候选并可继续按 Tab 选择。
        reader.getKeyMaps().get(LineReader.MAIN).bind(new org.jline.reader.Reference(LineReader.COMPLETE_WORD), "\t");
        reader.getWidgets().put("ylc-escape", () -> escapeLine(false));
        reader.getWidgets().put("ylc-double-escape", () -> escapeLine(true));
        reader.getWidgets().put("ylc-approval-toggle", () -> {
            // 审批及回退选择共用读取器，但不是任务输入框，不能把按键变成审批答案。
            if (!modeSwitchAllowed) return true;
            inputDraft = reader.getBuffer().toString();
            approvalModeToggle = true;
            reader.getBuffer().clear();
            reader.callWidget(LineReader.ACCEPT_LINE);
            return true;
        });
        var keys = reader.getKeyMaps().get(LineReader.MAIN);
        keys.setAmbiguousTimeout(80);
        keys.bind(new org.jline.reader.Reference("ylc-escape"), "\033");
        keys.bind(new org.jline.reader.Reference("ylc-double-escape"), "\033\033");
        keys.bind(new org.jline.reader.Reference("ylc-approval-toggle"), "\033[Z");
        keys.bind(new org.jline.reader.Reference("ylc-approval-toggle"), "\033m");
    }

    private boolean escapeLine(boolean twice) {
        escapeHandler.run();
        if (twice) escapeHandler.run();
        reader.getBuffer().clear();
        reader.callWidget(LineReader.ACCEPT_LINE);
        return true;
    }

    @Override public boolean isTerminal() { return true; }
    @Override public boolean editsLine() { return true; }
    @Override public String readLine() throws IOException { return readCommand("", List::of); }
    @Override public String readCommand(String prompt, Supplier<List<String>> candidates) throws IOException {
        return readCommand(prompt, candidates, null, false);
    }
    @Override public String readCommand(String prompt, Supplier<List<String>> candidates, String initialBuffer) throws IOException {
        return readCommand(prompt, candidates, initialBuffer, true);
    }
    private String readCommand(String prompt, Supplier<List<String>> candidates, String initialBuffer,
                               boolean modeSwitchAllowed) throws IOException {
        pauseTaskInput();
        this.candidates = candidates;
        this.modeSwitchAllowed = modeSwitchAllowed;
        try { return reader.readLine(prompt, null, (org.jline.reader.MaskingCallback) null, initialBuffer); }
        catch (EndOfFileException end) { return null; }
        catch (UserInterruptException interrupted) { interruptHandler.run(); return null; }
        catch (java.io.IOError failure) { throw new IOException("无法读取终端输入", failure.getCause()); }
        finally {
            this.candidates = List::of;
            this.modeSwitchAllowed = false;
            if (taskInput) resumeTaskInput();
        }
    }
    @Override public Runnable onEscape(Runnable handler) {
        escapeHandler = handler;
        return () -> escapeHandler = () -> { };
    }
    @Override public void startTaskInput() { taskInput = true; resumeTaskInput(); }
    @Override public void stopTaskInput() { taskInput = false; pauseTaskInput(); }
    @Override public boolean takeApprovalModeToggle() {
        boolean requested = approvalModeToggle;
        approvalModeToggle = false;
        return requested;
    }
    @Override public String takeInputDraft() {
        String draft = inputDraft;
        inputDraft = null;
        return draft;
    }
    private void resumeTaskInput() {
        if (taskKeys == null) taskKeys = new TaskKeyMonitor(terminal, () -> escapeHandler.run(), reader::runMacro);
    }
    private void pauseTaskInput() {
        if (taskKeys != null) { taskKeys.close(); taskKeys = null; }
    }
    @Override public Runnable onInterrupt(Runnable handler) {
        interruptHandler = handler;
        var previous = terminal.handle(Terminal.Signal.INT, signal -> handler.run());
        return () -> {
            terminal.handle(Terminal.Signal.INT, previous);
            interruptHandler = () -> { };
        };
    }
    @Override public void closeEditor() throws IOException { stopTaskInput(); terminal.close(); }
}
