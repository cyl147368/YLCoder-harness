# 较大仓库夹具交接

只新增 `scripts/repository_fixtures.py`、`scripts/check_repository_fixtures.py` 和本文件。未改已有评估器、Java生产代码、配置或工作计划。未运行Maven、访问凭据/控制器或网络/模型调用。

## 夹具范围

| 任务 | 生产类 | 行/非空行 | 必需修改文件 | 参考编辑操作 | 保留字节文件 |
| --- | ---: | ---: | ---: | ---: | --- |
| checkout-precision | 9 | 177 / 150 | 4 | 5 | shop/BuildInfo.java |
| ingestion-integrity | 10 | 174 / 150 | 4 | 4 | ingest/InputRow.java, ingest/BuildInfo.java |

checkout覆盖CartLine验证→Catalog→PriceCalculator→DiscountPolicy→CheckoutService→不可变Receipt→ReceiptRenderer，包括long分金额溢出、含等号折扣阈值、直至Long.MAX_VALUE的精确向下百分比、十进制收据格式。ingestion覆盖Tokenizer→RecordParser→BatchAccumulator→IngestionService→不可变ImportReport→ReportRenderer，包括字面/尾分隔符、精确小数分、重复/溢出原子性、有序拒绝处理、转义输出。都含与修复行为无关的独立BuildInfo类。

公开提示描述行为和回归边界，无参考补丁。每任务导出id、prompt、sources、expected_changed_paths、unchanged_paths、reference_edits、私有checks。参考修复和检查必须在模型工作区之外；真实工作区准备只复制**公开源码映射和提示**。

## 独立检查器API

- prepare_workspace(task, workspace)按UTF-8/LF字节写公开源码快照，不复制检查/修复。
- verify_workspace(task, workspace, java, javac, timeout=20)读精确声明文件字节（不规范换行），拒绝缺失/非法UTF-8，再新临时隔离验证。
- verify_sources(task, sources, java, javac, timeout=20)接受精确声明快照映射。若声称字节保留，不要传规范read_text值，应使用verify_workspace。
- run_controls(task, java, javac, timeout=20)及main运行验证器对照。真实运行器绝不在模型工作区调用apply_reference。

检查器只编译声明快照及私有Check.java到新输出，显式空classpath/sourcepath、-proc:none、--release21、UTF-8、新cwd。仅编译成功才运行Java，运行classpath仅新输出。编译/断言超时和IO失败报告阶段类别，无原始诊断。编译前检查精确未改文件内容/哈希，注释或换行修改也破坏保留。

这隔离旧输出/隐藏检查及编译器发现，不是OS沙箱：未来模型生成Java仍可用当前账户权限执行。所含夹具/参考生产代码确定性，无网络、环境或进程IO；检查仅用断言和完成哨兵。

## 已验证对照

用已安装JDK21本地执行：

```text
python scripts/check_repository_fixtures.py --java D:\software\jdk-21.0.10\bin\java.exe --javac D:\software\jdk-21.0.10\bin\javac.exe
```

结果：**全部15对照变体符合预期**。每任务原缺陷快照断言失败、完整参考修复通过、逐文件遗漏编辑快照断言失败。checkout另拒绝一次指定未改文件修改，ingestion拒绝两次。全部三项保留对照在编译前source-invariants失败。参考编辑精确匹配一次，每任务精确改四声明路径。

已验证夹具哈希 `3a7cc1f7ddf1877e0ac9ee39ceebbbad5ad877d189eed9cb9b34bc52c9bca591`。检查器辅助增加不改变夹具。此交接尚未进行真实编码运行；结果建立验证器对照和参考修复，不代表模型编码质量或token/延迟性能。
