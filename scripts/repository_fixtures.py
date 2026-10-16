"""确定性的微型 Java 仓库；参考修复仅供验证器使用。"""

CHECKOUT_SOURCES = {
    'src/shop/CartLine.java': '''package shop;

public record CartLine(String sku, int quantity) {
    public CartLine {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU is required");
        }
        if (quantity < 0 || quantity > 1000) {
            throw new IllegalArgumentException("Quantity must be 1..1000");
        }
    }
}
''',
    'src/shop/Catalog.java': '''package shop;

import java.util.Map;

public final class Catalog {
    private final Map<String, Long> prices;

    public Catalog(Map<String, Long> prices) {
        this.prices = Map.copyOf(prices);
        for (var entry : this.prices.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue() < 0) {
                throw new IllegalArgumentException("Invalid catalog price");
            }
        }
    }

    public long unitCents(String sku) {
        Long value = prices.get(sku);
        if (value == null) {
            throw new IllegalArgumentException("Unknown SKU: " + sku);
        }
        return value;
    }
}
''',
    'src/shop/DiscountPolicy.java': '''package shop;

public final class DiscountPolicy {
    private final long thresholdCents;
    private final int percent;

    public DiscountPolicy(long thresholdCents, int percent) {
        if (thresholdCents < 0 || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Invalid discount policy");
        }
        this.thresholdCents = thresholdCents;
        this.percent = percent;
    }

    public long discount(long subtotal) {
        if (subtotal < 0) {
            throw new IllegalArgumentException("Negative subtotal");
        }
        if (subtotal <= thresholdCents) {
            return 0;
        }
        return (subtotal / 100) * percent;
    }
}
''',
    'src/shop/PricedLine.java': '''package shop;

public record PricedLine(String sku, int quantity, long unitCents, long totalCents) {
    public PricedLine {
        if (quantity < 1 || unitCents < 0 || totalCents < 0) {
            throw new IllegalArgumentException("Invalid priced line");
        }
    }

    public String description() {
        return sku + " x" + quantity;
    }
}
''',
    'src/shop/PriceCalculator.java': '''package shop;

import java.util.ArrayList;
import java.util.List;

public final class PriceCalculator {
    private final Catalog catalog;

    public PriceCalculator(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<PricedLine> price(List<CartLine> cart) {
        List<PricedLine> result = new ArrayList<>();
        for (CartLine item : cart) {
            long unit = catalog.unitCents(item.sku());
            long lineTotal = (int) unit * item.quantity();
            result.add(new PricedLine(item.sku(), item.quantity(), unit, lineTotal));
        }
        return List.copyOf(result);
    }

    public long subtotal(List<PricedLine> lines) {
        long total = 0;
        for (PricedLine line : lines) {
            total = Math.addExact(total, line.totalCents());
        }
        return total;
    }
}
''',
    'src/shop/Receipt.java': '''package shop;

import java.util.List;

public record Receipt(List<PricedLine> lines, long subtotalCents,
                      long discountCents, long totalCents) {
    public Receipt {
        lines = List.copyOf(lines);
        if (subtotalCents < 0 || discountCents < 0 || totalCents < 0
                || discountCents > subtotalCents
                || totalCents != subtotalCents - discountCents) {
            throw new IllegalArgumentException("Inconsistent receipt");
        }
    }

    public int itemCount() {
        return lines.stream().mapToInt(PricedLine::quantity).sum();
    }
}
''',
    'src/shop/CheckoutService.java': '''package shop;

import java.util.List;

public final class CheckoutService {
    private final PriceCalculator calculator;
    private final DiscountPolicy discounts;

    public CheckoutService(Catalog catalog, DiscountPolicy discounts) {
        this.calculator = new PriceCalculator(catalog);
        this.discounts = discounts;
    }

    public Receipt checkout(List<CartLine> cart) {
        List<PricedLine> priced = calculator.price(List.copyOf(cart));
        long subtotal = calculator.subtotal(priced);
        long discount = discounts.discount(subtotal);
        return new Receipt(priced, subtotal, discount, subtotal - discount);
    }
}
''',
    'src/shop/ReceiptRenderer.java': '''package shop;

public final class ReceiptRenderer {
    public String render(Receipt receipt) {
        StringBuilder output = new StringBuilder();
        for (PricedLine line : receipt.lines()) {
            output.append(line.description()).append(" = ")
                    .append(money(line.totalCents())).append('\\n');
        }
        output.append("Subtotal: ").append(money(receipt.subtotalCents())).append('\\n');
        output.append("Discount: ").append(money(receipt.discountCents())).append('\\n');
        output.append("Total: ").append(money(receipt.totalCents())).append('\\n');
        return output.toString();
    }

    public String money(long cents) {
        if (cents < 0) {
            throw new IllegalArgumentException("Negative money");
        }
        return cents / 100 + "." + cents % 100;
    }
}
''',
    'src/shop/BuildInfo.java': '''package shop;

public final class BuildInfo {
    private BuildInfo() {}

    public static String applicationName() {
        return "Mini checkout";
    }

    public static int receiptFormatVersion() {
        return 1;
    }
}
''',
}

