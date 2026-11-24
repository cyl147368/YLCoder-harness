# 厂商结束状态和连续性后续审计

2026-10-14 进行了只读源码审计。未编辑厂商/核心源码、未运行 Maven、未发送外部模型流量。下文记录修复前状态。

## 最严重具体问题：缺失完成状态可能授权执行工具

优先级：P1。原生 OpenAI 兼容解码器和 Anthropic 解码器在厂商缺少必需结束标记或返回 null 时，仍虚构成功完成状态。这与代理阻止未完成生成的保护，以及厂商审阅中声称已修复格式错误结束状态的结论矛盾。

证据：

- OpenAiCompatibleProvider.java 第98行读取 finish_reason，默认 stop。共享文本辅助方法对缺失/null 节点也返回该默认值。含调用时，随后 STOP -> TOOL_CALLS 归一化将虚构完成状态提升为可执行工具回合。
- AnthropicProvider.java 第94行读取 stop_reason，默认 end_turn。缺失/null 节点使用此默认值，随后 STOP -> TOOL_CALLS 归一化做同样提升。
- CodeAgent.java 第81–105行拒绝 LENGTH、CONTENT_FILTER、OTHER，然后允许其余非空工具调用列表继续。第110–123行执行请求的本地工具。因此错误厂商结果绕过完成保护；核心无法恢复原始缺失标记。
- 原生 OpenAI、通用兼容/Ollama 端点和 Azure 继承这些 OpenAI 解码语义。Anthropic 独立存在相同问题。
- ProviderAdapterTest.missingResponseEnvelopeIsRejected 只检查整个消息信封缺失，不覆盖存在助手/工具信封但结束状态缺失/null。

### 复现及必要验收

使用现有 localhost HttpServer 模式和指向该服务的模型。返回 HTTP 200，包含以下任一信封：

OpenAI Chat Completions：

    {
      "choices": [{
        "message": {
          "role": "assistant",
          "content": "",
          "tool_calls": [{
            "id": "call_write",
            "type": "function",
            "function": {
              "name": "write_file",
              "arguments": "{\"path\":\"Answer.java\",\"content\":\"changed\"}"
            }
          }]
        }
      }]
    }

Anthropic Messages：

    {
      "content": [{
        "type": "tool_use",
        "id": "call_write",
        "name": "write_file",
        "input": {"path": "Answer.java", "content": "changed"}
      }]
    }

审计时确定性的源码行为：两种 complete 方法都返回带 call_write 的 TOOL_CALLS。将相应标记设为 null 结果相同。无调用但有可见文本、缺失/null 标记的信封返回 STOP，可误报任务完成。

必需结果：本地以脱敏 ProviderException 拒绝各信封，或返回 OTHER 且无可执行调用。要求非空白文本标记，仅映射显式识别值。不能静默替换为成功状态。若归一化是有意设计，保持显式厂商 STOP/end_turn 兼容。

增加参数化适配器测试，覆盖缺失、null、空白、数值/对象和未知标记，有/无调用两种情况。使用记录修改工具的 CodeAgent 测试，断言缺失/null 信封绝不执行工具且不报告 COMPLETED。已有显式 STOP/tool_calls、Anthropic end_turn/tool_use、LENGTH 和拒绝测试应继续通过。不需要真实 API 调用。

## 推理和流式连续性观察

- Responses 显式要求 status，不暴露未完成/拒绝函数调用，并通过元数据保留有序工具回合加密推理。其纯文本最终推理状态仍无元数据载体，后续任务无法回放。这已记录，是兼容性限制，而非本次新发现的完成错误。
- Gemini 显式要求 finishReason，保留函数调用 parts 的签名。其他 thought/文本签名与任意原始 parts 顺序丢失，已有记录。后续完整思考历史实现需要有序厂商消息元数据，而非再次文本拼接。
- Anthropic 忽略未知内容块，包括 thinking/redacted-thinking，并且不回放。当前请求编码器未启用扩展思考，所以普通请求不受影响；外部开启思考的网关需要显式拒绝或保留块的历史支持。
- LangChain4j 流式等待 CompleteResponse，显式映射结束原因，超时/中断时取消订阅，拒绝没有完成事件的流。现有本地 SSE 测试覆盖这些边界。本源码审计未发现更高严重性的新流式问题。

缺失/null 原生结束标记是首先应修复的问题，因为普通默认厂商使用此解码器，结果可直接触发工作区修改。
