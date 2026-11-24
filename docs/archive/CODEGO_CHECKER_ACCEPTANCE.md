# 自定义网关独立验收审阅

## 已执行离线证据

阅读 AGENTS.md，审阅 `scripts/validate_custom_provider.py` 和 `scripts/evaluate_coding.py`，没有读取凭据或访问凭据控制器。仅运行已授权 localhost 评估器：

```text
python scripts/evaluate_coding.py --task inclusive-range --repeat 1 --java D:\software\jdk-21.0.10\bin\java.exe --javac D:\software\jdk-21.0.10\bin\javac.exe
```

退出码0，报告 `passed` 为true。独立检查器拒绝缺陷源码，接受修复源码。两个配对运行均以15次模型请求完成，保留当前源码，并通过全新Java编译/断言。两者SHA256均为 `4e4358a2d52a7090827e53165266caacdbacbdbffcf547de7e5f3ff43508a163`。

| 变体 | 上下文预算 | cl100k序列化token合计 | o200k序列化token合计 | 序列化UTF-8字节 | 进程ms | 代理ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 基线 | 48,000 | 219,469 | 219,815 | 895,708 | 1328.799 | 324 |
| 紧凑 | 4,000 | 40,382 | 40,552 | 174,250 | 1354.426 | 310 |

这是单次重复、脚本化localhost测量。厂商用量缺失，未测首段文本延迟，耗时不能证明真实厂商提速。未付费/真实调用或运行Maven。

## 具体审阅发现

1. **自定义工作流通过不能证明read/edit/read顺序或编辑成功。** `validate_custom_provider.py` 统计助手工具名，只要求任意位置有edit_file及两次read_file，不配对成功结果或检查顺序。read/read/write_file/edit_file（edit失败）的轨迹可能产生值42、满足独立检查并通过；提示禁止其他文件，但也未拒绝额外修改。在宣称精确工作流前，应要求成功read先于成功edit，后面还有成功read，并检查最终工作区文件允许列表。这是宣称流程的覆盖缺口，不证明真实运行采用了该轨迹。

2. **发现报告在JSON转义后脱敏太晚。** 脚本在最终 `json.dumps` 字符串替换原始 `key`。发现的 `models` 和HTTP错误头没有在序列化前递归脱敏。网关若在模型ID或Location头回显含引号/反斜杠的人工密钥，JSON转义后不再包含原始密钥，替换便漏掉。响应摘要和编码providerError已有序列化前替换。应在编码前对全部字符串做精确密钥脱敏，并保留最终替换作纵深防御。未查看真实密钥；结论直接来自JSON转义语义。

3. **真实成本对比仍须限定。** `evaluate_coding.py` 转发归一化用量，并准确说明零可能代表不可用。网关省略用量时，两种工作流均不能证明真实用量；`validate_custom_provider.py` 当前仅标 `usage`，没有可用性说明。编码检查成功不应视为token节省或厂商报告用量可用的证明。网关交付报告应保留评估器限定。

隔离评估器排除旧类、隐式相邻源码及注解处理器发现，编译失败后不执行。自定义工作流检查器也使用全新临时类/输出位置、`-proc:none`，编译失败不执行，不把工作区作为classpath。两者是生成Java的功能测试harness，不是OS沙箱或对抗性正确性证明。

此指派只修改本审阅文档。脚本修改和真实修复由负责人处理。

## 后续修复与离线对照

归属移交后，仅修改 `scripts/validate_custom_provider.py`、新 `scripts/check_custom_validation.py` 和本文件。上方发现描述原代码，验证脚本现已修复：

- JSON转义前，递归精确凭据脱敏覆盖字典键、嵌套字符串、模型ID和请求头。最终替换仍作纵深防御。
- 持久化TOOL消息含字符串 `content`、`toolCallId`、`toolName`，没有持久化success布尔值。证据现配对唯一调用/结果，拒绝待执行/重复/不匹配结果，仅允许Answer.java的read_file/edit_file，要求编辑前带行号内容return41、恰好一次编辑、编辑后return42。成功编辑必须匹配真实 `Edited "Answer.java": replaced 1 literal occurrence; sha256=...` 结果前缀及最终源码哈希。失败、中断、不可用和未改结果均不满足。会话身份/工作区/完成状态必须一致。
- 工作区条目仅限Answer.java、未改task.yaml及普通.harness运行状态；拒绝符号链接/重定向路径。commands、write_file、MCP和其他工具不能满足验收。
- 报告明确说明用量来自适配器，零可能代表缺失，未验证账单。

已有真实边界保持：五迭代、配置输出700token、厂商超时60秒、子进程180秒。保留独立全新编译及编译失败后不执行。此成员未重复真实请求。

执行 `python scripts/check_custom_validation.py`：成功有序转录和允许运行文件通过；拒绝错误顺序、缺失前后读取、失败/未改编辑、错误后值、失败读取、错误编辑哈希、write_file/command/MCP调用、重复/不匹配结果、错误路径和额外文件。还往返验证含引号、反斜杠、换行、Unicode的人工凭据嵌套键/值递归脱敏；未读真实凭据。`python -m py_compile scripts/validate_custom_provider.py scripts/check_custom_validation.py` 通过。

对照验证功能证据解析，不证明对恶意改写检查点的防篡改。真实重跑和交付决策仍由负责人负责。

负责人整合允许对工作区根目录无害list_files，因为实际CLI系统提示要求发现适用指导。列表不能替代Answer.java必需成功read/edit/read。验证器现哈希精确文件字节而非换行规范化文本，保持Windows CRLF编辑结果哈希校验。离线对照加入根列表及CRLF字节/哈希，整合后再次通过。