INGEST_SOURCES = {
    'src/ingest/InputRow.java': '''package ingest;

public record InputRow(int number, String text) {
    public InputRow {
        if (number < 1 || text == null) {
            throw new IllegalArgumentException("Invalid input row");
        }
    }
}
''',
    'src/ingest/ParsedRecord.java': '''package ingest;

public record ParsedRecord(String id, String label, long cents) {
    public ParsedRecord {
        if (id == null || label == null || cents < 0) {
            throw new IllegalArgumentException("Invalid record");
        }
    }
}
''',
    'src/ingest/ValidationIssue.java': '''package ingest;

public record ValidationIssue(int line, String reason) {
    public ValidationIssue {
        if (line < 1 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Invalid issue");
        }
    }
}
''',
    'src/ingest/Tokenizer.java': '''package ingest;

public final class Tokenizer {
    public String[] fields(String input, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("Delimiter required");
        }
        if (input == null) {
            throw new IllegalArgumentException("Input required");
        }
        return input.split(delimiter);
    }
}
''',
    'src/ingest/RecordParser.java': '''package ingest;

import java.math.BigDecimal;

public final class RecordParser {
    private final Tokenizer tokenizer = new Tokenizer();

    public ParsedRecord parse(String text, String delimiter) {
        String[] fields = tokenizer.fields(text, delimiter);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Expected three fields");
        }
        if (!fields[0].matches("[A-Z][A-Z0-9]{2,11}")) {
            throw new IllegalArgumentException("Invalid ID");
        }
        if (fields[1].isBlank()) {
            throw new IllegalArgumentException("Label required");
        }
        if (!fields[2].matches("[0-9]+(?:\\\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Invalid decimal amount");
        }
        BigDecimal amount = new BigDecimal(fields[2]);
        long cents = amount.longValueExact() * 100;
        return new ParsedRecord(fields[0], fields[1], cents);
    }
}
''',
    'src/ingest/BatchAccumulator.java': '''package ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BatchAccumulator {
    private final Map<String, ParsedRecord> records = new LinkedHashMap<>();
    private long total;

    public void add(ParsedRecord record) {
        long updated = Math.addExact(total, record.cents());
        records.put(record.id(), record);
        total = updated;
    }

    public List<ParsedRecord> records() {
        return List.copyOf(new ArrayList<>(records.values()));
    }

    public long totalCents() {
        return total;
    }
}
''',
    'src/ingest/ImportReport.java': '''package ingest;

import java.util.List;

public record ImportReport(List<ParsedRecord> records,
                           List<ValidationIssue> issues, long totalCents) {
    public ImportReport {
        records = List.copyOf(records);
        issues = List.copyOf(issues);
        if (totalCents < 0) {
            throw new IllegalArgumentException("Negative report total");
        }
    }

    public int acceptedCount() {
        return records.size();
    }

    public int rejectedCount() {
        return issues.size();
    }
}
''',
    'src/ingest/IngestionService.java': '''package ingest;

import java.util.ArrayList;
import java.util.List;

public final class IngestionService {
    private final RecordParser parser = new RecordParser();

    public ImportReport ingest(List<InputRow> rows, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("Delimiter required");
        }
        BatchAccumulator accepted = new BatchAccumulator();
        List<ValidationIssue> issues = new ArrayList<>();
        for (InputRow row : rows) {
            try {
                ParsedRecord record = parser.parse(row.text(), delimiter);
                accepted.add(record);
            } catch (IllegalArgumentException | ArithmeticException rejected) {
                issues.add(new ValidationIssue(row.number(), "Invalid row"));
            }
        }
        return new ImportReport(accepted.records(), issues, accepted.totalCents());
    }
}
''',
    'src/ingest/ReportRenderer.java': '''package ingest;

public final class ReportRenderer {
    public String render(ImportReport report) {
        StringBuilder result = new StringBuilder();
        result.append("accepted=").append(report.acceptedCount())
                .append(";rejected=").append(report.rejectedCount())
                .append(";totalCents=").append(report.totalCents()).append('\\n');
        for (ParsedRecord record : report.records()) {
            result.append(record.id()).append('|').append(escape(record.label()))
                    .append('|').append(record.cents()).append('\\n');
        }
        for (ValidationIssue issue : report.issues()) {
            result.append("error@").append(issue.line()).append(':')
                    .append(issue.reason()).append('\\n');
        }
        return result.toString();
    }

    public String escape(String value) {
        return value.replace("|", "\\\\|");
    }
}
''',
    'src/ingest/BuildInfo.java': '''package ingest;

public final class BuildInfo {
    private BuildInfo() {}

    public static String applicationName() {
        return "Mini import";
    }

    public static int reportFormatVersion() {
        return 1;
    }
}
''',
}

