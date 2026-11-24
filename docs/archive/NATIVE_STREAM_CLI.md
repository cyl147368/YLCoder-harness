# 原生流式打包冒烟

重建 CLI 胖 JAR 后，Java 21 在 PATH 上时运行 `python scripts/smoke_native_streaming.py`。脚本仅使用原生 `openai-compatible` 厂商和 localhost；显式空配置隔离用户 MCP/端点设置，清除夹具凭据变量，并断言 HTTP 请求没有 Authorization 头。

四个隔离工作区执行真实 CLI 子进程：服务暂缓完成时已出现流式 stdout，最终短语仅打印一次；JSON 输出可解析；碎片化、已完成 write_file 参数只执行一次修改，持久化一个调用/结果，下一 HTTP 请求包含结果；看似完整修改调用和 tool_calls 结束标记后缺失 DONE 而 EOF 时失败，无修改或可执行检查点调用。失败检查点随后可成功恢复且不重放。所有场景共六个预期请求。通用网关必须收到 stream:true，但不带原生专用 stream_options。

服务完成屏障和子进程截止使失败清理有界；失败时杀死并回收启动的 CLI 进程、释放服务屏障并关闭服务。结束前修改观察及持久化单调用/结果，是本地夹具验收证据，不是所有外部厂商或真实 API 延迟结论。

仅修改新脚本和此交接文档。`python -m py_compile scripts/smoke_native_streaming.py` 语法验证通过。未改生产代码、运行 Maven 或付费调用。端到端执行等待负责人完成原生流传输/解析器集成及新的胖 JAR。
