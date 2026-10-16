package dev.codeharness.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TerminalInputTest {
    @Test
    void redirectedUnicodeLinesRetainExactTextAndRemainNonInteractive() throws Exception {
        var input = TerminalInput.utf8(new ByteArrayInputStream(
                "看下当前项目 😀\r\n继续修复 src/中文.java\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(input.isTerminal(), "管道输入不能被用于权限审批");
        assertEquals("看下当前项目 😀", input.readLine());
        assertEquals("继续修复 src/中文.java", input.readLine());
        assertNull(input.readLine());
    }

    @Test
    void invalidUtf8FailsBeforeReplacementTextCanReachModel() {
        var input = TerminalInput.utf8(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28, '\n'}));
        assertThrows(CharacterCodingException.class, input::readLine);
    }
}
