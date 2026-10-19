@echo off
setlocal
chcp 65001 >nul 2>&1
rem 统一控制台与 Java 编码，避免中文被按旧代码页解码。
set "HARNESS_JAR=%~dp0..\lib\ylc.jar"
if not exist "%~dp0..\lib\current-runtime.txt" goto verify
set "HARNESS_RUNTIME="
set /p "HARNESS_RUNTIME="<"%~dp0..\lib\current-runtime.txt"
set "HARNESS_JAR=%~dp0..\lib\%HARNESS_RUNTIME%"
:verify
if exist "%HARNESS_JAR%" goto java
echo 缺少运行包；请重新安装或解压完整的发行包。 1>&2
exit /b 1
:java
set "HARNESS_JAVA=java"
if defined JAVA_HOME set "HARNESS_JAVA=%JAVA_HOME%\bin\java.exe"
"%HARNESS_JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%HARNESS_JAR%" %*
exit /b %ERRORLEVEL%
