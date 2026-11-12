<#
.SYNOPSIS
    小智（灵犀）盒子远程控制脚本 —— 安装 / 打开 / 退出 / 卸载。

    这是一个可以独立使用的工具目录：本脚本、app-debug.apk（可选 adb.exe）放在
    同一个目录下，整个目录可以拷贝到任意电脑/任意路径使用，不依赖源码工程。

.DESCRIPTION
    盒子没有触摸屏，应用本身无法决定“什么时候运行”。本脚本在电脑上通过 adb
    的 app_process 方式（该固件的 adb shell 会拦截 pm/am 命令）完成以下操作：

      menu       （默认）交互菜单，适合双击 .bat 使用
      install    安装 APK（保留数据：已绑定的设备无需重新绑定）
                 默认使用与脚本同目录的 app-debug.apk，可用 -Apk 指定其他文件
      start      打开应用：自动连接、自动进入语音对话、占用麦克风
      stop       退出应用：断开连接、释放麦克风并结束进程（不改动开机自启设置）
      boot-on    开启开机自启（盒子重启后自动打开应用）
      boot-off   关闭开机自启
      status     查看应用是否在运行、开机自启状态，并输出最近的日志
      uninstall  卸载应用

    开机自启是独立开关，只由 boot-on / boot-off（或应用设置页的“开机自动启动应用”）
    决定，start / stop 不会修改它。

    启动后先要求填写盒子的 IP 地址，确认设备在线后才执行具体操作；菜单模式下
    只需填一次，之后的每个操作都在这台设备上执行。也可以用 -Device 参数直接指定
    （例如 -Device 192.168.1.20），跳过手动输入。

.EXAMPLE
    .\xiaozhi-device.ps1 start
    .\xiaozhi-device.ps1 stop
    .\xiaozhi-device.ps1 install
    .\xiaozhi-device.ps1 uninstall

.EXAMPLE
    # 指定设备 IP，跳过输入；换一个 APK
    .\xiaozhi-device.ps1 start -Device 192.168.1.20 -Apk D:\build\app-debug.apk
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('install', 'start', 'stop', 'status', 'boot-on', 'boot-off', 'uninstall', 'menu')]
    [string]$Command = 'menu',

    [string]$Device = '',

    [string]$Apk = '',

    [string]$Adb = ''
)

$ErrorActionPreference = 'Stop'

$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

# 默认 APK 与脚本放在同一目录
if (-not $Apk) {
    $Apk = Join-Path $scriptRoot 'app-debug.apk'
}

$Package   = 'com.lhht.xiaozhi.ai'
$VendorApp = 'com.phicomm.speaker.device'   # 厂商语音助手，会独占麦克风
$Receiver  = "$Package/com.lhht.xiaozhi.receiver.ControlReceiver"
$ActionStart = "$Package.action.START"
$ActionStop  = "$Package.action.STOP"
$ActionBootOn  = "$Package.action.BOOT_ON"
$ActionBootOff = "$Package.action.BOOT_OFF"

# ── adb 定位与连接 ───────────────────────────────────────────────────────────

if (-not $Adb) {
    # 独立使用时优先找脚本目录里的 adb，其次才是环境变量和本机 SDK
    $candidates = @(
        (Join-Path $scriptRoot 'adb.exe'),
        (Join-Path $scriptRoot 'platform-tools\adb.exe'),
        $env:ADB,
        'D:\Android\Sdk\platform-tools\adb.exe',
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($candidates.Count -gt 0) {
        $Adb = $candidates[0]
    } else {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $Adb = $cmd.Source }
    }
}
if (-not $Adb) {
    throw '找不到 adb：请把 adb.exe 放到脚本同一目录（或 platform-tools 子目录），或用 -Adb 指定路径。'
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)]$Arguments)
    # adb 把进度/提示写到 stderr，PS 5.1 会把 stderr 当成错误记录；这里只收集文本，不抛异常
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Adb -s $Device @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
    return @($out | ForEach-Object { "$_" })
}

