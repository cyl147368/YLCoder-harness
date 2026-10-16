param(
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'YLCoder-harness'),
    [string]$ConfigDirectory = (Join-Path $HOME '.code-harness'),
    [switch]$NoPath
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $OutputEncoding

# 只从安装脚本所在位置读取公开模板和运行文件，不依赖调用目录。
$bundleJar = Join-Path $PSScriptRoot 'lib\ylc.jar'
if (Test-Path -LiteralPath $bundleJar -PathType Leaf) {
    $jar = $bundleJar
    $launcherDirectory = Join-Path $PSScriptRoot 'bin'
} else {
    [xml]$pom = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw
    $version = [string]$pom.project.version
    if ($version -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
        throw 'pom.xml 中的项目版本无效。'
    }
    $jar = Join-Path $PSScriptRoot "harness-cli\target\harness-cli-$version-all.jar"
    $launcherDirectory = Join-Path $PSScriptRoot 'packaging\bin'
}
$template = Join-Path $PSScriptRoot 'config.example.yaml'
foreach ($required in @($jar, $template, (Join-Path $launcherDirectory 'ylc.cmd'), (Join-Path $launcherDirectory 'ylc'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw '缺少安装文件。源码用户请先在源码目录运行 mvn -B verify；便携包用户请完整解压。'
    }
}

$installRoot = [IO.Path]::GetFullPath($InstallDirectory)
$configRoot = [IO.Path]::GetFullPath($ConfigDirectory)
$bin = Join-Path $installRoot 'bin'
$lib = Join-Path $installRoot 'lib'
foreach ($directory in @($bin, $lib, $configRoot)) {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
}

function Copy-RuntimeFile([string]$Source, [string]$Destination) {
    if ([IO.Path]::GetFullPath($Source) -ine [IO.Path]::GetFullPath($Destination)) {
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
    }
}
Copy-RuntimeFile $jar (Join-Path $lib 'ylc.jar')
Copy-RuntimeFile (Join-Path $launcherDirectory 'ylc.cmd') (Join-Path $bin 'ylc.cmd')
Copy-RuntimeFile (Join-Path $launcherDirectory 'ylc') (Join-Path $bin 'ylc')
Copy-RuntimeFile $template (Join-Path $installRoot 'config.example.yaml')

$config = Join-Path $configRoot 'config.yaml'
# CreateNew 防止重复安装或并发安装覆盖已经填写的密钥。
if (-not (Test-Path -LiteralPath $config)) {
    $stream = [IO.File]::Open($config, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $data = [IO.File]::ReadAllBytes($template)
        $stream.Write($data, 0, $data.Length)
    } finally {
        $stream.Dispose()
    }
    Write-Host "已创建配置：$config"
} else {
    Write-Host "已保留现有配置：$config"
}

function Test-PathEntry([string]$Value, [string]$Entry) {
    foreach ($part in ($Value -split ';')) {
        $expanded = [Environment]::ExpandEnvironmentVariables($part.Trim().Trim('"')).TrimEnd('\', '/')
        if ($expanded -ieq $Entry.TrimEnd('\', '/')) { return $true }
    }
    return $false
}

if (-not $NoPath) {
    # 保留已有 PATH 的原始变量引用，且只写当前用户设置，无需管理员权限。
    $registry = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
    try {
        $userPath = [string]$registry.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        if (-not (Test-PathEntry $userPath $bin)) {
            $separator = if ($userPath -and -not $userPath.EndsWith(';')) { ';' } else { '' }
            [Environment]::SetEnvironmentVariable('Path', ($userPath + $separator + $bin), 'User')
            # SetEnvironmentVariable 会广播变更；保留可扩展字符串的注册表类型。
            $registry.SetValue('Path', ($userPath + $separator + $bin), [Microsoft.Win32.RegistryValueKind]::ExpandString)
        }
    } finally {
        $registry.Dispose()
    }
    if (-not (Test-PathEntry $env:Path $bin)) { $env:Path = "$bin;$env:Path" }
    Write-Host '已加入用户 PATH，并刷新此 PowerShell 进程。其他已打开的终端请重新打开。'
} else {
    Write-Host "未修改 PATH。需要将此目录加入 PATH：$bin"
}

Write-Host "安装位置：$installRoot"
Write-Host "请编辑配置：notepad `"$config`""
Write-Host '随后在任意项目目录执行 ylc；默认处理当前目录。'
