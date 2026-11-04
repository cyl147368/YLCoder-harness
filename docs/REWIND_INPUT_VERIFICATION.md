# 回退提问输入框回显验收

日期：2026-10-21。

## 原因与修复

本机全局启动器仍指向旧运行包，其中 `CompletingTerminalInput` 没有接收预填草稿的三参数 `readCommand` 方法。项目源码的草稿预填实现尚未部署到全局安装。已重新构建并通过版本化安装器更新全局运行包，保留已有用户配置；已经运行的旧进程需要退出后重新启动。

同时修复了草稿在进入输入读取前被提前清除的问题，并区分支持行编辑的终端和降级输入：普通终端预填所选提问，等待用户编辑后回车；不支持行编辑时明确显示原提问供复制，不再误报已经放入输入框，也不自动发送。

## 验证

- `mvn -B -o verify`：557 项测试，549 项通过、8 项平台条件跳过，0 失败、0 错误。
- `CompletingTerminalInputTest` 使用真实 JLine 行编辑器验证中文、emoji、退格编辑、多行草稿，以及回车前不提交。
- `python scripts/smoke_console_input.py --rewind --result target/rewind-console.json`：项目启动器 6/6 通过。
- 使用同一脚本的 `--launcher` 参数指定本机全局 `ylc.cmd`，输出 `target/rewind-installed-console.json`：全局安装包 6/6 通过。
- 控制台场景包括 `/rewind 2`、双 Esc 选择、任务取消后双 Esc 选择；分别覆盖初始代码页 936 与 65001。
- 检查新输入行实际显示中文、提交前分支保持 READY、编辑后的完整 Unicode 提问传入本地 HTTP 夹具、原会话保留、后续 Tab 补全和任务继续可用。
- `git diff --check` 通过。

控制台测试使用隐藏的真实 Windows 控制台和 Unicode 键盘事件；未调用真实厂商，不模拟物理中文输入法。旧式控制台屏幕读取接口不能可靠还原 emoji 显示格，因此屏幕断言检查中文，emoji 完整性通过 JLine 单元测试及 HTTP 请求内容验证。

## 使用

退出旧的 `ylc`，重新运行 `ylc`，用 `/resume` 和 `/resume 序号` 恢复会话，再输入 `/rewind 序号` 或连按两次 Esc 选择历史提问。所选提问显示在输入框中，编辑后回车才发送。回退不撤销磁盘文件修改。
