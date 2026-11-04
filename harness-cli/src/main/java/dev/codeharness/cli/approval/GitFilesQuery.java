package dev.codeharness.cli.approval;

import java.util.ArrayList;
import java.util.List;

/** 将组合命令中的 rg --files 段转换为 Git 文件清单，避免依赖系统 rg。 */
final class GitFilesQuery {
    static List<String> parse(List<String> words) {
        if (words.size() < 2 || !words.getFirst().equals("rg") || !words.get(1).equals("--files")) return null;
        var paths = new ArrayList<String>();
        for (int index = 2; index < words.size(); index++) {
            String value = words.get(index);
            if (value.startsWith("-") || paths.size() >= 32 || !safePath(value)) return null;
            paths.add(value);
        }
        if (paths.isEmpty()) paths.add(".");
        var result = new ArrayList<String>(List.of(
                "ls-files", "--cached", "--others", "--exclude-standard", "--"));
        result.addAll(paths);
        return List.copyOf(result);
    }

    private static boolean safePath(String value) {
        if (value.isBlank() || value.length() > 512 || value.startsWith("/") || value.startsWith("\\")
                || value.contains(":") || value.contains("*") || value.contains("?")
                || value.chars().anyMatch(code -> ";&<>|`$".indexOf(code) >= 0)) return false;
        for (String part : value.replace('\\', '/').split("/", -1)) {
            String lower = part.toLowerCase(java.util.Locale.ROOT);
            if (part.equals("..") || lower.equals(".git") || lower.equals(".harness")
                    || part.endsWith(".")) return false;
        }
        return true;
    }

    private GitFilesQuery() {}
}