# 每次运行先确认设备地址：没给 -Device 就交互输入，只填 IP 时自动补 5555 端口
function Resolve-DeviceAddress {
    $address = $Device
    if (-not $address) {
        Write-Host '请先填写盒子的 IP 地址（后续所有操作都在这台设备上执行）。'
        $address = Read-Host '设备 IP（例如 192.168.1.20）'
    }
    $address = "$address".Trim()
    if (-not $address) {
        throw '没有填写设备 IP 地址，已取消。'
    }
    if ($address -match '^\d{1,3}(\.\d{1,3}){3}$') {
        $address = "${address}:5555"
    }
    return $address
}

function Connect-Device {
    if ($Device -match ':') {
        & $Adb connect $Device | Out-Null
    }
    $state = (& $Adb -s $Device get-state 2>&1) -join ''
    if ($state.Trim() -ne 'device') {
        throw "设备未连接：$Device（$state）"
    }
}

# 该固件拦截 adb shell 的 pm/am，只能用 app_process 调起对应命令
function Invoke-DeviceAm {
    param([string[]]$Arguments)
    $cmd = "CLASSPATH=/system/framework/am.jar app_process /system com.android.commands.am.Am " +
           ($Arguments -join ' ')
    return Invoke-Adb shell $cmd
}

function Invoke-DevicePm {
    param([string[]]$Arguments)
    $cmd = "CLASSPATH=/system/framework/pm.jar app_process /system com.android.commands.pm.Pm " +
           ($Arguments -join ' ')
    return Invoke-Adb shell $cmd
}

function Get-AppProcessId {
    $lines = (Invoke-Adb shell ps) -join "`n"
    foreach ($line in ($lines -split "`n")) {
        if ($line -match "^\S+\s+(\d+)\s+.*\s$([regex]::Escape($Package))\s*$") {
            return [int]$Matches[1]
        }
    }
    return $null
}

function Get-AppLog {
    param([int]$Tail = 25)
    $lines = (Invoke-Adb shell "run-as $Package cat files/logs/app_debug.log") -join "`n"
    $all = @($lines -split "`n" | Where-Object { $_ -ne '' })
    if ($all.Count -le $Tail) { return $all }
    return $all[($all.Count - $Tail)..($all.Count - 1)]
}

function Get-LastLogTimestamp {
    $line = (Get-AppLog 1 | Select-Object -Last 1)
    if ($line -match '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') { return $Matches[1] }
    return ''
}

function Wait-AppLogPattern {
    param([string]$Pattern, [string]$Since = '', [int]$TimeoutSeconds = 40)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $lines = Get-AppLog 80
        $new = @($lines | Where-Object {
            if (-not $Since) { return $true }
            if ($_ -match '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') {
                return ($Matches[1] -gt $Since)
            }
            return $false
        })
        if ((($new -join "`n")) -match $Pattern) { return $true }
        Start-Sleep -Milliseconds 1500
    }
    return $false
}

# ── 命令实现 ─────────────────────────────────────────────────────────────────

function Invoke-Install {
    if (-not (Test-Path $Apk)) {
        throw "APK 不存在：$Apk。请把 app-debug.apk 放到脚本同一目录，或用 -Apk 指定路径。"
    }
    # 应用正在占用麦克风时直接覆盖安装会被系统强杀进程，音频 HAL 可能卡死到下次重启。
    # 先走正常退出流程释放资源，再安装。
    if (Get-AppProcessId) {
        Write-Host '[install] 应用正在运行，先退出并释放麦克风...'
        Invoke-Stop
    }
    Write-Host "[install] 推送 APK: $Apk"
    $push = Invoke-Adb push $Apk /data/local/tmp/app-debug.apk
    if (($push -join "`n") -notmatch '1 file pushed') {
        throw ("推送失败：" + ($push -join "`n"))
    }
    Write-Host '[install] 安装中（保留应用数据）...'
    $result = (Invoke-DevicePm @('install', '-r', '/data/local/tmp/app-debug.apk')) -join "`n"
    if ($result -notmatch 'Success') {
        throw "安装失败：$result"
    }
    Write-Host '[install] 安装成功。执行 start 打开应用；已绑定的设备无需重新绑定。'
}

