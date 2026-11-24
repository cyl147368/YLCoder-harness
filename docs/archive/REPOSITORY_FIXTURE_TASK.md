# 较大关联文件修复夹具

负责人：responses_acceptance。仅负责新建 scripts/repository_fixtures.py、scripts/check_repository_fixtures.py 和 docs/REPOSITORY_FIXTURE_REVIEW.md。其他成员也在工作；不要修改已有夹具或评估器、Java 生产代码、配置、WORKPLAN 或其他人的工作。不要访问真实网络、凭据或控制器，也不要运行 Maven。

创建两个 Java 21 微型仓库修复任务，规模大于单行或双文件夹具：每个任务 6-10 个关联生产类，约 150-300 行实质代码（避免人为填充），完整修复至少需要修改三个独立文件，且至少一个无关源码必须保持字节一致。优先采用结账/价格/收据流程，以及文本导入/验证/报告流程，覆盖实际边界。公开提示描述行为和回归情况，但不提供参考补丁。类名、包名和 API 要稳定，便于全新独立 Java 断言。每个夹具导出 id、prompt、sources 字典、预期修改路径、未修改路径、reference_edits、checks 源码。所有生成代码和检查都确定性执行，不访问网络、环境或进程 I/O。隐藏检查存放在模型工作区之外，为每个受影响组件覆盖多个边界/回归情况。参考编辑只用于独立验证器对照，绝不提供给真实模型。

导出精确任务键 id、prompt、sources、required_changed_paths、unchanged_paths、reference_edits 和 checks。reference_edits 是 path/old_text/new_text 字典列表。checks 声明默认包的 Check，其中 main 只在全部断言通过后打印 CHECKS_PASS。所有生产路径位于 src/ 下。这是负责人的真实运行器集成契约。（历史任务记录；最终实现的字段名见实际夹具与审阅文档。）

在 check_repository_fixtures.py 实现对照：将精确声明的源码快照复制到新目录后编译，显式空 classpath/sourcepath、-proc:none、新输出、Java21 release；仅执行成功编译的输出，时间有限。原始缺陷版本以及逐个遗漏被编辑文件的变体必须失败；完整参考修复必须通过。验证编辑匹配唯一性和未修改源码对照。使用已安装 Java21 本地运行对照。记录任务行数、文件数、必要编辑和精确对照；负责人负责后续真实运行器和所有真实编码运行。范围限于项目与临时夹具。
