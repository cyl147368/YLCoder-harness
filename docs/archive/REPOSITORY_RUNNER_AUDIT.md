# 仓库运行器独立审计

审阅 `scripts/evaluate_repository.py`、`scripts/repository_fixtures.py`、`scripts/check_repository_fixtures.py`。本审计仅负责文档。未运行Maven、外部厂商请求、访问凭据或改源码/配置。

## 已执行验收

执行：

```text
python scripts/evaluate_repository.py --offline --variants paired --max-iterations 4 --timeout 60
```

退出0，报告时间 `2026-10-14T09:22:29.337425+00:00`。

| 夹具 | 变体/估算上下文预算 | 独立编译/断言 | 修改路径 | 进程/代理ms | 结果 |
| --- | --- | --- | --- | --- | --- |
| Checkout | baseline / 48000 | 通过 | 4 | 1238.3615 / 163 | 通过 |
| Checkout | compact / 12000 | 通过 | 4 | 1247.5835 / 167 | 通过 |
| Ingest | baseline / 48000 | 通过 | 4 | 1215.8292 / 161 | 通过 |
| Ingest | compact / 12000 | 通过 | 4 | 1217.1519 / 145 | 通过 |

每运行两代理迭代、两localhost HTTP请求完成，无夹具失败。每次requiredFilesChanged、unrelatedSourcesUnchanged、workspaceAllowlist为true。各夹具基线/紧凑最终源码哈希相同。localhost夹具无厂商用量。

checkout含九公开源码/177行，修改 `src/shop/CartLine.java`、`src/shop/DiscountPolicy.java`、`src/shop/PriceCalculator.java`、`src/shop/ReceiptRenderer.java`。保护文件 `src/shop/BuildInfo.java` 保持字节一致。

ingest含十公开源码/174行，修改 `src/ingest/Tokenizer.java`、`src/ingest/RecordParser.java`、`src/ingest/BatchAccumulator.java`、`src/ingest/ReportRenderer.java`。保护文件 `src/ingest/InputRow.java`、`src/ingest/BuildInfo.java` 保持字节一致。

证据身份：

```text
artifactSha256: 43d272eec8e2087f3e72bb5147cfdec5e2fba00be7614d7fef4dfe0b23d1e3a6
evaluatorSha256: d19542d0758465406dee3b99d4c06d19642801f9ae7b084c8f2fc15f4198c8c8
fixtureSha256: 3a7cc1f7ddf1877e0ac9ee39ceebbbad5ad877d189eed9cb9b34bc52c9bca591
```

这些运行用提供已知修复的确定服务验证打包CLI修复/验收路径，不证明真实模型推理、计费token节省或提速。耗时是个别观察，无统计比较结论。

## 验收设计审阅

- 公开源码按UTF-8字节写入，保留规范LF；初始/最终按字节比较，保护不变量不受换行规范化干扰。
- 隐藏Check.java和参考编辑不复制到模型工作区。离线服务独立持有已知参考；真实请求须自行产生补丁。
- 验证将声明快照复制到新私有临时目录，以Java21、显式UTF-8、禁注解处理、空编译classpath/sourcepath编译。代理产出的类不能满足检查。断言要求退出0和精确CHECKS_PASS输出行。
- 运行器拒绝源码符号链接和规范重定向；允许列表在考虑运行文件前同样拒绝。缺失、过大、非法UTF-8输入或编译失败不能通过组合门槛。
- 可信厂商配置在代理工作区之外。命令/MCP禁用。离线仅localhost、清除人工凭据变量；夹具要求无Authorization。真实模式要求显式HTTPS URL、模型、已配置凭据变量。
- 编译/断言失败报告阶段和退出码，不暴露捕获源码/检查输出；超时和非法快照脱敏。最终报告递归精确凭据脱敏，包括代理错误字段。
- 源码中夹具对照覆盖缺陷、已知修复、逐必要修改文件遗漏、逐保护源码修改。本审计未重跑独立对照命令，运行器本身不执行负对照。

## 源码推导的负责人待处理缺口

1. **运行器未强制.harness下精确Java集合。** allowed_workspace豁免.harness下所有普通文件/目录；source_snapshot及独立编译器只考虑声明源码。因此额外.harness/Extra.java不验证也不使允许列表失败。这是源码缺口，不是观察的失败模型运行。若承诺精确工作区Java集合，应拒绝任何位置额外.java，包括.harness，同时保留会话/日志运行文件，并增加负对照。
2. **独立夹具检查器缺少运行器符号链接/规范路径门槛。** verify_workspace枚举src/**/*.java、读取声明路径，不拒绝符号链接/父重定向。生成对照是普通文件，未覆盖差异。应复用运行器规范快照验证，或等效验证及链接/重定向负对照。组合运行器仍由自身检查保护。
3. **保护字节只覆盖指定无关源码。** requiredFilesChanged只要求四路径包含，不要求修改集合等于它们。未列unchanged_paths的辅助源码可能修改。观察运行恰好改四文件。真实报告应明确，或若交付声称只改四文件则要求精确相等。
4. **真实端点预检只是字符检查。** 运行器检查https://前缀、拒绝?、#、@，但不解析/校验非空hostname。Java请求构建应拒绝非法端点，但本审计未跑此负对照。若承诺早期端点诊断，应采用共享解析URL验证。

私有编译目录是验证隔离，不是敌对代码执行沙箱，独立检查器明确如此。不应推断更广隔离。

## 下一验收工作

负责人应确定精确源码集合/保护文件契约，为收紧条件增加针对负对照，保留当前离线证据与带凭据真实运行分离。真实结果应含实际源码哈希、修改路径、保护字节证据、独立检查结果和用量可用性；本审计没有真实请求。
