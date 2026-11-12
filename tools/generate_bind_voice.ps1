# 生成绑定码语音播报用的 WAV 资源（res/raw）。
#
# 音箱/盒子类设备没有触摸屏，也无法保证接显示器，平台下发的 6 位绑定码只能靠
# 语音告诉用户。Android 5.1.1 的 Phicomm R1 上没有安装任何 TTS 引擎
# （pm list packages 里没有 com.svox.pico / com.google.android.tts），
# 系统 TextToSpeech 无法使用，因此在这里用 Windows 自带的中文语音
# （Microsoft Huihui Desktop）预生成音频，随 APK 一起打包。
#
# 用法（Windows PowerShell）：
#   powershell -ExecutionPolicy Bypass -File tools\generate_bind_voice.ps1
#
# 输出：16kHz / 单声道 / 16bit PCM WAV，与设备麦克风采集、Opus 播放的采样率一致。

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

$repoRoot = Split-Path -Parent $PSScriptRoot
$phraseFile = Join-Path $PSScriptRoot 'bind_voice_phrases.txt'
$outDir = Join-Path $repoRoot 'app\src\main\res\raw'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SelectVoice('Microsoft Huihui Desktop')
$synth.Rate = -1      # 稍慢一点，6 位数字更容易听清
$synth.Volume = 100
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)

foreach ($line in (Get-Content -Encoding UTF8 $phraseFile)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line.Split("`t", 2)
    if ($parts.Count -ne 2) { throw "格式错误: $line" }
    $name = $parts[0].Trim()
    $text = $parts[1].Trim()
    $path = Join-Path $outDir "$name.wav"
    $synth.SetOutputToWaveFile($path, $format)
    $synth.Speak($text)
    $synth.SetOutputToNull()
    Write-Output ("生成 {0}  ({1} bytes)" -f $path, (Get-Item $path).Length)
}

$synth.Dispose()
