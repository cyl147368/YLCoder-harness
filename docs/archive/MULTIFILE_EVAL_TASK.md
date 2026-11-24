# 多文件编码评估夹具

负责人负责新建 scripts/evaluate_multifile.py 运行器和文档。cli_acceptance 仅负责 scripts/multifile_fixtures.py。其他成员也在工作；保留其他成果，不运行 Maven 或外部模型。此前指派均已完成。原英文要求现按用户最新指令中文化。

创建纯数据 TASKS 列表，包含三个小型但有意义的带包 Java 多文件编码任务。每个任务至少需要修改两个文件。建议类别：重命名/改变公开 API 与依赖调用点；防溢出的金额汇总与依赖发票用途；字面分隔符/空字段解析与依赖验证。源码和预期修复保持确定性。独立验证器会在代理停止后，编译工作区全部 src/**/*.java，以及运行器写入的隐藏 Check.java。

每个任务契约：id 字符串、prompt 字符串、sources 字典（工作区相对 src/...java -> 完整源码）；edits 列表（{path, old_text, new_text}，精确且唯一的替换）；checks 字符串是完整、默认包 public class Check，其 main 对公开生产 API 做独立断言（失败抛 AssertionError，成功打印 CHECKS_PASS）。提供对照预期：原始缺陷源码必须失败，完整修复必须通过。逐一从完整修复中遗漏每个编辑时，检查器都必须拒绝；据此设计任务。不用 JUnit 或外部依赖。API 签名可以变化；编译失败是有效负对照。仅用 Java 21 标准库。可行时初始源码应能在不带检查器时编译。

暂不创建运行器或测试。负责人会编程验证原始缺陷、完整修复及每个部分修复变体，并通过真实 JAR 整合。最终清楚报告任务契约。

首稿审阅说明：Check 必须在默认包（不能 package check），因为运行器执行 `java Check`。重命名检查器必须直接调用 Counter.sum，使原始源码失败。不能有无操作编辑：invoice printer 初始使用 Integer.toString，在 API 改变后必须改成 Long.toString。edits 只列实际必要替换。增强空值/负值/正常值检查；分隔符测试 `a||b` 即使原解析器丢弃尾空字段，也必须无效。方便时每个任务增加第三个无关辅助源码，证明未修改源码保持。运行器目前独立检查每个缺失编辑。
