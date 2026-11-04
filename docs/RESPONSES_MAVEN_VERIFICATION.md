# Responses 协议与 Maven 审核验收

2026-10-27，在 Windows、Java 21.0.10、Maven 3.9.14 上完成本次实现与验收。

## 本次行为

- `openai` 和 `openai-compatible` 省略格式时仍使用 Chat Completions；增加 `api-format: responses` 或 `--api-format responses` 后使用 `/responses`。旧的 `provider: openai-responses` 继续支持。
- 主模型支持 Responses SSE 文本、完整函数调用、输入/输出/缓存用量和加密推理回放。审核模型默认继承主协议，可单独配置。流式 HTTP 400 的非流式恢复继续访问 `/responses`。
- `review` 模式将受支持的 Maven 3.9.x 构建/测试交给审核模型，不再因超过 128 字符直接转人工。批准后执行绑定的外部 Java/Maven 参数；工作区内包装器不进入自动执行。当前用户任务在长工具历史中仍保留在审核投影内。
- Maven 构建会执行插件和测试代码，可写文件及联网。它不是只读查询或沙箱；不支持的目标、注入参数、本地校验失败和审核失败仍需人工确认。

用法和详细限制见[使用手册](../使用手册.md)、[自动审核实现](AUTO_REVIEW_IMPLEMENTATION.md)和[原生流式输出](NATIVE_STREAMING.md)。

## 测试证据

完整 `mvn verify` 构建与打包成功：

| 模块 | 测试总数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| harness-core | 51 | 0 | 0 | 0 |
| harness-providers | 121 | 0 | 0 | 0 |
| harness-cli | 558 | 0 | 0 | 11 |
| 合计 | 730 | 0 | 0 | 11 |

跳过项依赖符号链接或其他平台条件。719 项实际执行通过；本次未发起真实模型请求。

源码打包 JAR 和实际全局 `bin/ylc.cmd` 分别执行了以下验收：

| 冒烟脚本 | 每个入口的场景数 | 每个入口的 localhost 请求数 | 结果 |
| --- | ---: | ---: | --- |
| `scripts/smoke_responses.py` | 5 | 17 | 全部通过，`/chat/completions` 请求为 0 |
| `scripts/smoke_maven_review.py` | 8 | 20 | 全部通过，真实执行 3 次 Maven 构建、6 次临时 JUnit 测试 |

Responses 场景覆盖配置及 CLI 选择、旧 provider、真实 SSE、独立进程恢复同一会话及工具/加密推理历史、HTTP 400 同协议恢复、审核凭据继承和 token/cache 统计。Maven 场景覆盖用户原样长命令、有效批准缓存、人工模式、审核拒绝/失败及危险目标和 shell 串接拒绝。Maven 只运行临时测试项目，并复用已准备的本地依赖缓存。

全局安装保留现有私有配置。源码与全局指针选择的 JAR SHA-256 一致：

```text
5e7b31c45294fea86daee2c86a89157b7d896b40685b702704175323fa70af70
```

`--help` 已验证包含 `--api-format`；打包 JAR 内的 Responses 流解析器、Maven 执行计划、格式解析器和 `UnifiedDiff` 类均存在。已有 JVM 需要退出后重新启动才能使用更新。

本地详细证据位于 `target/maven-responses-validation.json`、`target/maven-responses-verify.log` 和 `target/*-source-smoke.json`、`target/*-global-smoke.json`，这些生成文件不作为源码提交。`git diff --check` 通过。

## 复现

```text
mvn -B verify
python scripts/smoke_responses.py --result target/responses-source-smoke.json
python scripts/smoke_maven_review.py --result target/maven-review-source-smoke.json
```

全局验收在脚本参数中增加 `--launcher` 和已安装启动器的绝对路径。Maven 冒烟需要外部 Maven 3.9.x 和准备好的离线依赖；支持 `--maven-home`、`--local-repository` 选择安装及缓存。本次主项目离线构建通过临时 `-gs/-s` 设置匹配现有缓存的仓库 ID，没有修改用户 Maven 设置或读取 ylc 私有配置。

以上结果证明本地协议与执行链满足这些契约，不代表已经测得真实厂商兼容率、审核准确率、token 费用或线上延迟。
