<#
.SYNOPSIS
    小智（灵犀）盒子远程控制脚本 —— 安装 / 打开 / 退出 / 卸载。

    这是一个可以独立使用的工具目录：本脚本、app-debug.apk（可选 adb.exe）放在
    同一个目录下，整个目录可以拷贝到任意电脑/任意路径使用，不依赖源码工程。

.DESCRIPTION
    盒子没有触摸屏，应用本身无法决定“什么时候运行”。本脚本在电脑上通过 adb
    的 app_process 方式（该固件的 adb shell 会拦截 pm/am 命令）完成以下操作：

      menu       （默认）交互菜单，适合双击 .bat 使用
      install    安装 APK：先检查设备上是否已安装，已安装则先卸载再装
                 （应用数据会清除，绑定信息在服务端、重装无需重新绑定）；
                 安装完成后自动打开应用（自动连接、进入语音对话）
                 APK 文件名不限：默认优先用同目录的 app-debug.apk；
                 目录里只有一个 .apk 时自动用它；有多个时用 -Apk 指定
      start      打开应用：自动连接、自动进入语音对话、占用麦克风
      stop       退出应用：断开连接、释放麦克风并结束进程（不改动开机自启设置）
                 对话中对盒子说“退出小智”会触发同样的退出
      boot-on    开启开机自启（盒子重启后自动打开应用）
      boot-off   关闭开机自启（默认关闭：开机后不会自动打开应用）
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

# 应用日志文件是 UTF-8，而 PowerShell 5.1 默认按控制台代码页（中文系统为 GBK）
# 解码外部命令输出，会把 status 里的中文日志显示成乱码；这里显式按 UTF-8 解码。
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }

$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

# APK 路径在 Invoke-Install 里解析：优先 app-debug.apk，其次目录里唯一的 .apk

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

# 该固件对不存在的包执行 uninstall 会返回 DELETE_FAILED_INTERNAL_ERROR，
# 所以卸载前先用 pm list packages 确认是否装了。
function Test-PackageInstalled {
    $pattern = "(?m)^package:$([regex]::Escape($Package))\s*$"
    for ($i = 1; $i -le 2; $i++) {
        $lines = (Invoke-DevicePm @('list', 'packages', $Package)) -join "`n"
        if ($lines -match $pattern) { return $true }
        # pm 服务偶尔会短暂不可用（error: closed），再试一次
        if ($lines -match 'error:') { Start-Sleep -Milliseconds 800; continue }
        return $false
    }
    return $false
}

# APK 文件名不固定：-Apk 指定 > 同目录 app-debug.apk > 同目录唯一的 .apk
function Resolve-ApkPath {
    if ($Apk) {
        if (-not (Test-Path $Apk)) {
            throw "APK 不存在：$Apk"
        }
        return (Resolve-Path $Apk).Path
    }
    $preferred = Join-Path $scriptRoot 'app-debug.apk'
    if (Test-Path $preferred) {
        return $preferred
    }
    $found = @(Get-ChildItem -LiteralPath $scriptRoot -Filter '*.apk' -File -ErrorAction SilentlyContinue)
    if ($found.Count -eq 1) {
        return $found[0].FullName
    }
    if ($found.Count -gt 1) {
        $names = ($found | ForEach-Object { $_.Name }) -join '、'
        throw "脚本目录里有多个 APK（$names），请用 -Apk 指定要安装的文件。"
    }
    throw "APK 不存在：$preferred。请把 APK 放到脚本同一目录，或用 -Apk 指定路径。"
}

