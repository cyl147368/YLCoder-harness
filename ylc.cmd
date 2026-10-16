@echo off
setlocal
chcp 65001 >nul 2>&1
rem 统一控制台与 Java 编码，避免中文被按旧代码页解码。
set "HARNESS_JAR=%~dp0harness-cli/target/harness-cli-0.1.0-SNAPSHOT-all.jar"
if exist "%HARNESS_JAR%" goto run
echo 请先构建项目：mvn verify 1>&2
exit /b 1
:run
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%HARNESS_JAR%" %*
exit /b %ERRORLEVEL%
