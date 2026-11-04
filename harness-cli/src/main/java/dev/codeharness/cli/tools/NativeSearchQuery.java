package dev.codeharness.cli.tools;

import com.google.re2j.Pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** 将受限 rg 语法解释为纯查询数据；没有文件系统访问，也不会启动命令或 shell。 */
public final class NativeSearchQuery {
    private static final int MAX_COMMAND_CHARS = 4096;
    private static final int MAX_GLOBS = 64;
    private static final int MAX_PATHS = 32;
    private static final int MAX_PATTERNS = 16;
    private static final int MAX_PATTERN_CHARS = 2048;

    private NativeSearchQuery() {}

    public record Spec(boolean files, boolean hidden, boolean lineNumbers, boolean ignoreCase,
                       boolean fixedStrings, List<String> patterns, List<String> globs,
                       List<String> paths, int maxResults, String stripPrefix) {
        public Spec {
            patterns = List.copyOf(patterns);
            globs = List.copyOf(globs);
            paths = List.copyOf(paths);
            if (patterns.size() > MAX_PATTERNS || globs.size() > MAX_GLOBS || paths.isEmpty()
                    || paths.size() > MAX_PATHS || maxResults < 1 || maxResults > 1000
                    || (files ? !patterns.isEmpty() || lineNumbers || ignoreCase || fixedStrings : patterns.isEmpty())
                    || stripPrefix != null && !files)
                throw new IllegalArgumentException("原生搜索参数超出范围");
            for (String pattern : patterns) {
                if (pattern.length() > MAX_PATTERN_CHARS || hasControl(pattern))
                    throw new IllegalArgumentException("搜索模式过长或包含控制字符");
                compileLine(pattern, ignoreCase, fixedStrings);
            }
            for (String glob : globs) validateGlob(glob);
            for (String path : paths) {
                if (!path.equals(normalizePath(path))) throw new IllegalArgumentException("查询路径必须规范化");
            }
        }

        /** 文件内容逐行查询使用此实例，避免每读取一行都重新编译正则。 */
        public Predicate<String> lineMatcher() {
            List<Pattern> compiled = patterns.stream().map(value -> compileLine(value, ignoreCase, fixedStrings)).toList();
            return line -> compiled.stream().anyMatch(pattern -> pattern.matcher(line).find());
        }

        public boolean matchesLine(String line) { return lineMatcher().test(line); }

        /** 返回可供整个遍历复用的匹配器；目录不按正向 glob 剪枝，以免漏掉其后代。 */
        public BiPredicate<String, Boolean> pathMatcher() {
            GlobRules rules = new GlobRules(globs);
            return (relative, directory) -> !rules.isExcluded(relative, directory)
                    && (directory || rules.includes.isEmpty() || rules.includes.stream().anyMatch(glob -> glob.matches(relative)));
        }

        public boolean matchesPath(String relative, boolean directory) { return pathMatcher().test(relative, directory); }

        public BiPredicate<String, Boolean> exclusionMatcher() {
            GlobRules rules = new GlobRules(globs);
            return rules::isExcluded;
        }

        public boolean isExcluded(String relative, boolean directory) { return exclusionMatcher().test(relative, directory); }
    }

