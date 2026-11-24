package dev.codeharness.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loads AGENTS.md files from the workspace root and its ancestors so project rules are always in the system prompt. */
final class ProjectInstructions {
    static final String FILE_NAME = "AGENTS.md";
    static final int MAX_FILE_CHARS = 32_000;
    static final int MAX_TOTAL_CHARS = 64_000;
    /** Cross-session memory index; individual memory files are read on demand by the agent. */
    static final String MEMORY_INDEX = ".harness/memory/MEMORY.md";
    static final int MAX_MEMORY_CHARS = 8_000;

    private ProjectInstructions() {}

    /** Outer directories first, the workspace's own file last, so the most specific rules win on conflict. */
    static String load(Path workspace) {
        if (workspace == null) return "";
        List<Path> found = new ArrayList<>();
        for (Path dir = workspace.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(FILE_NAME);
            if (Files.isRegularFile(candidate)) found.add(candidate);
        }
        Collections.reverse(found);
        StringBuilder text = new StringBuilder();
        for (Path file : found) {
            String body;
            try { body = Files.readString(file, StandardCharsets.UTF_8); }
            catch (Exception unreadable) { continue; }
            if (body.isBlank()) continue;
            if (body.length() > MAX_FILE_CHARS) body = body.substring(0, MAX_FILE_CHARS) + "\n[truncated]";
            String section = "\n# Project instructions from " + file + "\n" + body.strip() + "\n";
            if (text.length() + section.length() > MAX_TOTAL_CHARS) break;
            text.append(section);
        }
        Path memory = workspace.toAbsolutePath().normalize().resolve(MEMORY_INDEX);
        if (Files.isRegularFile(memory)) {
            try {
                String body = Files.readString(memory, StandardCharsets.UTF_8).strip();
                if (body.length() > MAX_MEMORY_CHARS) body = body.substring(0, MAX_MEMORY_CHARS) + "\n[truncated]";
                if (!body.isEmpty()) text.append("\n# Project memory index (")
                        .append(MEMORY_INDEX)
                        .append("; user/feedback/project/reference entries, do not store facts derivable from code)\n")
                        .append(body).append('\n');
            } catch (Exception unreadable) { /* memory is optional */ }
        }
        return text.toString();
    }
}
