# 仓库检查器加固验收

检查实现拒绝工作区任何位置额外Java源码，包括 `.harness/Extra.java`。读取源码字节前检查声明路径和所有枚举条目是否符号链接或规范重定向。源码快照保留UTF-8换行字节以验证未改文件。编译仍用新输出目录、显式空classpath/sourcepath、禁注解处理、Java release21。

审阅成员恢复任务时实现已存在；保留并审阅，未再改检查器。此成员只增加本报告。

## 离线验证

执行一次：

```text
python scripts\check_repository_fixtures.py --java D:\software\jdk-21.0.10\bin\java.exe --javac D:\software\jdk-21.0.10\bin\javac.exe
```

退出码 **0**。观察进程墙钟 **9.570秒**。未用网络、模型调用或Maven。

| 夹具 | 原对照执行 | 新额外Java对照执行 | 链接对照跳过 | 执行预期符合 |
| --- | ---: | ---: | ---: | ---: |
| checkout-precision | 7 | 1 | 2 | 8/8 |
| ingestion-integrity | 8 | 1 | 2 | 9/9 |
| 合计 | 15 | 2 | 4 | 17/17 |

原15对照保留：两个缺陷仓库隐藏断言失败、两个完整参考修复通过、八个逐文件遗漏修复失败、三个禁止未改文件修改在编译前源码不变量门槛失败。

两项额外 `.harness/Extra.java` 在snapshot阶段以declared_source_set_mismatch失败，均gateMatched=true。

两夹具source-symlink和parent-directory-redirection均无法在平台创建符号链接。四项明确报告passed:null、skipped:true、phase setup、reason symlink_creation_unsupported、error type OSError。检查器有意合并不支持平台/权限错误，输出不证明更具体OS错误码。这四项没有测试拒绝门槛，不能计通过。

汇总passed:true仅表示所有**已执行**预期符合。独立allControlVariantsExecuted:false准确记录平台覆盖不全。

## 精确身份

此执行报告的检查器字节SHA-256：

```text
90a045b74695362aaa07bbdc6cee9995a134017023c2d3a6d1ed0247902491f2
```

此执行报告的 `json.dumps(TASKS, sort_keys=True).encode()` SHA-256：

```text
3a7cc1f7ddf1877e0ac9ee39ceebbbad5ad877d189eed9cb9b34bc52c9bca591
```

## 限制

源码/目录符号链接拒绝在代码中审阅，但本宿主未执行。有创建符号链接能力的平台应运行相同对照，取得21个已执行变体。这是编译器/源码验证隔离，不是OS沙箱；编译夹具Java以当前账户权限运行。枚举和快照读取也不保证对并发恶意文件系统修改的事务。
