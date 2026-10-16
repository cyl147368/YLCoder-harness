package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;

class StreamingOutputTest {
    @Test void printsAndFlushesBeforeResponseCompletionAndSuppressesFinalDuplicate() {
        StringWriter text = new StringWriter();
        StreamingOutput output = new StreamingOutput(new PrintWriter(text), true);
        output.startResponse();
        output.delta("你好");
        assertEquals("你好", text.toString());
        output.delta("，世界");
        output.endResponse("你好，世界");
        assertEquals("你好，世界" + System.lineSeparator(), text.toString());
        assertTrue(output.displayed("你好，世界"));
    }

    @Test void completesAnOmittedFinalFragmentAndResetsBetweenToolTurns() {
        StringWriter text = new StringWriter();
        StreamingOutput output = new StreamingOutput(new PrintWriter(text), true);
        output.startResponse();
        output.delta("检查");
        output.endResponse("检查文件");
        assertTrue(output.displayed("检查文件"));
        output.startResponse();
        assertFalse(output.displayed("检查文件"));
        output.endResponse("完成");
        assertFalse(output.displayed("完成"));
        assertEquals("检查文件" + System.lineSeparator(), text.toString());
    }

    @Test void disabledOutputKeepsJsonCleanAndFailedStreamGetsANewline() {
        StringWriter text = new StringWriter();
        StreamingOutput json = new StreamingOutput(new PrintWriter(text), false);
        json.delta("不应显示的文本");
        json.endResponse("不应显示的文本");
        assertEquals("", text.toString());
        assertFalse(json.displayed("不应显示的文本"));
        StreamingOutput interrupted = new StreamingOutput(new PrintWriter(text), true);
        interrupted.delta("部分");
        interrupted.finishLine();
        interrupted.finishLine();
        assertEquals("部分" + System.lineSeparator(), text.toString());
        assertFalse(interrupted.displayed("部分"));
    }
}