    /** 不支持的语法返回 null，不把解析失败降级成外部程序执行。 */
    public static Spec parse(String command) {
        if (command == null || command.isBlank() || command.length() > MAX_COMMAND_CHARS || hasControl(command)) return null;
        try {
            List<Token> tokens = tokenize(command);
            if (tokens.isEmpty() || (!tokens.getFirst().text.equalsIgnoreCase("rg")
                    && !tokens.getFirst().text.equalsIgnoreCase("rg.exe"))) return null;
            int end = tokens.size(), limit = 1000;
            String stripPrefix = null;
            var pipeline = new ArrayList<List<Token>>();
            var current = new ArrayList<Token>();
            boolean firstPipe = true;
            for (int index = 0; index < tokens.size(); index++) {
                if (tokens.get(index).pipe) {
                    if (current.isEmpty()) return null;
                    if (firstPipe) {
                        end = index;
                        firstPipe = false;
                    } else pipeline.add(List.copyOf(current));
                    current = new ArrayList<>();
                } else current.add(tokens.get(index));
            }
            if (end != tokens.size()) {
                if (current.isEmpty()) return null;
                pipeline.add(List.copyOf(current));
            }
            for (List<Token> stage : pipeline) {
                if (stage.getFirst().text.equals("head")) {
                    if (limit != 1000) return null;
                    limit = parseHead(stage);
                } else if (stage.getFirst().text.equals("sed")) {
                    if (stripPrefix != null) return null;
                    stripPrefix = parsePrefixStrip(stage);
                    if (stripPrefix == null) return null;
                } else return null;
            }
            List<String> patterns = new ArrayList<>(), globs = new ArrayList<>(), positional = new ArrayList<>();
            boolean files = false, hidden = false, lineNumbers = false, ignoreCase = false, fixed = false, options = true;
            for (int index = 1; index < end; index++) {
                String word = tokens.get(index).text;
                if (options && word.equals("--")) { options = false; continue; }
                if (!options || !word.startsWith("-") || word.equals("-")) { positional.add(word); continue; }
                switch (word) {
                    case "--files": files = true; break;
                    case "--hidden": hidden = true; break;
                    case "--line-number": lineNumbers = true; break;
                    case "--ignore-case": ignoreCase = true; break;
                    case "--fixed-strings": fixed = true; break;
                    case "--regexp", "--glob":
                        if (++index >= end) return null;
                        (word.equals("--regexp") ? patterns : globs).add(tokens.get(index).text);
                        break;
                    default:
                        if (word.startsWith("--regexp=")) patterns.add(word.substring("--regexp=".length()));
                        else if (word.startsWith("--glob=")) globs.add(word.substring("--glob=".length()));
                        else {
                            if (word.startsWith("--")) return null;
                            for (int part = 1; part < word.length(); part++) {
                                char option = word.charAt(part);
                                switch (option) {
                                    case 'n': lineNumbers = true; break;
                                    case 'i': ignoreCase = true; break;
                                    case 'F': fixed = true; break;
                                    case 'e', 'g':
                                        String value;
                                        if (part + 1 < word.length()) value = word.substring(part + 1);
                                        else {
                                            if (++index >= end) return null;
                                            value = tokens.get(index).text;
                                        }
                                        (option == 'e' ? patterns : globs).add(value);
                                        part = word.length();
                                        break;
                                    default: return null;
                                }
                            }
                        }
                        break;
                }
            }
            if (!files && patterns.isEmpty()) {
                if (positional.isEmpty()) return null;
                patterns.add(positional.removeFirst());
            }
            List<String> paths = positional.isEmpty() ? List.of(".") : positional.stream().map(NativeSearchQuery::normalizePath).toList();
            return new Spec(files, hidden, lineNumbers, ignoreCase, fixed, patterns, globs, paths,
                    limit, stripPrefix);
        } catch (IllegalArgumentException error) { return null; }
    }

    private record Token(String text, boolean pipe) {}

