# 最终恢复测试交接

负责人：cli_acceptance。其他成员也在此代码库工作，请保留他们的修改。

仅负责 SessionRecoveryProcessTest.java 和 docs/RECOVERY_ACCEPTANCE.md。为子进程崩溃夹具增加不透明的 ToolCall 厂商元数据，并断言原样的映射传入恢复后的模型，且保留在已完成的持久化检查点中。现有检查已覆盖厂商和模型标签；报告必须区分这些标签与不透明工具元数据。不要修改生产代码，不要运行 Maven。使用明文报告完成情况，并更新交接文件。
