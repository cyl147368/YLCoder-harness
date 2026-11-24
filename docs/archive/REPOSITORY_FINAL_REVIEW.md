# 最终仓库整合审阅

只读审阅当前评估器、运行器边界对照、共享检查器及负责人生成 `target/repository-offline-hardened.json`。仅编辑本文档。未重跑测试，未访问真实报告、控制器、凭据、网络或Maven。

## 整合结论

早期运行器审计四缺口已在当前组合验收路径解决：

- run_case要求set(changed)==set(expected_changed_paths)，四个必需路径都须修改，任何额外声明辅助源码修改均失败。
- 运行器独立比较四路径之外**每个**声明源码初始/最终原字节，保护五checkout辅助类和六ingest辅助类，包括指定保护文件。嵌套检查器仍只报告指定保护文件；顶层unrelatedSourcesUnchanged范围更广。
- verify现调用共享加固verify_workspace，拒绝声明源码符号链接/规范重定向、验证枚举路径、要求全工作区精确Java源码集合。.harness Java新增被检查器和运行器允许列表拒绝；普通.harness状态仍允许。独立编译继续用新目录、隐藏检查、空编译classpath/sourcepath。
- 真实预检现解析URL：HTTPS、非空hostname、无user/password/query/fragment、有效port、无空白/控制字符或反斜杠。CLI启动前拒绝缺失主机/错误端口。这是配置语法验证，不声称网络可达或厂商兼容。

scripts/check_repository_runner.py包含十个显式端点和四个工作区情况，含保留状态文件、拒绝.harness/sessions和src额外Java。本审阅检查对照源码，未执行，不能将源码视为执行证据。

## 已有加固离线报告

时间 `2026-10-14T09:34:46.593955+00:00`，模式localhost-known-repair。四运行均COMPLETED、退出0、独立断言通过，declaredSourcesExact、exactChangedPaths、unrelatedSourcesUnchanged、workspaceAllowlist为true。每运行两代理迭代、两localhost请求，无夹具失败。

| 夹具 | 变体/估算上下文预算 | 精确修改路径 | 未改辅助类 | 进程/代理ms |
| --- | --- | --- | --- | --- |
| checkout-precision | baseline / 48000 | 4 | 5 | 1280.0681 / 156 |
| checkout-precision | compact / 12000 | 4 | 5 | 1398.8336 / 214 |
| ingestion-integrity | baseline / 48000 | 4 | 6 | 1240.2672 / 160 |
| ingestion-integrity | compact / 12000 | 4 | 6 | 1211.2492 / 154 |

每夹具每声明源码的最终哈希在基线和紧凑之间匹配。checkout修改CartLine.java、DiscountPolicy.java、PriceCalculator.java、ReceiptRenderer.java。ingest修改Tokenizer.java、RecordParser.java、BatchAccumulator.java、ReportRenderer.java。

报告指纹：

```text
artifactSha256: 43d272eec8e2087f3e72bb5147cfdec5e2fba00be7614d7fef4dfe0b23d1e3a6
evaluatorSha256: 85e32e955b75d62aad02f633c36f931f68b29aa75f14aab29c41b0c6b016321c
checkerSha256: 90a045b74695362aaa07bbdc6cee9995a134017023c2d3a6d1ed0247902491f2
fixtureSha256: 3a7cc1f7ddf1877e0ac9ee39ceebbbad5ad877d189eed9cb9b34bc52c9bca591
```

当前JAR、评估器、检查器只读SHA-256检查匹配三个对应指纹。夹具身份取自报告，本审阅未独立重算规范夹具摘要。

## 剩余证据限制

普通夹具修复的组合门槛未发现实质误判通过。但此结论不能将源码审阅转为未执行负对照证据。此前检查器明确跳过四个源码链接/父重定向，因为此Windows环境不能创建符号链接；拒绝执行证据仍待支持链接环境。运行器边界脚本也无额外辅助文件修改负对照；代码中精确集合相等和全部辅助字节比较强制此条件。

检查器以当前账户执行提交Java；私有编译目录是验证隔离，不是敌对代码沙箱。保留已有范围说明。

离线策略提供已知参考修复。报告正确标用量缺失，不声称真实模型、账单或速度。上下文预算是估算，非测量token消耗。单次配对且基线先紧凑后，不能支持延迟对比或证明上下文压缩节省。