CHECKOUT_CHECKS = '''import shop.*;
import java.util.*;

public class Check {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void rejected(Runnable action, Class<? extends Throwable> type) {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Unexpected exception", failure);
        }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        rejected(() -> new CartLine("A", 0), IllegalArgumentException.class);
        rejected(() -> new CartLine("A", -1), IllegalArgumentException.class);
        rejected(() -> new CartLine("A", 1001), IllegalArgumentException.class);
        rejected(() -> new CartLine(" ", 1), IllegalArgumentException.class);
        check(new CartLine("A", 1000).quantity() == 1000);
        var catalog = new Catalog(Map.of("A", 199L, "B", 1L, "HUGE", 3_000_000_000L));
        var calc = new PriceCalculator(catalog);
        var lines = calc.price(List.of(new CartLine("HUGE", 2), new CartLine("A", 3)));
        check(lines.get(0).totalCents() == 6_000_000_000L);
        check(calc.subtotal(lines) == 6_000_000_597L);
        rejected(() -> lines.clear(), UnsupportedOperationException.class);
        rejected(() -> calc.price(List.of(new CartLine("UNKNOWN", 1))), IllegalArgumentException.class);
        var overflowing = new PriceCalculator(new Catalog(Map.of("X", Long.MAX_VALUE)));
        rejected(() -> overflowing.price(List.of(new CartLine("X", 2))), ArithmeticException.class);
        rejected(() -> overflowing.subtotal(List.of(new PricedLine("X", 1, Long.MAX_VALUE, Long.MAX_VALUE),
                new PricedLine("Y", 1, 1, 1))), ArithmeticException.class);
        var discount = new DiscountPolicy(199, 50);
        check(discount.discount(198) == 0);
        check(discount.discount(199) == 99);
        check(discount.discount(201) == 100);
        check(new DiscountPolicy(0, 100).discount(Long.MAX_VALUE) == Long.MAX_VALUE);
        check(new DiscountPolicy(0, 1).discount(Long.MAX_VALUE) == Long.MAX_VALUE / 100);
        check(new DiscountPolicy(0, 0).discount(1) == 0);
        rejected(() -> discount.discount(-1), IllegalArgumentException.class);
        var input = new ArrayList<>(List.of(new CartLine("A", 1), new CartLine("B", 2)));
        var receipt = new CheckoutService(catalog, new DiscountPolicy(199, 50)).checkout(input);
        check(input.size() == 2 && input.get(0).quantity() == 1);
        check(receipt.subtotalCents() == 201 && receipt.discountCents() == 100 && receipt.totalCents() == 101);
        check(receipt.itemCount() == 3);
        input.clear(); check(receipt.lines().size() == 2);
        rejected(() -> receipt.lines().clear(), UnsupportedOperationException.class);
        var renderer = new ReceiptRenderer();
        check(renderer.money(0).equals("0.00"));
        check(renderer.money(1).equals("0.01"));
        check(renderer.money(10).equals("0.10"));
        check(renderer.money(101).equals("1.01"));
        check(renderer.money(Long.MAX_VALUE).equals("92233720368547758.07"));
        check(renderer.render(receipt).equals("A x1 = 1.99\\nB x2 = 0.02\\nSubtotal: 2.01\\nDiscount: 1.00\\nTotal: 1.01\\n"));
        var empty = new CheckoutService(catalog, discount).checkout(List.of());
        check(empty.totalCents() == 0 && empty.lines().isEmpty());
        check(BuildInfo.applicationName().equals("Mini checkout"));
        System.out.println("CHECKS_PASS");
    }
}
'''

