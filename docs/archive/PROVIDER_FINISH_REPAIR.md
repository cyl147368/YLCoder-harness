# 厂商结束标记修复

## 结果

OpenAI Chat Completions 和 Anthropic Messages 现要求非空白文本完成标记。缺失、null、空字符串、纯空白和非文本标记，在响应到达代理前抛出脱敏 `ProviderException`。未知文本标记继续失败。这关闭了 `PROVIDER_FOLLOWUP.md` 记录的格式错误响应路径：之前会推断成功完成，并可能执行响应提供的修改调用。

## 负责的修改

- `harness-providers/src/main/java/dev/codeharness/providers/OpenAiCompatibleProvider.java`：要求显式有效 `finish_reason`，不再默认 `stop`。
- `harness-providers/src/main/java/dev/codeharness/providers/AnthropicProvider.java`：要求显式有效 `stop_reason`，不再默认 `end_turn`。
- `harness-providers/src/test/java/dev/codeharness/providers/ProviderFinishStateTest.java`：四项 localhost 验收，覆盖两种协议、OpenAI 别名、Azure 继承和真实 `CodeAgent` 阻止修改。

显式支持的标记映射保留。显式 STOP/end_turn 响应包含调用时，现有兼容行为保留。未改核心或 CLI 源码。

## 证据

使用 JDK 21 和现有 Surefire 依赖 classpath，运行 `python harness-providers\target\finish-state-acceptance\verify.py`。辅助脚本只将编辑后的厂商和新测试编译到私有 target 目录，然后调用四个测试方法。结果：**4 项测试通过**。

测试覆盖有/无工具调用时，缺失、null、空字符串、空白、数值、对象、数组、布尔和未知文本标记。记录式 `write_file` 集成验证：缺失/null 响应产生 FAILED，零修改执行，且不追加工具结果转录。OpenAI、通用兼容、Ollama 和 Azure 别名均拒绝缺失标记。错误消息不含任意标记文本，也不附带原始响应 cause。

全部流量均为 localhost。未运行 Maven 或真实模型调用。负责人应串行运行 reactor，将这些修改与当前核心/CLI 编辑和全部既有夹具一起验证。私有辅助脚本生成于 target 下，不是交付物。

## 范围限制

负责人最终验收：串行 `mvn -B verify` 于 2026-10-14 10:45:22 Asia/Shanghai 通过，包括全部 45 项厂商测试和完整核心/CLI reactor。重建产物上的四套真实 JAR localhost 冒烟也通过。上方成员辅助脚本运行属于初步交接证据。

本修复没有增加推理元数据支持或新流式协议。这些观察保留在 `PROVIDER_FOLLOWUP.md`，供后续排序。
