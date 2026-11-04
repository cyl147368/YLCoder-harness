package dev.codeharness.cli.approval;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将命令统一解析为参数、连接操作符和 stderr 重定向，再分别判断本地放行或 AI 审核。
 * 支持字面量引号；变量展开、转义、后台执行、文件写入重定向和不完整语法整体拒绝。
 */
final class ReviewableShell {
    private static final int MAX_CHARS = 4_096;
    private static final int MAX_SEGMENTS = 12;
    private static final int MAX_WORDS = 128;
    private static final Pattern ECHO = Pattern.compile("[A-Za-z0-9_.:/=+-]+");
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,4}");
    private static final Set<String> DANGEROUS_COMMANDS = Set.of(
            "rm", "del", "erase", "rmdir", "rd", "format", "diskpart", "mkfs", "dd", "shred",
            "truncate", "touch", "mkdir", "md", "copy", "cp", "move", "mv", "ren", "rename",
            "attrib", "chmod", "chown", "chattr", "ln", "mklink", "tee", "curl", "wget", "scp",
            "sftp", "ssh", "nc", "netcat", "powershell", "pwsh", "cmd", "bash", "sh", "zsh",
            "fish", "python", "python3", "node", "npm", "npx", "pnpm", "yarn", "pip", "pip3",
            "go", "cargo", "rustc", "javac", "java", "jar", "mvn", "mvnw", "gradle", "gradlew",
            "docker", "kubectl", "systemctl", "service", "sc", "reg", "taskkill", "kill", "pkill",
            "shutdown", "reboot", "mount", "umount", "useradd", "passwd", "sudo", "su", "eval",
            "exec", "source", "set", "export", "xargs", "awk", "perl", "ruby", "php", "pytest",
            "date", "get-date");

    record Segment(List<String> words, boolean discardStandardError, boolean mergeStandardError) {
        Segment { words = List.copyOf(words); }
    }

    record Plan(List<Segment> segments, List<String> operators) {
        Plan {
            segments = List.copyOf(segments);
            operators = List.copyOf(operators);
            if (segments.size() != operators.size() + 1)
                throw new IllegalArgumentException("只读命令链的段与操作符数量不一致");
        }
    }

    static Plan parse(String command) {
        Plan plan = syntax(command);
        if (plan == null || plan.segments().stream().anyMatch(segment -> !safeSegment(segment.words()))) return null;
        // 单个 Git/时间/搜索查询继续使用专用入口，保留其直接 argv 和路径校验。
        if (plan.operators().isEmpty() && plan.segments().stream().noneMatch(segment ->
                segment.discardStandardError() || segment.mergeStandardError())
                && !Set.of("ls", "wc").contains(plan.segments().getFirst().words().getFirst())) return null;
        return plan;
    }

    static Plan syntax(String command) {

        if (command == null || command.length() > MAX_CHARS || command.isBlank()) return null;
        if (command.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT
                || Character.getType(code) == Character.LINE_SEPARATOR
                || Character.getType(code) == Character.PARAGRAPH_SEPARATOR
                || "`$%!(){}^＆；｜＜＞".indexOf(code) >= 0)) return null;
        var segments = new ArrayList<Segment>();
        var operators = new ArrayList<String>();
        var words = new ArrayList<String>();
        boolean discardError = false;
        boolean mergeError = false;
        int wordCount = 0;
        for (int index = 0; index < command.length();) {
            char value = command.charAt(index);
            if (Character.isISOControl(value) || "`$%!(){}^#".indexOf(value) >= 0) return null;
            if (Character.isWhitespace(value)) {
                index++;
                continue;
            }
            if (value == '2') {
                int end = stderrNullEnd(command, index);
                if (end > index) {
                    if (discardError || mergeError || words.isEmpty()) return null;
                    discardError = true;
                    index = end;
                    continue;
                }
                end = stderrMergeEnd(command, index);
                if (end > index) {
                    if (discardError || mergeError || words.isEmpty()) return null;
                    mergeError = true;
                    index = end;
                    continue;
                }
            }
            if (value == '&') {
                if (index + 1 >= command.length() || command.charAt(index + 1) != '&') return null;
                if (!appendSegment(segments, operators, words, discardError, mergeError, "&&")) return null;
                words = new ArrayList<>();
                discardError = false;
                mergeError = false;
                index += 2;
                continue;
            }
            if (value == '|') {
                if (index + 1 < command.length() && command.charAt(index + 1) == '|') {
                    return null;
                }
                if (!appendSegment(segments, operators, words, discardError, mergeError, "|")) return null;
                words = new ArrayList<>();
                discardError = false;
                mergeError = false;
                index++;
                continue;
            }
            if (value == ';') {
                if (!appendSegment(segments, operators, words, discardError, mergeError, ";")) return null;
                words = new ArrayList<>();
                discardError = false;
                mergeError = false;
                index++;
                continue;
            }
            if ("<>\\".indexOf(value) >= 0) return null;
            var word = new StringBuilder();
            char quote = 0;
            while (index < command.length()) {
                char next = command.charAt(index);
                if (quote != 0) {
                    if (next == quote) { quote = 0; index++; continue; }
                    if (Character.isISOControl(next) || "\\`$%!(){}^#".indexOf(next) >= 0) return null;
                    word.append(next);
                    index++;
                    continue;
                }
                if (next == '\'' || next == '"') { quote = next; index++; continue; }
                if (Character.isWhitespace(next) || "|;&<>\\".indexOf(next) >= 0) break;
                word.append(next);
                index++;
            }
            if (quote != 0 || word.isEmpty() || word.length() > 512 || ++wordCount > MAX_WORDS) return null;
            words.add(word.toString());
        }
        if (!appendSegment(segments, operators, words, discardError, mergeError, null)) return null;
        if (segments.isEmpty() || segments.size() > MAX_SEGMENTS) return null;
        return new Plan(segments, operators);
    }

    private static boolean appendSegment(List<Segment> segments, List<String> operators,
                                         List<String> words, boolean discardError, boolean mergeError,
                                         String operator) {
        if (words.isEmpty() || segments.size() >= MAX_SEGMENTS) return false;
        segments.add(new Segment(words, discardError, mergeError));
        if (operator != null) operators.add(operator);
        return true;
    }

    private static int stderrNullEnd(String command, int index) {
        if (command.charAt(index) != '2' || index + 1 >= command.length() || command.charAt(index + 1) != '>')
            return -1;
        int cursor = index + 2;
        while (cursor < command.length() && (command.charAt(cursor) == ' ' || command.charAt(cursor) == '\t')) cursor++;
        for (String target : List.of("/dev/null", "NUL")) {
            if (command.regionMatches(true, cursor, target, 0, target.length())) {
                int end = cursor + target.length();
                if (end == command.length() || Character.isWhitespace(command.charAt(end))
                        || "|;&".indexOf(command.charAt(end)) >= 0) return end;
            }
        }
        return -1;
    }

    private static int stderrMergeEnd(String command, int index) {
        if (index + 3 >= command.length() || !command.regionMatches(index, "2>&1", 0, 4)) return -1;
        int end = index + 4;
        return end == command.length() || Character.isWhitespace(command.charAt(end))
                || "|;&".indexOf(command.charAt(end)) >= 0 ? end : -1;
    }

    static boolean safeSegment(List<String> words) {
        String command = join(words);
        if (words.getFirst().equals("git")) return ReadOnlyQuery.git(command) != null;
        return switch (words.getFirst()) {
            case "echo" -> words.size() >= 2 && words.subList(1, words.size()).stream()
                    .allMatch(value -> ECHO.matcher(value).matches());
            case "head", "tail" -> lineFilter(words);
            case "wc" -> wordCount(words);
            case "ls" -> directoryListing(words);
            case "pwd" -> words.size() == 1;
            case "rg" -> GitFilesQuery.parse(words) != null;
            default -> false;
        };
    }

    private static boolean lineFilter(List<String> words) {
        if (words.size() == 2 && words.get(1).matches("-[1-9][0-9]{0,4}")
                && Integer.parseInt(words.get(1).substring(1)) <= 10_000) return true;
        return words.size() == 3 && words.get(1).equals("-n")
                && NUMBER.matcher(words.get(2)).matches() && Integer.parseInt(words.get(2)) <= 10_000;
    }

    private static boolean directoryListing(List<String> words) {
        boolean arguments = false;
        int paths = 0;
        for (int index = 1; index < words.size(); index++) {
            String value = words.get(index);
            if (!arguments && value.startsWith("-") && value.length() > 1
                    && value.substring(1).chars().allMatch(code -> "1adl".indexOf(code) >= 0)) continue;
            if (safeRelativePath(value) || value.equals("*/")) {
                arguments = true;
                if (++paths > 32) return false;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean wordCount(List<String> words) {
        if (words.size() < 2 || !Set.of("-l", "-c").contains(words.get(1))) return false;
        if (words.size() == 2) return words.get(1).equals("-l");
        if (words.size() > 34) return false;
        return words.subList(2, words.size()).stream().allMatch(ReviewableShell::safeRelativePath);
    }

    static boolean safeRelativePath(String value) {
        if (value.equals(".")) return true;
        if (value.isBlank() || value.length() > 512 || value.startsWith("-") || value.startsWith("~")
                || value.startsWith("/") || value.startsWith("\\")
                || value.contains(":") || value.chars().anyMatch(code -> ";&<>|`$%!\"'*?[]{}".indexOf(code) >= 0))
            return false;
        for (String part : value.replace('\\', '/').split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.equals("..") || lower.equals(".git") || lower.equals(".harness")
                    || part.endsWith(".") && !part.equals(".") || part.endsWith(" "))
                return false;
        }
        return true;
    }

    static String join(List<String> words) {
        return words.stream().map(word -> word.indexOf(' ') >= 0 ? "\"" + word + "\"" : word)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /**
     * 参考项目“结构可解析后再交给分类器”的兜底：只做确定性硬拦截，
     * 不把不认识的只读查询永久锁死。通过后仍必须由无工具分类器批准。
     */
    static boolean reviewCandidate(String command) {
        if (command == null || command.isBlank() || command.length() > MAX_CHARS
                || command.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT
                || Character.getType(code) == Character.LINE_SEPARATOR
                || Character.getType(code) == Character.PARAGRAPH_SEPARATOR)
                || command.indexOf('`') >= 0 || command.indexOf('$') >= 0
                || command.indexOf('%') >= 0 || command.indexOf('!') >= 0
                || command.indexOf('(') >= 0 || command.indexOf(')') >= 0
                || command.indexOf('{') >= 0 || command.indexOf('}') >= 0
                || command.indexOf('^') >= 0 || command.indexOf('＆') >= 0
                || command.indexOf('；') >= 0 || command.indexOf('｜') >= 0
                || command.indexOf('＜') >= 0 || command.indexOf('＞') >= 0) return false;
        Plan plan = syntax(command);
        if (plan == null) return false;
        List<List<String>> segments = plan.segments().stream().map(Segment::words).toList();
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.contains(".env") || lower.contains("id_rsa") || lower.contains(".ssh")
                || lower.contains(".aws") || lower.contains("credentials")
                || lower.contains(".git/") || lower.contains(".harness/")
                || lower.contains("/etc/") || lower.contains("/proc/")
                || lower.contains("--output")) return false;
        for (List<String> words : segments) {
            if (words.isEmpty()) return false;
            for (String word : words) {
                if (word.startsWith("/") || word.startsWith("\\")
                        || word.equals("..") || word.startsWith("../") || word.startsWith("..\\")
                        || word.matches("[A-Za-z]:.*") || word.indexOf('*') >= 0 || word.indexOf('?') >= 0)
                    return false;
                for (String part : word.replace('\\', '/').split("/", -1)) {
                    String lowerPart = part.toLowerCase(Locale.ROOT);
                    if (lowerPart.equals("..") || lowerPart.equals(".git") || lowerPart.equals(".harness")
                            || part.endsWith(".") && !part.equals("."))
                        return false;
                }
            }
            String name = executableName(words.getFirst());
            if (DANGEROUS_COMMANDS.contains(name)) return false;
            if (!words.getFirst().matches("[A-Za-z][A-Za-z0-9_-]*")) return false;
            // 已知命令的路径/参数安全失败不能经 AI 兜底洗成可执行计划。
            if (Set.of("ls", "wc", "head", "tail", "echo").contains(name) && !safeSegment(words)) return false;
            if (name.equals("git") && words.size() > 1 && words.get(1).equals("diff")
                    && ReadOnlyQuery.git(join(words)) == null) return false;
            if (name.equals("git") && !safeClassifierGit(words)) return false;
            if (name.equals("find") && words.stream().anyMatch(word -> Set.of(
                    "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprintf", "-fls",
                    "-L", "-H", "-follow").contains(word)))
                return false;
            if (name.equals("sed") && words.stream().anyMatch(word -> word.equals("-i")
                    || word.startsWith("-i") || word.equals("--in-place"))) return false;
            if (name.equals("rg") && words.stream().anyMatch(word -> word.equals("--pre")
                    || word.startsWith("--pre=") || word.equals("--pre-glob")
                    || word.startsWith("--pre-glob=") || word.equals("--follow")
                    || word.equals("--search-zip"))) return false;
            String executable = words.getFirst();
            if (executable.startsWith(".") || executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0)
                return false;
        }
        return true;
    }



    private static String executableName(String value) {
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static boolean safeClassifierGit(List<String> words) {
        if (words.size() < 2) return false;
        String operation = words.get(1).toLowerCase(Locale.ROOT);
        if (!Set.of("show", "grep", "ls-files", "rev-parse", "blame", "shortlog",
                "status", "log", "diff",
                "describe", "cat-file", "for-each-ref").contains(operation)) return false;
        return words.stream().noneMatch(word -> word.equals("-C") || word.equals("-c")
                || word.startsWith("--git-dir") || word.startsWith("--work-tree")
                || word.startsWith("--output") || word.startsWith("--open-files-in-pager")
                || word.equals("-O") || word.equals("--ext-diff") || word.equals("--textconv"));
    }

    private ReviewableShell() {}
}