    private static List<Token> tokenize(String command) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            // 对三种常见 shell 的展开语法统一拒绝；正则的行末 $ 锚点仍可使用。
            if (character == '`' || character == '$' && index + 1 < command.length()
                    && (Character.isLetterOrDigit(command.charAt(index + 1)) || "_({[?@*!#-$".indexOf(command.charAt(index + 1)) >= 0)
                    || (character == '%' || character == '!') && pairedExpansion(command, index, character))
                throw new IllegalArgumentException("不支持变量或命令展开");
            if (quote != 0) {
                if (character == quote) quote = 0;
                else word.append(character);
                continue;
            }
            if (character == '\'' || character == '"') { quote = character; started = true; }
            else if (character == ' ') {
                if (started) { tokens.add(new Token(word.toString(), false)); word.setLength(0); started = false; }
            } else if (character == '|') {
                if (started) { tokens.add(new Token(word.toString(), false)); word.setLength(0); started = false; }
                tokens.add(new Token("|", true));
            } else if (";&<>()".indexOf(character) >= 0) throw new IllegalArgumentException("不支持命令串接或重定向");
            else { word.append(character); started = true; }
        }
        if (quote != 0) throw new IllegalArgumentException("引号未闭合");
        if (started) tokens.add(new Token(word.toString(), false));
        return tokens;
    }

    private static boolean pairedExpansion(String command, int index, char delimiter) {
        for (int next = index + 1; next < command.length(); next++) {
            char character = command.charAt(next);
            if (character == delimiter) return true;
            if (character == ' ' || character == '\'' || character == '"') return false;
        }
        return false;
    }

    private static int parseHead(List<Token> tokens) {
        if (tokens.stream().anyMatch(Token::pipe) || tokens.isEmpty() || !tokens.getFirst().text.equals("head"))
            throw new IllegalArgumentException("只支持单个 head 输出阶段");
        String value;
        if (tokens.size() == 3 && tokens.get(1).text.equals("-n")) value = tokens.get(2).text;
        else if (tokens.size() == 2 && tokens.get(1).text.startsWith("--lines=")) value = tokens.get(1).text.substring(8);
        else if (tokens.size() == 2 && tokens.get(1).text.startsWith("-")) value = tokens.get(1).text.substring(1);
        else throw new IllegalArgumentException("不支持该 head 参数");
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9'))
            throw new IllegalArgumentException("head 行数必须为正整数");
        int limit = Integer.parseInt(value);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("head 行数超出范围");
        return limit;
    }

    /** 只接受不读取文件的 s#prefix##，且 Spec 会进一步限制为 rg --files。 */
    private static String parsePrefixStrip(List<Token> tokens) {
        if (tokens.size() != 2 || !tokens.get(1).text.startsWith("s#")
                || !tokens.get(1).text.endsWith("##")) return null;
        String prefix = tokens.get(1).text.substring(2, tokens.get(1).text.length() - 2);
        if (prefix.isEmpty() || prefix.indexOf('#') >= 0 || hasControl(prefix)) return null;
        String normalized = prefix.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("~") || normalized.contains(":")
                || normalized.contains("*") || normalized.contains("?")
                || normalized.chars().anyMatch(value -> ";&<>|".indexOf(value) >= 0))
            return null;
        for (String part : normalized.split("/", -1)) if (part.equals("..")) return null;
        return normalized;
    }

    private static String normalizePath(String input) {
        if (input.isEmpty() || hasControl(input)) throw new IllegalArgumentException("查询路径不能为空");
        String path = input.replace('\\', '/');
        if (path.startsWith("/") || path.startsWith("~") || path.contains(":") || path.contains("*") || path.contains("?")
                || path.chars().anyMatch(value -> ";&<>|".indexOf(value) >= 0))
            throw new IllegalArgumentException("查询路径必须位于当前工作区");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/", -1)) {
            if (part.equals("..")) throw new IllegalArgumentException("查询路径不能包含父目录");
            if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return parts.isEmpty() ? "." : String.join("/", parts);
    }

    private static boolean hasControl(String text) {
        return text.codePoints().anyMatch(value -> Character.isISOControl(value) || Character.getType(value) == Character.FORMAT);
    }

    private static Pattern compileLine(String value, boolean ignoreCase, boolean fixed) {
        try { return Pattern.compile(fixed ? Pattern.quote(value) : value, ignoreCase ? Pattern.CASE_INSENSITIVE : 0); }
        catch (com.google.re2j.PatternSyntaxException ignored) {
            throw new IllegalArgumentException("搜索正则无效或使用了不支持的特性");
        }
    }

    private static void validateGlob(String glob) {
        Objects.requireNonNull(glob, "glob");
        String value = glob.startsWith("!") ? glob.substring(1) : glob;
        if (value.isEmpty() || hasControl(value) || value.startsWith("/") || value.startsWith("\\")
                || value.contains(":") || value.indexOf('\\') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0
                || value.chars().anyMatch(character -> ";&<>|".indexOf(character) >= 0)
                || value.indexOf('{') >= 0 || value.indexOf('}') >= 0)
            throw new IllegalArgumentException("仅支持工作区内的 *、? 和 ** 文件模式");
        for (String part : value.split("/", -1)) {
            if (part.equals("..")) throw new IllegalArgumentException("文件模式不能越过工作区");
        }
    }

    private record Glob(Pattern pattern, boolean basename, String value) {
        Glob(String value) { this(Pattern.compile(globRegex(value)), value.indexOf('/') < 0, value); }

        boolean matches(String relative) {
            String normalized = relative.replace('\\', '/');
            String subject = basename ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            return pattern.matcher(subject).matches();
        }

        boolean excludes(String relative, boolean directory) {
            String path = relative.replace('\\', '/');
            if (matches(path)) return true;
            // 否定目录匹配继续作用于其所有后代，包括调用者显式选择目录里的单个文件时。
            for (int index = path.lastIndexOf('/'); index >= 0; index = path.lastIndexOf('/', index - 1)) {
                if (matches(path.substring(0, index))) return true;
            }
            return directory && value.endsWith("/**")
                    && Pattern.compile(globRegex(value.substring(0, value.length() - 3))).matcher(path).matches();
        }
    }

    private static final class GlobRules {
        final List<Glob> includes = new ArrayList<>(), excludes = new ArrayList<>();
        GlobRules(List<String> globs) {
            for (String glob : globs) (glob.startsWith("!") ? excludes : includes).add(new Glob(glob.startsWith("!") ? glob.substring(1) : glob));
        }
        boolean isExcluded(String relative, boolean directory) { return excludes.stream().anyMatch(glob -> glob.excludes(relative, directory)); }
    }

    /** glob 仅在文件名上匹配，不交给操作系统展开；RE2 保证模式执行没有灾难性回溯。 */
    private static String globRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < glob.length(); index++) {
            char character = glob.charAt(index);
            if (character == '*') {
                if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                    index++;
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') { regex.append("(?:.*/)?"); index++; }
                    else regex.append(".*");
                } else regex.append("[^/]*");
            } else if (character == '?') regex.append("[^/]");
            else regex.append(Pattern.quote(String.valueOf(character)));
        }
        return regex.toString();
    }
}
