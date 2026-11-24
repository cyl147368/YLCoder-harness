# 可移植交付包验收

负责人负责 scripts/package_distribution.py、packaging/bin/harness、packaging/bin/harness.cmd、docs/DISTRIBUTION.md 和所有其他文件。cli_acceptance 仅负责 scripts/smoke_distribution.py。其他成员也在工作，请保留他们的成果。不要运行 Maven 或真实调用。为新的可移植归档实现独立冒烟测试。

契约：`python scripts/package_distribution.py` 生成 target/distributions/java-code-harness-0.1.0-SNAPSHOT.zip、.tar.gz 和 SHA256SUMS。归档根目录 java-code-harness-0.1.0-SNAPSHOT 包含 bin/harness（POSIX 可执行）、bin/harness.cmd、lib/harness.jar、config.example.yaml、README.md、docs 和 manifest.json。manifest 结构为 {version, files:{relative-path:sha256}}，不包含 manifest 自身。使用显式文件允许列表，不包含 .harness、日志、target 内容或凭据。

冒烟测试应验证两种归档的名称和安全路径、相同的解压字节及 manifest 哈希、tar/zip 可执行元数据、旁置校验和；然后把 ZIP 解压到包含空格的临时路径。从另一个包含空格的当前目录，用当前平台启动器运行。测试 --help、providers（8 个 ID）、离线 doctor，以及无效参数保持退出码 2。使用显式离线配置和 localhost 厂商执行简单写入/读取任务（无 shell 权限）；确保文件和会话生成在调用工作区或显式 --workspace 中，不能写到交付包当前目录；测试实际 JAR。原生适配器允许 JSON 回退。不要付费调用。不要打包夹具或创建生产文件。

消息无法读取时，CLI_ACCEPTANCE_TASK.md 是入口。负责人在冒烟之前构建归档。最终使用明文报告所有假设和缺陷。
