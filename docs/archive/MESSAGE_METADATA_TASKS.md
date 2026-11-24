# 消息元数据续接

原指派要求源码/文档/测试使用英文，现以用户最新中文要求为准。所有成员保留其他修改；此代码库中有其他成员。不要运行 Maven 或真实/付费调用。负责人串行验证。仅访问项目文件。

## 负责人集成说明

核心 SPI 和理解元数据的压缩现已实现。交接前确保回放 JSON 拒绝重复对象键和尾随 JSON 值，并严格解析原始与预期的函数参数比较。AbstractHttpChatModel.JSON 当前使用 Jackson 默认宽松解析。为回放和参数校验使用厂商局部的严格 reader/mapper，不改变共享 HTTP 基类。增加格式错误回放回归测试。Gemini 对只有 thought 或空白可见文本的最终成功应一致拒绝。Responses 必须保持已有旧调用元数据测试通过：测试新解码时改用消息元数据，同时保留显式旧夹具覆盖。

## 共享 SPI 契约（负责人负责）

负责人给 ChatMessage 增加不可变 Map<String,String> providerMetadata（第六个字段），给 ChatResponse 增加相同字段（第五个字段）。现有五/四参数构造器保留源码兼容，默认空映射。现有静态辅助方法保留，新增 ChatMessage.assistant(text, metadata)、ChatMessage.assistantToolCalls(text,calls,metadata)、ChatResponse.text(text,metadata)。CodeAgent 在助手消息上保存 response.providerMetadata。ContextCompactor 统计元数据，不裁剪带元数据消息的内容；仍可删除完整、可选的旧块。元数据是不透明回放证据，不是工具或用户指令。Jackson 必须将旧会话中缺失/null 的映射读为空映射。

## responses_acceptance 归属

仅负责 OpenAiResponsesProvider.java、OpenAiResponsesProviderTest.java（或新建 ResponsesMessageMetadataTest.java）和 docs/RESPONSES_CONTINUITY.md。通过消息级 openai.responses.outputItems 元数据修复最终文本回合推理丢失。保留精确、有序且受支持的 output，包括加密推理及助手消息 phase/ID/annotations。将已完成文本和工具回合解码为 ChatResponse 元数据。先验证投影可见文本和预期调用，再以原始回放编码带元数据的助手消息；HTTP 前拒绝格式错误、篡改或重复元数据。不要把只有推理的输出当作成功编码完成。保留旧版首个 ToolCall 元数据会话回退及已有测试/源码用法；拒绝冲突的双副本。保持未完成/拒绝调用不可执行，不把未完成输出持久化为可回放证据。

已抓取官方来源 <https://developers.openai.com/api/docs/guides/reasoning>（HTTP200，2026-10-14）：无状态推理连续性要求保留每个响应输出项并回放完整历史；store:false 默认返回 encrypted_content，旧 include 仍被接受。不要改变模型选择或付费调用。测试应覆盖：最终推理/文本响应后用户继续提问的回放；新载体和旧回退下有序工具回合回放；phase/不透明数据保留；可见文本/调用不匹配、格式错误元数据、缺失加密推理及双副本矛盾在 HTTP 前失败；可行时覆盖真实 CodeAgent 双回合/恢复路径。用明文/共享文档报告，不运行 Maven。

## gemini_continuity 归属

仅负责 GeminiProvider.java、GeminiMetadataTest.java（或新建 GeminiPartsReplayTest.java）和 docs/GEMINI_CONTINUITY.md。通过消息级 gemini.contentParts 元数据保留受支持、有序的 candidate content.parts，包括 thought 文本、文本/thought/function 部分的 thoughtSignature、与调用交错的可见文本，以及可选函数 ID。thought 文本不显示在可见响应中。验证可见文本、函数名称/参数/协议 ID/签名与中立消息/调用匹配后，原样按顺序回放 parts。保留已有调用级元数据/会话回退。HTTP 前拒绝格式错误、不支持的多模态或矛盾 parts/元数据，不静默丢数据；不声称多模态兼容。不虚构签名或协议 ID。未完成/拒绝输出不能暴露可执行调用或可回放元数据。公开签名/token 上限保持中立。需要时可访问公开官方 Gemini thought-signature 指南，绝不访问凭据。测试：双回合交错 parts，含 thought/文本签名和并行调用；最终文本/thought 后的用户回放；可选签名/ID 缺失；篡改投影在 HTTP 前失败；不支持/格式错误 parts 拒绝；旧调用元数据回退。不要修改核心/CLI 或运行 Maven。用明文/共享文档报告。

## cli_acceptance 归属

仅负责新建 SessionMessageMetadataTest.java、TokenizerInputTokenCounterTest.java 的新增测试和 docs/MESSAGE_METADATA_ACCEPTANCE.md。不要改生产代码、核心或厂商代码。验证旧会话（缺失/null ChatMessage 元数据）读为空映射，以及新不可变消息元数据通过 SessionStore/真实进程持久化后逐值、逐字节往返一致。增加最终回答携带消息元数据、随后用户追问的检查点/恢复测试，断言元数据原样传入模拟模型且不增加修改。验证 tokenizer 统计大型不透明消息元数据，在必需元数据无法放入预算时于模型请求前失败；不调用 API。测试应有效且不依赖私有实现细节。不要运行 Maven。用明文/共享文档报告。