function Invoke-Start {
    # 厂商语音助手会独占麦克风：先停掉它，否则音频 HAL 会卡住、应用无法录音
    Write-Host '[start] 停止厂商语音助手（释放麦克风）...'
    $vendor = (Invoke-DeviceAm @('force-stop', $VendorApp)) -join "`n"
    if ($vendor.Trim()) { Write-Host "        $vendor" }

    Write-Host '[start] 发送打开指令...'
    $since = Get-LastLogTimestamp
    $out = (Invoke-DeviceAm @('broadcast', '-a', $ActionStart, '-n', $Receiver)) -join "`n"
    if ($out -notmatch 'Broadcast completed') {
        # 广播不可用时退回直接拉起引导页（已引导过会直接进主界面）
        Write-Host "[start] 广播未生效（$out），改用直接启动界面"
        $fallback = (Invoke-DeviceAm @('start', '-n', "$Package/com.lhht.xiaozhi.activities.OnboardingActivity")) -join "`n"
        if ($fallback -match 'Error') { throw "启动失败：$fallback" }
    }

    Write-Host '[start] 等待应用连接并打开麦克风（最多 60 秒）...'
    $ok = Wait-AppLogPattern '自动开始语音对话' $since 60
    $tail = (Get-AppLog 15) -join "`n"
    Write-Host $tail
    if ($ok) {
        Write-Host '[start] 已连接并自动进入语音对话。'
    } else {
        Write-Host '[start] 60 秒内没看到“自动开始语音对话”，请检查上面的日志（可能是网络未就绪）。'
    }
}

function Invoke-Stop {
    $appPid = Get-AppProcessId
    if (-not $appPid) {
        Write-Host '[stop] 应用没有在运行。'
        return
    }
    Write-Host "[stop] 发送退出指令（pid=$appPid）..."
    Invoke-DeviceAm @('broadcast', '-a', $ActionStop, '-n', $Receiver) | Out-Null

    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-AppProcessId)) {
            Write-Host '[stop] 应用已退出，麦克风已释放（开机自启设置未改动）。'
            return
        }
        Start-Sleep -Milliseconds 1000
    }
    # 兜底：优雅退出超时就直接强杀（下次 start 前建议重启一次盒子）
    Write-Host '[stop] 优雅退出超时，强制结束进程...'
    Invoke-DeviceAm @('force-stop', $Package) | Out-Null
    Write-Host '[stop] 已强制结束。'
}

function Get-BootAutoStartState {
    $lines = (Invoke-Adb shell "run-as $Package cat shared_prefs/xiaozhi_settings.xml") -join "`n"
    if ($lines -match 'name="boot_auto_start" value="(true|false)"') {
        return ($Matches[1] -eq 'true')
    }
    return $null
}

function Set-BootAutoStart {
    param([bool]$Enabled)
    $action = if ($Enabled) { $ActionBootOn } else { $ActionBootOff }
    Invoke-DeviceAm @('broadcast', '-a', $action, '-n', $Receiver) | Out-Null
    $state = Get-BootAutoStartState
    $text = if ($Enabled) { '开启' } else { '关闭' }
    if ($state -eq $Enabled) {
        Write-Host "[boot] 开机自启已$text。"
    } else {
        Write-Host "[boot] 已发送${text}指令，但没能从设置文件确认结果（应用可能在忙）。"
    }
}

function Get-AppUiRunning {
    $dump = (Invoke-Adb shell "dumpsys activity activities") -join "`n"
    return ($dump -match "$([regex]::Escape($Package))/\S*activities\.MainActivity")
}