INGEST_CHECKS = '''import ingest.*;
import java.util.*;

public class Check {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        var tokenizer = new Tokenizer();
        check(Arrays.equals(tokenizer.fields("a|b|", "|"), new String[]{"a", "b", ""}));
        check(Arrays.equals(tokenizer.fields("a.b..", "."), new String[]{"a", "b", "", ""}));
        check(Arrays.equals(tokenizer.fields("a::b::", "::"), new String[]{"a", "b", ""}));
        check(Arrays.equals(tokenizer.fields("x[", "["), new String[]{"x", ""}));
        rejected(() -> tokenizer.fields("x", ""));
        var parser = new RecordParser();
        var parsed = parser.parse("ABC| padded |12.34", "|");
        check(parsed.cents() == 1234 && parsed.label().equals(" padded "));
        check(parser.parse("ABC|x|0.01", "|").cents() == 1);
        check(parser.parse("ABC::x::1.2", "::").cents() == 120);
        check(parser.parse("ABC.x.0", ".").cents() == 0);
        check(parser.parse("ABC|x|92233720368547758.07", "|").cents() == Long.MAX_VALUE);
        for (String row : new String[]{"ab|x|1", "AB|x|1", "ABC||1", "ABC| |1", "ABC|x|", "ABC|x|-1",
                "ABC|x|+1", "ABC|x|1.234", "ABC|x|1e2", "ABC|x|1|extra", "ABC|x|92233720368547758.08"}) {
            rejected(() -> parser.parse(row, "|"));
        }
        var accumulator = new BatchAccumulator();
        accumulator.add(new ParsedRecord("ABC", "first", 100));
        rejected(() -> accumulator.add(new ParsedRecord("ABC", "replacement", 200)));
        check(accumulator.totalCents() == 100 && accumulator.records().get(0).label().equals("first"));
        accumulator.add(new ParsedRecord("DEF", "second", 50));
        check(accumulator.records().stream().map(ParsedRecord::id).toList().equals(List.of("ABC", "DEF")));
        var max = new BatchAccumulator();
        max.add(new ParsedRecord("ABC", "max", Long.MAX_VALUE));
        rejected(() -> max.add(new ParsedRecord("DEF", "overflow", 1)));
        check(max.records().size() == 1 && max.totalCents() == Long.MAX_VALUE);
        var rows = new ArrayList<>(List.of(new InputRow(1, "ABC:: a|b\\\\c ::1.01"),
                new InputRow(2, "BAD::x::"), new InputRow(3, "ABC::duplicate::2"),
                new InputRow(4, "DEF::z::0.09"), new InputRow(5, "GH::x::1")));
        var report = new IngestionService().ingest(rows, "::");
        check(rows.size() == 5);
        check(report.acceptedCount() == 2 && report.rejectedCount() == 3 && report.totalCents() == 110);
        check(report.records().stream().map(ParsedRecord::id).toList().equals(List.of("ABC", "DEF")));
        check(report.issues().stream().map(ValidationIssue::line).toList().equals(List.of(2, 3, 5)));
        try { report.records().clear(); throw new AssertionError(); } catch (UnsupportedOperationException expected) { }
        var renderer = new ReportRenderer();
        check(renderer.escape("a|b\\\\c").equals("a\\\\|b\\\\\\\\c"));
        check(renderer.render(report).equals("accepted=2;rejected=3;totalCents=110\\nABC| a\\\\|b\\\\\\\\c |101\\nDEF|z|9\\nerror@2:Invalid row\\nerror@3:Invalid row\\nerror@5:Invalid row\\n"));
        var empty = new IngestionService().ingest(List.of(), "|");
        check(empty.acceptedCount() == 0 && empty.totalCents() == 0);
        rejected(() -> new IngestionService().ingest(List.of(), ""));
        System.out.println("CHECKS_PASS");
    }
}
'''

