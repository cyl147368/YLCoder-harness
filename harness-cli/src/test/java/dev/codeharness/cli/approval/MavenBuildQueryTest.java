package dev.codeharness.cli.approval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MavenBuildQueryTest {
    private static final String REPORTED = "mvn -o -pl soulclaw-knowledge-server -am -DskipTests=false -Dtest=ApiKeyFilterTest,MarkdownPathResolverTest -Dsurefire.failIfNoSpecifiedTests=false test";

    @Test void 超过旧128字符的用户命令成为构建审核候选() {
        assertTrue(REPORTED.length() > 128);
        var query = parse(REPORTED);
        assertEquals(List.of("test"), query.goals());
        assertEquals(List.of("soulclaw-knowledge-server"), query.modules());
        assertTrue(query.offline());
        assertEquals(List.of("-o", "-pl=soulclaw-knowledge-server", "-am", "-DskipTests=false",
                "-Dtest=ApiKeyFilterTest,MarkdownPathResolverTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"), query.arguments());
        assertEquals(List.of(), query.profiles());
    }

    @Test void 普通生命周期组合与无值标量属性可以解析() {
        var query = parse("MVN.CMD -B -ntp -q -e -amd -DskipTests -Dmaven.test.skip=false -Dmaven.compiler.release=21 validate compile test-compile test package verify");
        assertFalse(query.offline());
        assertEquals(List.of("validate", "compile", "test-compile", "test", "package", "verify"), query.goals());
        assertNotNull(MavenBuildQuery.parse("mvn --offline --batch-mode --no-transfer-progress --quiet --errors --also-make --also-make-dependents test"));
        assertNotNull(MavenBuildQuery.parse("mvn -Dfile.encoding=UTF-8 -Dstyle.color=never -Dcustom.retries=3 -D custom.enabled=true test"));
        assertNotNull(MavenBuildQuery.parse("mvn -Dempty= -Dnegative=-1 -Dtest='Foo*,Bar#method' test"));
    }

    @Test void 项目和profile支持长短参数及附值() {
        var query = parse("mvn --projects=module-a,nested/module-b,:artifact-id -Pdev,!slow,-legacy,+integration --activate-profiles=ci test");
        assertEquals(List.of("module-a", "nested/module-b", ":artifact-id"), query.modules());
        assertEquals(List.of("dev", "!slow", "-legacy", "+integration", "ci"), query.profiles());
        assertEquals(List.of("-pl=module-a,nested/module-b,:artifact-id", "-Pdev,!slow,!legacy,+integration", "-Pci", "test"), query.arguments());
        assertEquals(List.of("one", "two"), parse("mvn -plone -pl=two compile").modules());
        assertEquals(List.of("dev", "test"), parse("mvn -P=dev --activate-profiles test verify").profiles());
        assertEquals(List.of(".", "./module", "src\\nested"), parse("mvn -pl . -pl ./module -pl src\\nested test").modules());
    }

    @Test void 引号保留参数空格且参数列表不含程序名() {
        var query = parse("mvn -pl 'module with spaces' -Ddescription=\"test build\" '-Dtest=Foo*,Bar#method' test");
        assertEquals(List.of("module with spaces"), query.modules());
        assertEquals(List.of("-pl=module with spaces", "-Ddescription=test build", "-Dtest=Foo*,Bar#method", "test"), query.arguments());
        assertNull(MavenBuildQuery.parse("mvn '-pl module with spaces' test"));
        assertNull(MavenBuildQuery.parse("mvn -Dtest='unterminated test"));
        assertNull(MavenBuildQuery.parse("mvn -pl '' test"));
    }

    @Test void 单独D参数保持单个属性边界而不会产生新的选项() {
        var query = parse("mvn -D 'description=module build' -D test=Foo*,Bar#method -Dcustom.flag=-q test");
        assertEquals(List.of("-Ddescription=module build", "-Dtest=Foo*,Bar#method", "-Dcustom.flag=-q", "test"), query.arguments());
        assertEquals(List.of("test"), query.goals());
        assertNull(MavenBuildQuery.parse("mvn -D -f test"));
        assertNull(MavenBuildQuery.parse("mvn -D '--settings=other' test"));
        assertNull(MavenBuildQuery.parse("mvn -D 'description=word\\\" -f other' test"));
        assertNull(MavenBuildQuery.parse("mvn -D 'description=!TEMP!' test"));
    }

    @Test void 带值选项规范化避免负profile与空格参数被重新拆分() {
        var query = parse("mvn -P -q --activate-profiles -dev -pl='module with spaces' --projects :api -D custom.enabled=true test");
        assertEquals(List.of("-P!q", "-P!dev", "-pl=module with spaces", "-pl=:api", "-Dcustom.enabled=true", "test"), query.arguments());
        assertEquals(List.of("-q", "-dev"), query.profiles());
        assertEquals(List.of("module with spaces", ":api"), query.modules());
        assertEquals(parse("mvn -pl api -P dev -D custom=true test").arguments(),
                parse("mvn --projects=api --activate-profiles=dev -Dcustom=true test").arguments());
    }

    @Test void 不接受破坏发布目标或任意插件执行入口() {
        for (String command : List.of("mvn clean test", "mvn install", "mvn deploy", "mvn help:effective-pom",
                "mvn dependency:tree", "mvn exec:java", "mvn org.example:plugin:1:run", "mvn --version", "mvn -o",
                "mvn -f child/pom.xml test", "mvn --file=child/pom.xml test", "mvn -s settings.xml test",
                "mvn -gs settings.xml test", "mvn -t toolchains.xml test", "mvn --settings settings.xml test",
                "mvn -T 4 test", "mvn -X test", "mvn test --", "mvn -pl test", "mvn -P test",
                "mvn.exe test", "mvnw test", "C:\\tools\\mvn.cmd test"))
            assertNull(MavenBuildQuery.parse(command), command);
    }

    @Test void 工作区外模块和歧义模块列表被拒绝() {
        for (String module : List.of("..", "../module", "module/../other", "C:\\outside", "C:/outside", "/tmp",
                "\\\\server\\share", "~", "module/*", ":", ":../escape", "a,,b", ",a", "a,", "module/",
                ".git", ".harness", "module/.git", "module.", " module", "module ", "-outside"))
            assertNull(MavenBuildQuery.parse("mvn -pl '" + module + "' test"), module);
        for (String profile : List.of("", "-", "!", "+", "../dev", "dev,,ci", "dev,", "dev profile"))
            assertNull(MavenBuildQuery.parse("mvn -P '" + profile + "' test"), profile);
    }

    @Test void 凭据路径JVM与执行入口属性不会进入自动审核() {
        for (String property : List.of("maven.home=other", "maven.multiModuleProjectDirectory=other", "maven.repo.local=repo",
                "argLine=-Xmx2g", "surefire.argLine=-Xmx2g", "maven.ext.class.path=extensions", "java.home=other",
                "java.security.properties=security", "exec.executable=cmd", "exec.args=hello", "maven.compiler.executable=javac",
                "compilerArgs=-proc", "jvmArgs=-Xmx2g", "project.build.outputDirectory=output", "custom.working-directory=elsewhere",
                "custom.command=run", "custom.script=script", "settingsFile=settings", "toolchain=other", "custom.password=value",
                "custom.passwd=value", "custom.secret=value", "auth.token=value", "api-key=value", "api_key=value",
                "APIKey=value", "authorization=value", "credentials=value", "private-key=value",
                "classworlds.conf=custom", "classworlds.conf.url=custom", "library.jansi.path=custom",
                "jansi.library.path=custom", "jansi.tmpdir=custom", "Classworlds_Conf=custom", "LIBRARY-JANSI-PATH=custom"))
            assertNull(MavenBuildQuery.parse("mvn -D" + property + " test"), property.substring(0, property.indexOf('=')));
        assertNull(MavenBuildQuery.parse("mvn -D classworlds.conf=custom test"));
        assertNull(MavenBuildQuery.parse("mvn '-Dlibrary.jansi.path=custom' test"));
    }

    @Test void 属性值必须是普通标量而非外部路径或脚本表达式() {
        for (String value : List.of("../out", "a/../out", "/tmp", "C:\\temp", "https://example.test", "a/b", "a\\b", "~=x",
                "a=b", "value;echo", "value|echo", "value&echo", "$(whoami)", "`whoami`", "%TEMP%", "$HOME",
                "@{value}", "(run)", "<file", ">file", " value", "value "))
            assertNull(MavenBuildQuery.parse("mvn -Dcustom='" + value + "' test"));
        for (String name : List.of("", "name:value", "name/value", "name value", "-name", ".name"))
            assertNull(MavenBuildQuery.parse("mvn '-D" + name + "=ok' test"));
    }

    @Test void 控制字符命令展开与串接无论引号均拒绝() {
        for (String suffix : List.of(" && echo x", " | head -10", " > output.txt", ";echo x", "\n", "\r", "\t", "\u001b", "\u202e"))
            assertNull(MavenBuildQuery.parse("mvn test" + suffix));
        assertNull(MavenBuildQuery.parse("mvn -Dcustom='hello&bye' test"));
        assertNull(MavenBuildQuery.parse("mvn test $()"));
        assertNull(MavenBuildQuery.parse("mvn te^st"));
        assertNull(MavenBuildQuery.parse(null));
    }

    @Test void 长度数量与不可变结果都有边界() {
        String atLimit = "mvn" + " ".repeat(4096 - "mvn test".length() + 1) + "test";
        assertEquals(4096, atLimit.length());
        assertNotNull(MavenBuildQuery.parse(atLimit));
        assertNull(MavenBuildQuery.parse(atLimit + " "));
        assertNotNull(MavenBuildQuery.parse("mvn" + " -o".repeat(126) + " test"));
        assertNull(MavenBuildQuery.parse("mvn" + " -o".repeat(127) + " test"));
        var mutable = new ArrayList<>(List.of("test"));
        var query = new MavenBuildQuery.Query(mutable, mutable, List.of(), false, List.of());
        mutable.clear();
        assertEquals(List.of("test"), query.arguments());
        assertEquals(List.of("test"), query.goals());
        assertThrows(UnsupportedOperationException.class, () -> query.arguments().add("clean"));
    }

    private static MavenBuildQuery.Query parse(String command) {
        var query = MavenBuildQuery.parse(command);
        assertNotNull(query, command);
        return query;
    }
}
