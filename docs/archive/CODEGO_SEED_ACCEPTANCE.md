# 精确预置评估器localhost验收

仅实现 `scripts/check_seeded_evaluation.py` 和本文件。脚本以live=True调用未变评估器run_case，指向受控127.0.0.1服务，覆盖实际预置检查点/恢复分支。这是结构localhost验收，不是认证外部模型测试。

显式临时配置选择本地端点、700输出token、十秒厂商超时、命令/MCP禁用。唯一人工凭据环境变量仅在夹具期间存在，在finally移除；未访问真实凭据或控制器。请求必须带人工认证、stream:true、max_tokens:700和夹具模型。JSON回退响应恰好返回一次edit_file修复，再文本完成。socket、厂商、子进程等待有限。

服务验证有序assistant/tool调用ID、无重复/缺失结果、原始/最新用户指令，以及首请求精确保留Subject.java预置。基线必须按顺序有全部13预置周期、29消息。紧凑保留当前源码但删除早期周期。只报告结构计数/状态，不打印完整载荷或凭据。

以下命令成功执行，退出码0：

```text
python scripts/check_seeded_evaluation.py --java D:\software\jdk-21.0.10\bin\java.exe --javac D:\software\jdk-21.0.10\bin\javac.exe
```

| 变体 | 首请求消息/工具周期 | 次请求消息/工具周期 | 请求数 | 独立质量 | 预置后保留工具 | 保留失败 |
| --- | --- | --- | ---: | --- | --- | ---: |
| 基线，48,000预算 | 29 / 13 | 31 / 14 | 2 | 通过 | edit_file | 0 |
| 紧凑，4,000预算 | 8 / 2 | 10 / 3 | 2 | 通过 | edit_file | 0 |

两者COMPLETED，首请求保留精确当前源码，SHA256 `4e4358a2d52a7090827e53165266caacdbacbdbffcf547de7e5f3ff43508a163`。原缺陷编译器对照失败，已知修复对照通过。修正的retainedToolNamesAfterSeed在两个最终检查点均包含edit_file，即使紧凑保留更少预置周期。这建立此夹具轨迹提取和必需预置保留，不声称真实用量/速度。

未改评估器/验证器/生产/配置源码、WORKPLAN或其他文档。未运行Maven或外部主机调用。真实重跑仍由负责人负责。