TASKS = [
    {
        'id': 'checkout-precision',
        'prompt': '''修复商店结账仓库，不改变公开 API 名称或收据格式。
购物数量必须在 1..1000 范围内，包含两端。使用精确的 long 分金额算术计算每行价格，
拒绝算术溢出，并保留购物车顺序及输入数据。折扣资格包含阈值本身；
百分比范围为 0..100，即使 subtotal 为 Long.MAX_VALUE，也要无溢出地精确计算
floor(subtotal * percent / 100)。所有非负金额必须显示恰好两位小数，
不受区域设置影响，包括一分总额和 Long.MAX_VALUE。保留不可变收据、未知 SKU 拒绝、
空购物车总额及现有校验。验证价格、折扣和显示的回归情况。
BuildInfo.java 的字节保持不变，保留无关源码的行为。保持声明的源文件集合，不新增或删除源码文件。
不添加外部依赖，不使用网络、进程或环境变量输入输出。''',
        'sources': CHECKOUT_SOURCES,
        'expected_changed_paths': ['src/shop/CartLine.java', 'src/shop/PriceCalculator.java',
                                   'src/shop/DiscountPolicy.java', 'src/shop/ReceiptRenderer.java'],
        'unchanged_paths': ['src/shop/BuildInfo.java'],
        'reference_edits': [
            {'path': 'src/shop/CartLine.java', 'old_text': 'quantity < 0', 'new_text': 'quantity < 1'},
            {'path': 'src/shop/PriceCalculator.java', 'old_text': '(int) unit * item.quantity()',
             'new_text': 'Math.multiplyExact(unit, item.quantity())'},
            {'path': 'src/shop/DiscountPolicy.java', 'old_text': 'subtotal <= thresholdCents', 'new_text': 'subtotal < thresholdCents'},
            {'path': 'src/shop/DiscountPolicy.java', 'old_text': '(subtotal / 100) * percent',
             'new_text': '(subtotal / 100) * percent + ((subtotal % 100) * percent) / 100'},
            {'path': 'src/shop/ReceiptRenderer.java', 'old_text': 'return cents / 100 + "." + cents % 100;',
             'new_text': 'return cents / 100 + "." + (cents % 100 < 10 ? "0" : "") + cents % 100;'},
        ],
        'checks': CHECKOUT_CHECKS,
    },
    {
        'id': 'ingestion-integrity',
        'prompt': '''修复数据导入仓库，保留公开 API 名称。每个非空分隔符都按字面量处理，
包括正则特殊字符和多字符分隔符，并保留末尾空字段。记录必须恰好包含三个字段：
ID 匹配 [A-Z][A-Z0-9]{2,11}，标签不能全为空白且原样保留、不修剪，
金额为无符号普通十进制表示且最多两位小数。精确转换为 long 分金额，
拒绝无法表示的金额，并支持零和 Long.MAX_VALUE 分。
每个 ID 只接受首条有效记录；之后所有重复记录均拒绝，不能替换原记录或改变总额。
加法溢出也不能改变累计状态。保留输入顺序，按顺序报告拒绝记录的行号，遇到无效行后继续处理。
输出采用竖线分隔；反斜杠通过写成两个反斜杠转义，竖线通过前置反斜杠转义。
保留不可变报告及空导入行为。InputRow.java 和 BuildInfo.java 的字节保持不变。
验证小数、重复记录、溢出、字面量分隔符及转义回归。
保持声明的源文件集合，不新增或删除源码文件。不添加依赖，不使用网络、进程或环境变量输入输出。''',
        'sources': INGEST_SOURCES,
        'expected_changed_paths': ['src/ingest/Tokenizer.java', 'src/ingest/RecordParser.java',
                                   'src/ingest/BatchAccumulator.java', 'src/ingest/ReportRenderer.java'],
        'unchanged_paths': ['src/ingest/InputRow.java', 'src/ingest/BuildInfo.java'],
        'reference_edits': [
            {'path': 'src/ingest/Tokenizer.java', 'old_text': 'return input.split(delimiter);',
             'new_text': 'return input.split(java.util.regex.Pattern.quote(delimiter), -1);'},
            {'path': 'src/ingest/RecordParser.java', 'old_text': 'amount.longValueExact() * 100',
             'new_text': 'amount.movePointRight(2).longValueExact()'},
            {'path': 'src/ingest/BatchAccumulator.java', 'old_text': '        long updated = Math.addExact(total, record.cents());',
             'new_text': '        if (records.containsKey(record.id())) {\n            throw new IllegalArgumentException("Duplicate ID");\n        }\n        long updated = Math.addExact(total, record.cents());'},
            {'path': 'src/ingest/ReportRenderer.java', 'old_text': 'return value.replace("|", "\\\\|");',
             'new_text': 'return value.replace("\\\\", "\\\\\\\\").replace("|", "\\\\|");'},
        ],
        'checks': INGEST_CHECKS,
    },
]
