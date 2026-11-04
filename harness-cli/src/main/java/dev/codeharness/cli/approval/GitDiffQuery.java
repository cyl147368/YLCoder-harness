package dev.codeharness.cli.approval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 只解析本地差异查询；参数始终直接交给 Git，不解释 shell 或 Git 路径魔法。 */
final class GitDiffQuery {
    private static final Set<String> FORMATS = Set.of("--stat", "--numstat", "--shortstat", "--name-only", "--name-status");

    static List<String> parse(String command) {
        List<String> words = words(command);
        if (words == null || words.size() < 2 || !words.getFirst().equals("git") || !words.get(1).equals("diff")) return null;
        boolean paths = false, staged = false;
        String format = null;
        var files = new ArrayList<String>();
        for (int i = 2; i < words.size(); i++) {
            String word = words.get(i);
            if (paths) {
                if (!safePath(word) || files.size() >= 64) return null;
                files.add(word);
            } else if (word.equals("--")) paths = true;
            else if (word.equals("--cached") || word.equals("--staged")) staged = true;
            else if (FORMATS.contains(word)) {
                if (format != null) return null;
                format = word;
            } else if (!Set.of("--no-ext-diff", "--no-textconv", "--ignore-submodules=all", "--no-color").contains(word)) return null;
        }
        var result = new ArrayList<String>(List.of("diff"));
        if (staged) result.add("--cached");
        if (format != null) result.add(format);
        result.addAll(List.of("--no-ext-diff", "--no-textconv", "--ignore-submodules=all", "--no-relative", "--"));
        result.addAll(files);
        return List.copyOf(result);
    }

    /** 仅支持整参数引号，允许中文和空格文件名；拒绝展开、转义及 shell 运算符。 */
    private static List<String> words(String command) {
        if (command.length() > 4096) return null;
        var result = new ArrayList<String>();
        for (int i = 0; i < command.length();) {
            if (command.charAt(i) == ' ') { i++; continue; }
            char quote = command.charAt(i);
            boolean quoted = quote == '\'' || quote == '"';
            if (quoted) i++;
            int start = i;
            while (i < command.length() && (quoted ? command.charAt(i) != quote : command.charAt(i) != ' ')) i++;
            if (quoted && (i == command.length() || i + 1 < command.length() && command.charAt(i + 1) != ' ')) return null;
            String word = command.substring(start, i);
            if (word.isEmpty() || word.length() > 512 || !word.codePoints().allMatch(value -> Character.isLetterOrDigit(value)
                    || " ./_-=".indexOf(value) >= 0)) return null;
            result.add(word);
            if (result.size() > 80) return null;
            if (quoted) i++;
        }
        return result;
    }

    private static boolean safePath(String value) {
        if (value.startsWith("/") || value.startsWith("-") || value.endsWith(" ")) return false;
        for (String part : value.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.isEmpty() || part.equals("..") || lower.equals(".git") || lower.equals(".harness")
                    || part.endsWith(" ") || part.endsWith(".") && !part.equals(".")) return false;
        }
        return true;
    }

    static void validatePaths(Path workspace, List<String> operation) throws IOException {
        int separator = operation.indexOf("--");
        List<String> files = operation.subList(separator + 1, operation.size());
        // 无 pathspec 的普通 diff 会读取已跟踪的工作区文件。这里不扫描无关的未跟踪目录，
        // 因此不把工作区遍历当作 OS 沙箱；显式 pathspec 仍逐级检查真实位置。
        if (files.isEmpty()) return;
        for (String file : files) {
            Path target = workspace.resolve(file).normalize();
            if (!target.startsWith(workspace)) throw boundary();
            // 删除的文件仍可查询。只检查实际 pathspec 及其尚存父目录；目录也只看
            // 所请求的入口，Git 随后按索引筛选，不会跟随目录中的链接读取工作区外文件。
            for (Path part = target; part != null && part.startsWith(workspace); part = part.getParent()) {
                if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (Files.isSymbolicLink(part) || !part.toRealPath().equals(part)) throw boundary();
                } catch (IOException failure) {
                    throw new GitStatusConfig.Ineligible(
                            "Git 差异路径不可访问或无法验证真实位置，需要人工确认");
                }
            }
        }
    }

    private static IOException boundary() {
        return new GitStatusConfig.Ineligible("Git 差异路径包含链接、特殊文件或超出工作区检查上限，需要人工确认");
    }

    private GitDiffQuery() {}
}