function Invoke-Status {
    $appPid = Get-AppProcessId
    if ($appPid -and (Get-AppUiRunning)) {
        Write-Host "[status] 应用正在运行（pid=$appPid，界面已打开）"
    } elseif ($appPid) {
        Write-Host "[status] 只有空闲进程（pid=$appPid），界面未打开，麦克风未被占用"
    } else {
        Write-Host '[status] 应用未运行'
    }
    $boot = Get-BootAutoStartState
    if ($boot -eq $true) {
        Write-Host '[status] 开机自启：开启'
    } elseif ($boot -eq $false) {
        Write-Host '[status] 开机自启：关闭'
    } else {
        Write-Host '[status] 开机自启：未设置（默认开启）'
    }
    $vendorPid = ((Invoke-Adb shell ps) -join "`n") -match 'com\.phicomm\.speaker\.device'
    if ($vendorPid) { Write-Host '[status] 厂商语音助手正在运行' }
    Write-Host '[status] 最近日志：'
    (Get-AppLog 40) |
        Where-Object { $_ -notmatch 'OTA 响应|Body=|Device-Id=|Client-Id=|WS Token|携带 Authorization' } |
        Select-Object -Last 15 |
        ForEach-Object { Write-Host "        $_" }
}

function Invoke-Uninstall {
    if (Get-AppProcessId) {
        Write-Host '[uninstall] 应用正在运行，先退出并释放麦克风...'
        Invoke-Stop
    }
    Write-Host '[uninstall] 卸载中...'
    $result = (Invoke-DevicePm @('uninstall', $Package)) -join "`n"
    Write-Host "        $result"
    if ($result -notmatch 'Success') { throw "卸载失败：$result" }
    Write-Host '[uninstall] 已卸载。'
}

# ── 交互菜单（双击 .bat 时使用）──────────────────────────────────────────────

function Invoke-Action {
    param([string]$Name)
    switch ($Name) {
        'install'   { Invoke-Install }
        'start'     { Invoke-Start }
        'stop'      { Invoke-Stop }
        'boot-on'   { Set-BootAutoStart $true }
        'boot-off'  { Set-BootAutoStart $false }
        'status'    { Invoke-Status }
        'uninstall' { Invoke-Uninstall }
    }
}

function Show-Menu {
    try { Clear-Host } catch { }
    Write-Host '=============================================='
    Write-Host '  小智盒子控制台'
    Write-Host '=============================================='
    Write-Host '   1. 安装应用            install'
    Write-Host '   2. 打开应用            start   （自动连接 + 开始对话）'
    Write-Host '   3. 退出应用            stop    （释放麦克风）'
    Write-Host '   4. 开机自启：开启      boot-on'
    Write-Host '   5. 开机自启：关闭      boot-off'
    Write-Host '   6. 查看状态            status'
    Write-Host '   7. 卸载应用            uninstall'
    Write-Host '   0. 退出'
    Write-Host ''
    $choice = Read-Host '请输入序号'
    if (-not $choice) { return '' }
    switch ($choice) {
        '1' { return 'install' }
        '2' { return 'start' }
        '3' { return 'stop' }
        '4' { return 'boot-on' }
        '5' { return 'boot-off' }
        '6' { return 'status' }
        '7' { return 'uninstall' }
        '0' { return '' }
        default { return 'menu' }
    }
}

function Start-MenuLoop {
    $Device = Resolve-DeviceAddress
    Connect-Device
    while ($true) {
        $action = Show-Menu
        if (-not $action) { return }
        if ($action -eq 'menu') { continue }
        Invoke-Action $action
        Write-Host ''
        Read-Host '按回车返回菜单（Ctrl+C 退出）' | Out-Null
    }
}

# ── 入口 ─────────────────────────────────────────────────────────────────────

try {
    if ($Command -eq 'menu') {
        Start-MenuLoop
    } else {
        $Device = Resolve-DeviceAddress
        Connect-Device
        Invoke-Action $Command
    }
} catch {
    Write-Host "[错误] $($_.Exception.Message)"
    exit 1
}
