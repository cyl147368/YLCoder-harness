"""用本地模拟厂商和真实离线 Maven 构建验证长命令审核；不读取用户私有配置。"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[1]
SOURCE_JAR = ROOT / 'harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar'
JAR = SOURCE_JAR if SOURCE_JAR.is_file() else ROOT / 'lib/ylc.jar'
COMMAND = ('mvn -o -pl soulclaw-knowledge-server -am -DskipTests=false '
           '-Dtest=ApiKeyFilterTest,MarkdownPathResolverTest '
           '-Dsurefire.failIfNoSpecifiedTests=false test')
MODULE = 'soulclaw-knowledge-server'
TEST_CLASSES = ('ApiKeyFilterTest', 'MarkdownPathResolverTest')
TASK = '运行工作区中的 Maven 测试，确认两个指定测试都通过。'
ASSISTANT_PROSE = 'ASSISTANT_TEXT_MUST_NOT_ENTER_MAVEN_REVIEW'
REVIEW_REASON = 'RAW_MAVEN_REVIEW_REASON_MUST_NOT_APPEAR'
REVIEW_FAILURE = 'RAW_MAVEN_REVIEW_ERROR_MUST_NOT_APPEAR'
CASES = ('approved', 'approved-cache', 'manual-mode', 'ai-manual', 'ai-failure',
         'blocked-clean', 'blocked-deploy', 'blocked-chain')
ENVIRONMENT_HOOKS = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH',
                     'JVM_CONFIG_MAVEN_PROPS', 'JAVACMD', 'JAVA_HOME', 'M2_HOME'}


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def java_executable():
    """只寻找已安装的 Java，不通过 Maven 脚本读取用户环境配置。"""
    configured = os.environ.get('JAVA_HOME')
    candidate = Path(configured) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if configured else None
    executable = candidate if candidate and candidate.is_file() else shutil.which('java')
    if not executable:
        raise RuntimeError('真实 Maven 验收需要已安装的 Java 21')
    return Path(executable).resolve()


def maven_home(configured=None):
    if configured:
        candidates = [configured]
    else:
        marker = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
        candidates = [Path(marker).resolve().parent.parent] if marker else []
        candidates.extend(Path(os.environ[name]) for name in ('MAVEN_HOME', 'M2_HOME') if os.environ.get(name))
    for candidate in candidates:
        candidate = candidate.resolve()
        if (candidate / 'bin/m2.conf').is_file() and (candidate / 'boot').is_dir() and (candidate / 'lib').is_dir():
            return candidate
    raise RuntimeError('找不到标准 Maven 3.9 安装；可通过 --maven-home 指定，不会读取其 settings.xml')


def prepare_runtime(base, installed, repository):
    """复制标准二进制，仅为此次验收生成全新的 Maven 安装配置。"""
    copied = base / 'maven-home'
    for folder in ('bin', 'boot', 'lib'):
        shutil.copytree(installed / folder, copied / folder)
    # 安装中的全局 settings、toolchains 和扩展不属于验收输入；只复制日志格式资源。
    logging = installed / 'conf/logging'
    if logging.is_dir():
        shutil.copytree(logging, copied / 'conf/logging')
    else:
        (copied / 'conf/logging').mkdir(parents=True)
    # 使用固定 Classworlds 配置，避免继承安装中额外的配置文件入口。
    write(copied / 'bin/m2.conf', '''main is org.apache.maven.cli.MavenCli from plexus.core

set maven.conf default ${maven.home}/conf

[plexus.core]
load ${maven.conf}/logging
load ${maven.home}/lib/*.jar
''')
    repositories = ''.join(
        f'<repository><id>{identifier}</id><url>https://repo.maven.apache.org/maven2</url></repository>'
        for identifier in ('baai-group', 'central'))
    plugin_repositories = repositories.replace('<repository>', '<pluginRepository>').replace('</repository>', '</pluginRepository>')
    write(copied / 'conf/settings.xml', f'''<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <localRepository>{escape(str(repository))}</localRepository>
  <offline>true</offline>
  <profiles><profile><id>isolated-offline-validation</id>
    <repositories>{repositories}</repositories>
    <pluginRepositories>{plugin_repositories}</pluginRepositories>
  </profile></profiles>
  <activeProfiles><activeProfile>isolated-offline-validation</activeProfile></activeProfiles>
</settings>
''')
    write(copied / 'conf/toolchains.xml', '<toolchains/>\n')
    return copied


def prepare_fixture(workspace, user_home):
    workspace.mkdir(parents=True)
    write(user_home / '.m2/settings.xml', '''<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <profiles><profile><id>isolated-user</id>
    <properties><smoke.user.settings>isolated-user-settings</smoke.user.settings></properties>
  </profile></profiles>
  <activeProfiles><activeProfile>isolated-user</activeProfile></activeProfiles>
</settings>
''')
    expected_home = escape(str(user_home))
    write(workspace / 'pom.xml', f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.smoke</groupId><artifactId>maven-review-fixture</artifactId><version>1.0</version>
  <packaging>pom</packaging><modules><module>{MODULE}</module></modules>
  <properties><maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.4.0</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>
      <configuration><systemPropertyVariables>
        <fixture.expected.home>{expected_home}</fixture.expected.home>
        <fixture.maven.user.home>${{user.home}}</fixture.maven.user.home>
        <fixture.user.settings>${{smoke.user.settings}}</fixture.user.settings>
      </systemPropertyVariables></configuration>
    </plugin>
  </plugins></build>
</project>
''')
    write(workspace / MODULE / 'pom.xml', f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>local.smoke</groupId><artifactId>maven-review-fixture</artifactId><version>1.0</version></parent>
  <artifactId>{MODULE}</artifactId>
  <dependencies>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-api</artifactId>
      <version>5.11.4</version><scope>test</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-engine</artifactId>
      <version>5.11.4</version><scope>test</scope></dependency>
  </dependencies>
</project>
''')
    for name in TEST_CLASSES:
        write(workspace / MODULE / 'src/test/java' / (name + '.java'), '''import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ''' + name + ''' {
    @Test void 本地构建使用隔离配置并真实执行测试() throws Exception {
        assertEquals(42, 6 * 7);
        assertEquals(Path.of(System.getProperty("fixture.expected.home")).toRealPath(),
                Path.of(System.getProperty("fixture.maven.user.home")).toRealPath());
        assertEquals("isolated-user-settings", System.getProperty("fixture.user.settings"));
        Files.writeString(Path.of("target", "maven-review-runs.txt"), "''' + name + '''\\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
''')
    # 如果审批链错误地启动工作区脚本，将留下标记；正常流程应直接启动绑定的外部 Java。
    write(workspace / 'mvn.cmd', '@echo WRONG_MAVEN_ENTRYPOINT>maven-script-executed.txt\n')
    write(workspace / 'mvn', '#!/bin/sh\nprintf WRONG_MAVEN_ENTRYPOINT > maven-script-executed.txt\n')
    (workspace / 'mvn').chmod(0o755)


def environment_for(runtime, user_home, java):
    environment = {key: value for key, value in os.environ.items()
                   if key.upper() not in ENVIRONMENT_HOOKS and not key.upper().startswith(('MAVEN_', 'HARNESS_', 'YLC_'))}
    environment.update({'JAVA_HOME': str(java.parent.parent), 'MAVEN_HOME': str(runtime), 'M2_HOME': str(runtime),
                        'HOME': str(user_home), 'USERPROFILE': str(user_home),
                        'XDG_CONFIG_HOME': str(user_home / '.config')})
    environment['PATH'] = os.pathsep.join((str(runtime / 'bin'), str(java.parent), environment.get('PATH', '')))
    return environment


def build_reports(workspace, expected_runs):
    reports = []
    folder = workspace / MODULE / 'target/surefire-reports'
    actual = sorted(folder.glob('TEST-*.xml')) if folder.is_dir() else []
    if not expected_runs:
        assert not actual and not (workspace / MODULE / 'target').exists(), '未批准的命令不能执行 Maven 构建'
        return reports
    assert {path.stem for path in actual} == {'TEST-' + name for name in TEST_CLASSES}, actual
    for path in actual:
        suite = ET.parse(path).getroot()
        assert suite.attrib['tests'] == '1' and suite.attrib['failures'] == '0' and suite.attrib['errors'] == '0', suite.attrib
        assert suite.attrib.get('skipped', '0') == '0', suite.attrib
        reports.append({'name': suite.attrib['name'], 'tests': 1, 'failures': 0, 'errors': 0})
    runs = (workspace / MODULE / 'target/maven-review-runs.txt').read_text(encoding='utf-8').splitlines()
    assert len(runs) == 2 * expected_runs and all(runs.count(name) == expected_runs for name in TEST_CLASSES), runs
    return reports


def verify_fixture(installed=None, repository=None):
    """仅预检离线依赖和测试样本，不把此结果当作审核链已通过。"""
    java = java_executable()
    repository = (repository or Path.home() / '.m2/repository').resolve()
    if not repository.is_dir():
        raise RuntimeError('本地 Maven 缓存不存在；不会下载依赖')
    with tempfile.TemporaryDirectory(prefix='ylc-maven-fixture-') as temporary:
        base = Path(temporary).resolve()
        runtime = prepare_runtime(base, maven_home(installed), repository)
        workspace, user_home = base / 'workspace', base / 'home'
        prepare_fixture(workspace, user_home)
        classworlds, = (runtime / 'boot').glob('plexus-classworlds-*.jar')
        arguments = [str(java), '-Duser.home=' + str(user_home), '-Dfile.encoding=UTF-8',
                     '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-classpath', str(classworlds),
                     '-Dclassworlds.conf=' + str(runtime / 'bin/m2.conf'), '-Dmaven.home=' + str(runtime),
                     '-Dmaven.conf=' + str(runtime / 'conf'), '-Dmaven.multiModuleProjectDirectory=' + str(workspace),
                     'org.codehaus.plexus.classworlds.launcher.Launcher', '-B', '-e', '-Dstyle.color=never', *COMMAND.split()[1:]]
        run = subprocess.run(arguments, cwd=workspace, env=environment_for(runtime, user_home, java),
                             capture_output=True, text=True, encoding='utf-8', timeout=120)
        assert run.returncode == 0 and 'BUILD SUCCESS' in run.stdout, (run.stdout, run.stderr)
        reports = build_reports(workspace, 1)
    return {'status': 'PASS', 'scope': '仅预检真实离线 Maven 测试样本；尚未验证 CLI 审核链。', 'surefireReports': reports}


def verify_maven(launcher=None, installed=None, repository=None):
    java = java_executable()
    installed = maven_home(installed)
    repository = (repository or Path.home() / '.m2/repository').resolve()
    if not repository.is_dir():
        raise RuntimeError('本地 Maven 缓存不存在；用 --local-repository 指定已有缓存，不会下载依赖')
    records = []
    with tempfile.TemporaryDirectory(prefix='ylc-maven-review-') as temporary:
        base = Path(temporary).resolve()
        runtime = prepare_runtime(base, installed, repository)
        for case in CASES:
            workspace = base / 'workspaces' / case
            user_home = base / 'homes' / case
            prepare_fixture(workspace, user_home)
            command = COMMAND
            if case == 'blocked-clean':
                command = 'mvn -o clean test'
            elif case == 'blocked-deploy':
                command = 'mvn -o deploy'
            elif case == 'blocked-chain':
                command = COMMAND + ' && echo MAVEN_SHOULD_NOT_EXECUTE > blocked.txt'
            allowed = case in ('approved', 'approved-cache')
            repeated = case == 'approved-cache'
            expected_runs = 2 if repeated else int(allowed)
            expected_reviews = int(case in ('approved', 'approved-cache', 'ai-manual', 'ai-failure'))
            state = {'main': [], 'review': [], 'errors': [], 'outputs': []}

            class Provider(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def do_POST(self):
                    try:
                        assert 'Authorization' not in self.headers
                        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                        reviewer = self.path == '/review/v1/chat/completions'
                        assert self.path in ('/main/v1/chat/completions', '/review/v1/chat/completions'), self.path
                        state['review' if reviewer else 'main'].append(body)
                        if reviewer:
                            assert not body.get('tools') and not body.get('stream') and body['model'] == 'offline-maven-reviewer'
                            assert len(body['messages']) == 2 and body['max_tokens'] == 192
                            data = json.loads(body['messages'][-1]['content'])
                            assert data['command'] == command and len(data['command']) > 128, data
                            assert data['actionType'] == 'maven-build' and data['build']['offline'] is True, data
                            assert data['build']['goals'] == ['test'] and MODULE in data['build']['modules'], data
                            assert any(item.get('role') == 'user' and item.get('content') == TASK for item in data['context']), data
                            assert ASSISTANT_PROSE not in json.dumps(body, ensure_ascii=False)
                            assert Path(data['arguments'][0]).samefile(java), data['arguments']
                            assert 'org.codehaus.plexus.classworlds.launcher.Launcher' in data['arguments'], data['arguments']
                            if case == 'ai-failure':
                                self.send_response(503)
                                self.end_headers()
                                self.wfile.write(REVIEW_FAILURE.encode('ascii'))
                                return
                            message = {'role': 'assistant', 'content': json.dumps({
                                'decision': 'manual' if case == 'ai-manual' else 'approve', 'reason': REVIEW_REASON})}
                        elif len(state['main']) == 1:
                            assert body['model'] == 'offline-maven-main' and body.get('tools'), body
                            assert [m['content'] for m in body['messages'] if m['role'] == 'user'] == [TASK]
                            message = {'role': 'assistant', 'content': ASSISTANT_PROSE, 'tool_calls': [
                                {'id': f'maven-review-{index}', 'type': 'function', 'function': {
                                    'name': 'run_command', 'arguments': json.dumps({'command': command})}}
                                for index in range(2 if repeated else 1)]}
                        else:
                            assert len(state['main']) == 2
                            outputs = [m['content'] for m in body['messages'] if m['role'] == 'tool']
                            state['outputs'] = outputs
                            assert len(outputs) == (2 if repeated else 1), outputs
                            assert all(('退出码：0' if allowed else '未获批准') in output for output in outputs), outputs
                            if allowed:
                                assert all('BUILD SUCCESS' in output for output in outputs), outputs
                                assert all(all(name in output for name in TEST_CLASSES) for output in outputs), outputs
                            message = {'role': 'assistant', 'content': 'Maven 审核验收完成。'}
                        payload = json.dumps({'choices': [{'message': message,
                            'finish_reason': 'tool_calls' if message.get('tool_calls') else 'stop'}],
                            'usage': {'prompt_tokens': 50 if reviewer else 13,
                                      'completion_tokens': 7 if reviewer else 3}}).encode('utf-8')
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(payload)))
                        self.end_headers()
                        self.wfile.write(payload)
                    except Exception as failure:
                        state['errors'].append(repr(failure))
                        self.send_response(500)
                        self.end_headers()

            with ThreadingHTTPServer(('127.0.0.1', 0), Provider) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                url = f'http://127.0.0.1:{server.server_port}'
                config = workspace / 'offline.yaml'
                write(config, f'''provider: openai-compatible
model: offline-maven-main
base-url: {url}/main/v1
api-key-env: HARNESS_MAVEN_UNUSED
web:
  enabled: false
permissions:
  mode: {'manual' if case == 'manual-mode' else 'review'}
  reviewer:
    provider: openai-compatible
    model: offline-maven-reviewer
    base-url: {url}/review/v1
    api-key-env: HARNESS_MAVEN_UNUSED
    header-env: {{}}
''')
                environment = environment_for(runtime, user_home, java)
                if launcher:
                    launch = [str(Path(launcher).resolve())]
                    # 安装启动器保留真实调用路径；父 JVM 显式选择临时 home，Maven 子进程沿用同一目录。
                    environment['JDK_JAVA_OPTIONS'] = '"-Duser.home=' + str(user_home) + '"'
                else:
                    launch = [str(java), '--disable-@files', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                              '-Dstderr.encoding=UTF-8', '-Duser.home=' + str(user_home), '-jar', str(JAR)]
                arguments = ['--config', str(config), '--workspace', str(workspace), '--no-stream', '--quiet', '--json', 'run', TASK]
                if os.name == 'nt':
                    write(workspace / 'cli-arguments.txt', '\n'.join(json.dumps(value, ensure_ascii=False) for value in arguments) + '\n')
                    arguments = ['@cli-arguments.txt']
                try:
                    run = subprocess.run(launch + arguments, cwd=workspace, env=environment, input='',
                                         text=True, encoding='utf-8', capture_output=True, timeout=210)
                finally:
                    server.shutdown()
                    thread.join(5)
            assert run.returncode == 0, (case, run.stdout, run.stderr, state['errors'])
            assert not state['errors'], (case, state['errors'])
            result = json.loads(run.stdout)
            assert result['status'] == 'COMPLETED' and result['iterations'] == 2, (case, result)
            assert '[y/N]' not in run.stderr, '非交互验收不能等待人工输入'
            assert REVIEW_REASON not in run.stdout + run.stderr and REVIEW_FAILURE not in run.stdout + run.stderr
            assert not (workspace / 'blocked.txt').exists() and not (workspace / 'maven-script-executed.txt').exists()
            review = result['review']
            assert len(state['main']) == 2 and len(state['review']) == expected_reviews, (case, state)
            assert review['requests'] == expected_reviews and review['localApprovals'] == 0, (case, review)
            assert review['approved'] == expected_runs and review['cacheHits'] == int(repeated), (case, review)
            assert review['manual'] == int(not allowed and case != 'manual-mode'), (case, review)
            assert review['unreportedRequests'] == int(case == 'ai-failure'), (case, review)
            reports = build_reports(workspace, expected_runs)
            records.append({'case': case, 'status': 'PASS', 'command': command, 'commandChars': len(command),
                            'mainRequests': len(state['main']), 'reviewRequests': len(state['review']),
                            'review': review, 'mavenExecutions': expected_runs, 'surefireReports': reports,
                            'toolOutputSha256': [hashlib.sha256(output.encode('utf-8')).hexdigest() for output in state['outputs']]})
    return {'status': 'PASS', 'cases': records,
            'localhostRequests': sum(item['mainRequests'] + item['reviewRequests'] for item in records),
            'mavenExecutions': sum(item['mavenExecutions'] for item in records),
            'testExecutions': 2 * sum(item['mavenExecutions'] for item in records),
            'scope': 'localhost 模拟主模型与独立审核模型；真实 Maven 离线运行两个临时 JUnit 测试；不读取用户私有配置，不调用真实厂商。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launcher', type=Path, help='可选的已安装 ylc 启动器；省略时检查源码构建 JAR')
    parser.add_argument('--maven-home', type=Path, help='标准 Maven 3.9 安装目录，只复用二进制与日志格式资源')
    parser.add_argument('--local-repository', type=Path, help='已准备好的离线缓存，默认 ~/.m2/repository')
    parser.add_argument('--result', type=Path, help='保存不含凭据的 JSON 验收证据')
    parser.add_argument('--fixture-only', action='store_true', help='只预检真实离线 Maven 测试样本；不代表审核链通过')
    options = parser.parse_args()
    result = (verify_fixture(options.maven_home, options.local_repository) if options.fixture_only
              else verify_maven(options.launcher, options.maven_home, options.local_repository))
    if options.result:
        write(options.result, json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
