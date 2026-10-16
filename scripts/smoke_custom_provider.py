"""在项目源码之外编译并运行 ServiceLoader 厂商扩展。

无需模型调用，即可验证公开厂商 SPI 和 CLI 类路径扩展入口。
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
PROVIDER_ID = 'custom-fixture'


SOURCE = '''
package external.fixture;

import dev.codeharness.core.model.ChatModel;
import dev.codeharness.core.model.ChatRequest;
import dev.codeharness.core.model.ChatResponse;
import dev.codeharness.core.model.ToolCall;
import dev.codeharness.core.model.FinishReason;
import dev.codeharness.core.model.TokenUsage;
import java.util.List;
import java.util.Map;
import dev.codeharness.providers.ModelProvider;
import dev.codeharness.providers.ProviderConfig;

public final class FixtureProvider implements ModelProvider {
    @Override public String id() { return "custom-fixture"; }
    @Override public ChatModel create(ProviderConfig config) {
        if (!"fixture-model".equals(config.model())) throw new IllegalArgumentException("unexpected model");
        if (config.apiKey() != null || !config.headers().isEmpty()) throw new IllegalArgumentException("unexpected credentials");
        return request -> {
            boolean resumed = request.messages().stream().anyMatch(m -> m.content().equals("验证恢复后的厂商元数据。"));
            if (resumed) {
                if (request.messages().stream().noneMatch(m -> "final-proof".equals(m.providerMetadata().get("custom.fixture")))) {
                    throw new IllegalStateException("Message metadata did not survive resume");
                }
                return ChatResponse.text("自定义厂商恢复后保留精确元数据。");
            }
            boolean wrote = request.messages().stream().anyMatch(m -> "custom-write".equals(m.toolCallId()));
            boolean read = request.messages().stream().anyMatch(m -> "custom-read".equals(m.toolCallId()));
            if (wrote && read) {
                if (request.messages().stream().noneMatch(m -> "custom-read".equals(m.toolCallId()) && m.content().contains("custom extension"))) {
                    throw new IllegalStateException("Read result is missing");
                }
                return ChatResponse.text("已通过 ServiceLoader 加载自定义厂商。", Map.of("custom.fixture", "final-proof"));
            }
            ToolCall call = wrote
                ? new ToolCall("custom-read", "read_file", "{\\"path\\":\\"extension.txt\\"}")
                : new ToolCall("custom-write", "write_file", "{\\"path\\":\\"extension.txt\\",\\"content\\":\\"custom extension\\"}");
            return new ChatResponse("", List.of(call), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        };
    }
}
'''


def run(command, cwd, **kwargs):
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                          encoding='utf-8', timeout=30, **kwargs)


def main():
    assert JAR.is_file(), '请先使用 mvn verify 构建 shaded jar'
    java = os.environ.get('JAVA_HOME')
    javac = str(Path(java) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')) if java else 'javac'
    java_bin = str(Path(java) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java else 'java'
    with tempfile.TemporaryDirectory(prefix='harness-custom-provider-') as temporary:
        root = Path(temporary)
        source = root / 'src/external/fixture/FixtureProvider.java'
        source.parent.mkdir(parents=True)
        source.write_text(SOURCE, encoding='utf-8')
        classes = root / 'classes'
        compiled = run([javac, '-encoding', 'UTF-8', '-proc:none', '-cp', str(JAR), '-d', str(classes), str(source)], root)
        assert compiled.returncode == 0, (compiled.stdout, compiled.stderr)
        services = classes / 'META-INF/services/dev.codeharness.providers.ModelProvider'
        services.parent.mkdir(parents=True)
        services.write_text('external.fixture.FixtureProvider\n', encoding='utf-8')
        extension = root / 'custom-provider.jar'
        with zipfile.ZipFile(extension, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in classes.rglob('*'):
                if path.is_file(): archive.write(path, path.relative_to(classes).as_posix())
        workspace = root / 'workspace'
        workspace.mkdir()
        config = root / 'offline.yaml'
        config.write_text('api-key-env: ""\n', encoding='utf-8')
        common = [java_bin, '-cp', os.pathsep.join((str(JAR), str(extension))),
                   'dev.codeharness.cli.HarnessCli', '--workspace', str(workspace),
                   '--config', str(config),
                   '--provider', PROVIDER_ID, '--model', 'fixture-model', '--json', '--quiet',
                   ]
        result = run(common + ['run', '写入并读取自定义扩展文件。'], root)
        assert result.returncode == 0, (result.stdout, result.stderr)
        payload = json.loads(result.stdout)
        assert payload['status'] == 'COMPLETED' and payload['answer'] == '已通过 ServiceLoader 加载自定义厂商。', payload
        assert (workspace / 'extension.txt').read_text(encoding='utf-8') == 'custom extension'
        resumed = run(common + ['resume', payload['session'], '验证恢复后的厂商元数据。'], root)
        assert resumed.returncode == 0, (resumed.stdout, resumed.stderr)
        resumed_payload = json.loads(resumed.stdout)
        assert resumed_payload['status'] == 'COMPLETED' and resumed_payload['answer'] == '自定义厂商恢复后保留精确元数据。', resumed_payload
        assert resumed_payload['session'] == payload['session']
        providers = run([java_bin, '-cp', os.pathsep.join((str(JAR), str(extension))),
                         'dev.codeharness.cli.HarnessCli', 'providers'], root)
        assert providers.returncode == 0 and PROVIDER_ID in providers.stdout.splitlines(), providers.stdout
        print(json.dumps({'status': 'PASS', 'provider': PROVIDER_ID,
                          'checks': ['外部 javac 编译', 'ServiceLoader registration',
                                     '真实 CLI 写入和读取调用', '跨进程元数据恢复',
                                     'providers discovery', '不使用网络或凭据']}, indent=2))


if __name__ == '__main__':
    main()
