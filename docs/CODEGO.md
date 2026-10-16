# CodeGo 自定义网关

本文及 `configs/codego.example.yaml` 仅用于复现已归档的 CodeGo 验证。日常接入任意自定义厂商请将根目录 `config.example.yaml` 复制为自己的私有配置，填写 API 基础地址、模型 ID 和 `api-key`；也可将密钥留空，通过 `api-key-env` 引用自选环境变量。

使用随包提供的 `configs/codego.example.yaml`，凭据环境变量名称为 `CODEGO_API_KEY`。它选择 `openai-compatible`、`gpt-6-sol` 和 `https://codegoai.com/v1`。配置不包含凭据，也不授予 shell 或 MCP 权限。

```powershell
# 先在当前终端的进程环境中设置 CODEGO_API_KEY。
# 从源码目录运行：
.\ylc.cmd --config .\configs\codego.example.yaml --workspace E:\path\to\repo
.\ylc.cmd --config .\configs\codego.example.yaml --workspace E:\path\to\repo run "修复失败的解析器测试"
# 解压便携包后：将 .\ylc.cmd 替换为 .\bin\ylc.cmd。
```

`resume SESSION_ID` 时提供相同的显式配置和工作区。凭据和自定义 base URL 不保存在检查点中。`--no-stream` 选择同步请求；默认请求原生 Chat Completions 流式输出，也接受 JSON 回退，不会重试。网关使用 `max_tokens` 设置输出上限。选择其他已部署模型时，通过 `--model` 使用该网关返回的 ID，不要假定上游模型名称可用。

此配置限制每次生成最多 1,000 个输出 token、每次厂商请求最多 60 秒、每项任务最多八次迭代。默认上下文估算预算为 12,000。必需指令/最近内容能够放入预算时，`--context-budget 4000` 会减少保留历史；压缩可能需要重新读取旧细节。配置默认使用 UTF-16 估算；`--token-counter cl100k` 或 `o200k` 选择其他本地统计方式，不代表网关账单分词器。`--parallel-reads 4` 可以并行执行同一模型响应中的独立读取；修改仍串行执行。

## 复现受限验收

以下命令会真实调用网关，可能产生费用。必须显式配置凭据环境变量。脚本不会把凭据写入配置或证据文件。

```shell
python scripts/validate_custom_provider.py --live --discover --base-url https://codegoai.com/v1 --credential-env CODEGO_API_KEY
python scripts/validate_custom_provider.py --live --base-url https://codegoai.com/v1 --credential-env CODEGO_API_KEY --model gpt-6-sol
python scripts/validate_custom_provider.py --live --base-url https://codegoai.com/v1 --credential-env CODEGO_API_KEY --model gpt-6-sol --no-stream
python scripts/evaluate_coding.py --live --config configs/codego.example.yaml --provider openai-compatible --model gpt-6-sol --credential-env CODEGO_API_KEY --repeat 2 --max-iterations 6 --timeout 180
```

模型发现限制为 2 MB，并拒绝重定向。小型编程工作流使用临时源码，要求按顺序完成读取、编辑和结果回放，并通过新的 Java 编译独立检查最终数值。它允许列出工作区文件，但拒绝命令、write_file 和 MCP 调用。验证器记录工具结果和允许路径列表，不信任模型最后的自述。`python scripts/check_custom_validation.py` 在无网络、无凭据条件下运行合成负向对照。

成对评测器给两个变体预置同样的十二个历史诊断工具循环和当前源码，再比较 48,000 与 4,000 预算。它在新的编译目录中通过隐藏断言验证包含边界、小数/空数组平均值和 null/空白处理。真实运行前，缺陷/修复对照组必须通过。每行最多六次模型迭代；重复一次的完整套件有六行。它记录规范化网关用量、产物/脚本哈希、进程时间和成功配对总量。此 JSON 子进程运行器不测量首文本时间或实际传输分词计数。

验收证据和精确结果归档在 [codego-acceptance-v1.json](benchmarks/codego-acceptance-v1.json)。请同时查看失败和通过记录：最初的模型发现返回 403；第一个预置历史配对在收到规范化响应前失败，保留的错误不足以确定原因。之后显式调用的检查成功。YLCoder-harness 不会自动重试付费请求；失败时规范化用量为零也不能证明没有收费。

完整六迭代套件的六项独立代码检查都通过，但其中一次压缩后的 null/空白任务在最终完成前达到 MAX_ITERATIONS。只有两对已完成任务进入主要成功配对用量比较：基线输入 245,263 tokens，压缩输入 71,304 tokens（报告输入减少 70.93%）。null/空白后续检查明确告知模型“退出后执行独立检查”，并允许八次迭代；其指令/上限发生变化，因此单独归档，不进入该汇总。

后续[实测续接](CODEGO_EVALUATION.md)和 [v2 归档](benchmarks/codego-acceptance-v2.json)保留十二次交替顺序的成功预置历史修复、六个真实首个可见文本样本，以及四次从新鲜源码开始的关联文件修复。预置历史输入总量降低 72.17%；新鲜源码仓库总量仅相差约 0.81%。两者都未经独立账单审计。小幅时间差不能证明稳定的生产提速或普遍仓库质量。历史十小时窗口仍未达成，Linux 运行验收仍待完成。
