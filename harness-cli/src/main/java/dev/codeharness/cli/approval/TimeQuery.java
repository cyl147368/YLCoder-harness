package dev.codeharness.cli.approval;

import java.util.Map;

/** 解析有限的只读时间语法；所有执行表达式由程序生成，拒绝 shell 拼接与时间设置。 */
final class TimeQuery {
    private static final Map<String, String> FORMATS = Map.of(
            "+%Y-%m-%d %H:%M:%S", "yyyy-MM-dd HH:mm:ss",
            "+%Y-%m-%d", "yyyy-MM-dd",
            "+%H:%M:%S", "HH:mm:ss",
            "+%Y-%m-%d %H:%M:%S %z", "yyyy-MM-dd HH:mm:ss zzz",
            "+%Y-%m-%d %H:%M:%S %Z (%z)", "yyyy-MM-dd HH:mm:ss zzz");
    record Query(String dateFormat, String windowsExpression, boolean powerShell) { }

    static Query parse(String command) {
        if (command.equals("date")) return new Query("", now("yyyy-MM-dd HH:mm:ss zzz"), false);
        if (command.startsWith("date ")) {
            String format = unquote(command.substring(5));
            String windows = FORMATS.get(format);
            if (windows == null) return null;
            String expression = now(windows);
            // POSIX 的 %z 没有冒号；%Z 在 Windows 上使用系统时区 ID。
            if (format.equals("+%Y-%m-%d %H:%M:%S %z"))
                expression = now("yyyy-MM-dd HH:mm:ss") + " + ' ' + " + now("zzz") + ".Replace(':','')";
            if (format.equals("+%Y-%m-%d %H:%M:%S %Z (%z)"))
                expression = now("yyyy-MM-dd HH:mm:ss") + " + ' ' + [TimeZoneInfo]::Local.Id + ' (' + "
                        + now("zzz") + ".Replace(':','') + ')'";
            return new Query(format, expression, false);
        }
        String body = command;
        var wrapper = java.util.regex.Pattern.compile("(?i)^powershell(?:\\.exe)? -NoProfile -Command (.+)$").matcher(command);
        if (wrapper.matches()) body = unquote(wrapper.group(1));
        if (body.equalsIgnoreCase("Get-Date")) return new Query("", now("yyyy-MM-dd HH:mm:ss zzz"), true);
        var formatQuery = java.util.regex.Pattern.compile("(?i)^Get-Date -Format (.+)$").matcher(body);
        if (!formatQuery.matches()) return null;
        String format = unquote(formatQuery.group(1));
        if (!java.util.Set.of("yyyy-MM-dd HH:mm:ss zzz", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "HH:mm:ss", "o", "s", "u").contains(format))
            return null;
        // -Format u 的 UTC 含义由明确的 UtcNow 实现；其余按本地时区读取。
        String expression = format.equals("u") ? "[DateTimeOffset]::UtcNow.ToString('u',[Globalization.CultureInfo]::InvariantCulture)" : now(format);
        return new Query("", expression, true);
    }

    private static String now(String format) {
        return "[DateTimeOffset]::Now.ToString('" + format + "',[Globalization.CultureInfo]::InvariantCulture)";
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                && value.charAt(value.length() - 1) == value.charAt(0)) return value.substring(1, value.length() - 1);
        return value;
    }
}
