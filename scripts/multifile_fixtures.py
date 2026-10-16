"""纯数据 Java 修复任务：有缺陷版本及每个遗漏修改的对照都必须失败。"""

TASKS = [
    {
        'id': 'rename-public-api',
        'prompt': '将 Counter.total 重命名为 Counter.sum，移除旧的公开方法，并更新依赖它的 Report.amount 调用点。保持整数加法行为。',
        'sources': {
            'src/Counter.java': '''public final class Counter {
    private Counter() {}
    public static int total(int first, int second) { return first + second; }
}
''',
            'src/Report.java': '''public final class Report {
    private Report() {}
    public static int amount(int first, int second) { return Counter.total(first, second); }
}
''',
        },
        'edits': [
            {'path': 'src/Counter.java', 'old_text': 'total(int first, int second)', 'new_text': 'sum(int first, int second)'},
            {'path': 'src/Report.java', 'old_text': 'Counter.total(first, second)', 'new_text': 'Counter.sum(first, second)'},
        ],
        'checks': '''public class Check {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(Counter.sum(7, 8) == 15);
        check(Counter.sum(-7, 8) == 1);
        check(Counter.sum(0, 0) == 0);
        check(Report.amount(7, 8) == 15);
        check(Report.amount(-20, 3) == -17);
        for (var method : Counter.class.getDeclaredMethods()) check(!method.getName().equals("total"));
        System.out.println("CHECKS_PASS");
    }
}
''',
    },
    {
        'id': 'overflow-safe-invoice',
        'prompt': '将 Invoice.totalCents(int[]) 改为返回 long，累加有符号分金额时不能发生 int 溢出。更新 InvoicePrinter.print，格式化十进制金额时保留完整总额。空发票总额为零，继续支持负数调整。',
        'sources': {
            'src/Invoice.java': '''public final class Invoice {
    private Invoice() {}
    public static int totalCents(int[] lineItems) {
        int total = 0;
        for (int item : lineItems) total += item;
        return total;
    }
}
''',
            'src/InvoicePrinter.java': '''public final class InvoicePrinter {
    private InvoicePrinter() {}
    public static String print(int[] lineItems) {
        int total = Invoice.totalCents(lineItems);
        return total + " cents";
    }
}
''',
        },
        'edits': [
            {'path': 'src/Invoice.java', 'old_text': 'public static int totalCents(int[] lineItems)', 'new_text': 'public static long totalCents(int[] lineItems)'},
            {'path': 'src/Invoice.java', 'old_text': 'int total = 0;', 'new_text': 'long total = 0L;'},
            {'path': 'src/InvoicePrinter.java', 'old_text': 'int total = Invoice.totalCents(lineItems);', 'new_text': 'long total = Invoice.totalCents(lineItems);'},
        ],
        'checks': '''public class Check {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        check(Invoice.class.getMethod("totalCents", int[].class).getReturnType() == long.class);
        int[][] cases = {{}, {123, 456}, {Integer.MAX_VALUE, 1},
                         {Integer.MIN_VALUE, -1}, {Integer.MAX_VALUE, Integer.MAX_VALUE, -5},
                         {500, -750, 250}};
        long[] expected = {0L, 579L, 2147483648L, -2147483649L, 4294967289L, 0L};
        for (int i = 0; i < cases.length; i++) {
            int[] before = cases[i].clone();
            check(Invoice.totalCents(cases[i]) == expected[i]);
            check(InvoicePrinter.print(cases[i]).equals(expected[i] + " cents"));
            check(java.util.Arrays.equals(before, cases[i]));
        }
        System.out.println("CHECKS_PASS");
    }
}
''',
    },
    {
        'id': 'literal-delimiter-empty-fields',
        'prompt': '让 RecordParser.parse(input, delimiter) 将每个非空分隔符按字面量处理，并保留末尾空字段。更新 RecordValidator.valid，仅接受恰好三个非空字段；拒绝字段缺失、过多，以及首部、中间或末尾的空字段。含空白字符的字段仍有效，不要修剪内容。',
        'sources': {
            'src/RecordParser.java': '''public final class RecordParser {
    private RecordParser() {}
    public static String[] parse(String input, String delimiter) {
        if (delimiter.isEmpty()) throw new IllegalArgumentException("Empty delimiter");
        return input.split(delimiter);
    }
}
''',
            'src/RecordValidator.java': '''public final class RecordValidator {
    private RecordValidator() {}
    public static boolean valid(String input, String delimiter) {
        String[] fields = RecordParser.parse(input, delimiter);
        return fields.length >= 3 && !fields[0].isEmpty();
    }
}
''',
        },
        'edits': [
            {'path': 'src/RecordParser.java', 'old_text': 'return input.split(delimiter);', 'new_text': 'return input.split(java.util.regex.Pattern.quote(delimiter), -1);'},
            {'path': 'src/RecordValidator.java', 'old_text': 'return fields.length >= 3 && !fields[0].isEmpty();', 'new_text': 'return fields.length == 3 && java.util.Arrays.stream(fields).noneMatch(String::isEmpty);'},
        ],
        'checks': '''public class Check {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static void parsed(String input, String delimiter, String... expected) {
        check(java.util.Arrays.equals(expected, RecordParser.parse(input, delimiter)));
    }
    public static void main(String[] args) {
        parsed("a|b|", "|", "a", "b", "");
        parsed("a.b..", ".", "a", "b", "", "");
        parsed("[a[b[", "[", "", "a", "b", "");
        parsed("first::second::", "::", "first", "second", "");
        parsed("", "|", "");
        parsed("single", ".", "single");
        check(RecordValidator.valid("a|b|c", "|"));
        check(RecordValidator.valid("a.b.c", "."));
        check(RecordValidator.valid("a:: ::c", "::"));
        for (String input : new String[]{"a|b|", "|b|c", "a||b", "a||c", "a|b", "a|b|c|d", ""}) {
            check(!RecordValidator.valid(input, "|"));
        }
        boolean rejected = false;
        try { RecordParser.parse("abc", ""); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected);
        System.out.println("CHECKS_PASS");
    }
}
''',
    },
]

# 每个工作区都含有无关源码，其字节必须保持不变。
for task in TASKS:
    task['sources']['src/BuildIdentity.java'] = (
        'public final class BuildIdentity {\n'
        '    private BuildIdentity() {}\n'
        '    public static String name() { return "fixture-v1"; }\n'
        '}\n')