function Invoke-Install {
    $apkPath = Resolve-ApkPath

    # 应用正在占用麦克风时直接覆盖安装会被系统强杀进程，音频 HAL 可能卡死到下次重启。
    # 先走正常退出流程释放资源，再安装。
    if (Get-AppProcessId) {
        Write-Host '[install] 应用正在运行，先退出并释放麦克风...'
        Invoke-Stop
    }

    # 安装前先检查设备上有没有这个包：有就卸载，没有就跳过卸载。
    # 该固件对不存在的包执行 uninstall 会返回 DELETE_FAILED_INTERNAL_ERROR，
    # 因此不能靠卸载结果判断，必须先查包。
    # 卸载只清应用数据（设置项恢复默认）；绑定信息保存在服务端、按设备识别，重装无需重新绑定。
    if (Test-PackageInstalled) {
        Write-Host '[install] 检测到设备上已有旧版本，开始卸载（应用数据会清除，绑定信息不受影响）...'
        $uninstall = ((Invoke-DevicePm @('uninstall', $Package)) |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }) -join ' '
        if ($uninstall -notmatch 'Success') {
            throw "卸载旧版本失败：$uninstall"
        }
        Write-Host '[install] 旧版本已卸载。'
    } else {
        Write-Host '[install] 设备上没有旧版本，直接安装。'
    }

    Write-Host "[install] 推送 APK: $apkPath"
    $push = Invoke-Adb push $apkPath /data/local/tmp/app-debug.apk
    if (($push -join "`n") -notmatch '1 file pushed') {
        throw ("推送失败：" + ($push -join "`n"))
    }
    Write-Host '[install] 安装中...'
    $result = (Invoke-DevicePm @('install', '/data/local/tmp/app-debug.apk')) -join "`n"
    if ($result -notmatch 'Success') {
        throw "安装失败：$result"
    }
    if (-not (Test-PackageInstalled)) {
        throw '安装命令返回成功，但没有在设备上查到该应用，请重试或检查盒子存储空间。'
    }
    Write-Host '[install] 安装成功，正在自动打开应用...'
    # 装完直接拉起：自动连接并进入语音对话（等同于执行 start）
    Invoke-Start
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
    # 已绑定设备会出现“自动开始语音对话”；未绑定设备会收到 bind 消息并语音播报绑定码，
    # 两种情况都算启动成功，不必等满 60 秒。
    $ok = Wait-AppLogPattern '自动开始语音对话|"type":"bind"' $since 60
    # 输出最近的日志便于排查；过滤掉 token、设备 ID 等敏感内容（与 status 一致）
    $tail = @(Get-AppLog 15 |
        Where-Object { $_ -notmatch 'OTA 响应|Body=|Device-Id=|Client-Id=|WS Token|携带 Authorization|deviceId=|clientId=' }) -join "`n"
    Write-Host $tail
    if ($ok) {
        $new = @(Get-AppLog 200 | Where-Object {
            if (-not $since) { return $true }
            if ($_ -match '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})') {
                return ($Matches[1] -gt $since)
            }
            return $false
        }) -join "`n"
        if ($new -match '"type":"bind"') {
            Write-Host '[start] 设备尚未绑定：应用正在语音播报 6 位绑定码，请到 xiaozhi.me 绑定。'
        } else {
            Write-Host '[start] 已连接并自动进入语音对话。'
        }
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
        Write-Host '[status] 开机自启：未设置（默认关闭）'
    }
    $vendorPid = ((Invoke-Adb shell ps) -join "`n") -match 'com\.phicomm\.speaker\.device'
    if ($vendorPid) { Write-Host '[status] 厂商语音助手正在运行' }
    Write-Host '[status] 最近日志：'
    (Get-AppLog 40) |
        Where-Object { $_ -notmatch 'OTA 响应|Body=|Device-Id=|Client-Id=|WS Token|携带 Authorization|deviceId=|clientId=' } |
        Select-Object -Last 15 |
        ForEach-Object { Write-Host "        $_" }
}

function Invoke-Uninstall {
    if (-not (Test-PackageInstalled)) {
        Write-Host '[uninstall] 设备上没有安装该应用，无需卸载。'
        return
    }
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
    Write-Host '   1. 安装应用            install（装完自动打开）'
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
        if ($action -eq 'install') {
            # 安装会先卸载旧版本，应用设置（含开机自启）会恢复默认，菜单里再确认一次
            $ok = Read-Host '安装会先卸载旧版本并清除应用数据（绑定信息不受影响，设置恢复默认），输入 y 继续'
            if ($ok -notmatch '^[yY]') { Write-Host '已取消。'; continue }
        }
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
