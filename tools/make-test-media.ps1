# 生成「播放器 + 字幕」验证用的测试媒体与字幕文件。
#
# 为什么要有这个脚本，而不是手动敲 ffmpeg：
# 每次验证都要同一批素材（含空档、双语句式、逐字 LRC、GBK 编码、中文文件名），
# 手敲一遍必然漏掉其中一两项，而漏掉的那项正是这次要测的东西。
#
# 用法：
#   pwsh -File tools\make-test-media.ps1
#   pwsh -File tools\make-test-media.ps1 -OutputDir D:\tmp\media
#
# 输出目录默认在 %TEMP% 下，不放进仓库（几十 MB 的测试视频没必要进版本库）。

param(
    # 注意：PowerShell 变量名不区分大小写，所以这里刻意不叫 $OutDir——
    # 否则函数体里一个局部的 $outDir 会静默地把这个参数冲掉。
    [string]$OutputDir = (Join-Path $env:TEMP 'msp-testmedia'),
    [string]$FfmpegPath = 'D:\ffmpeg-build\bin\ffmpeg.exe'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $FfmpegPath)) {
    throw "找不到 ffmpeg：$FfmpegPath"
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$dir = (Resolve-Path -LiteralPath $OutputDir).Path
Write-Host "输出目录：$dir"

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Write-Utf8([string]$Name, [string]$Text) {
    [System.IO.File]::WriteAllText((Join-Path $dir $Name), $Text, $utf8NoBom)
}

function Write-Gb18030([string]$Name, [string]$Text) {
    $enc = [System.Text.Encoding]::GetEncoding('GB18030')
    [System.IO.File]::WriteAllText((Join-Path $dir $Name), $Text, $enc)
}

# ---------------------------------------------------------------------------
# 视频 + 外挂字幕
# ---------------------------------------------------------------------------

Write-Host '生成 TestClip.mp4（60 秒，带秒表图案，便于肉眼比对字幕同步）…'
& $FfmpegPath -y -loglevel error `
    -f lavfi -i 'testsrc2=size=640x360:rate=25:duration=60' `
    -f lavfi -i 'sine=frequency=440:duration=60' `
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -profile:v high -level 4.0 `
    -c:a aac -b:a 128k -shortest -movflags +faststart `
    (Join-Path $dir 'TestClip.mp4')

# 第 5 句和第 6 句之间刻意留 8 秒空档：用来验证「空档里不显示任何字幕」。
# 如果实现里用了 cueFocusedAt 之类的「最后一个已开始的行」，这 8 秒会一直挂着
# 上一句台词——那正是要被抓出来的 bug。
$srt = @'
1
00:00:02,000 --> 00:00:06,000
Subtitles from an external file.
Multi-line cues should wrap as a block.

2
00:00:06,000 --> 00:00:10,000
Line two: the karaoke sweep only applies
when the file carries inline timing.

3
00:00:10,000 --> 00:00:14,000
SRT has no translation field.

4
00:00:14,000 --> 00:00:18,000
Bilingual files are handled by pairing
two lines with the same timestamp.

5
00:00:18,000 --> 00:00:26,000
After this cue there is a deliberate
8 second gap with nothing at all.

6
00:00:34,000 --> 00:00:38,000
The gap ended. Nothing should have
been drawn during it.

7
00:00:38,000 --> 00:00:42,000
Switching display mode is instant
because it never re-parses the file.

8
00:00:42,000 --> 00:00:46,000
Picking another candidate from the
sheet replaces this text.

9
00:00:46,000 --> 00:00:50,000
Seeking jumps straight to the right line.

10
00:00:50,000 --> 00:00:56,000
Last cue of the clip.
'@
Write-Utf8 'TestClip.srt' $srt

$srtChs = @'
1
00:00:02,000 --> 00:00:06,000
这是外挂字幕文件里的第一句。
长句应当整块换行。

2
00:00:06,000 --> 00:00:10,000
第二句：只有文件带内嵌时间标签时
才会有逐字高亮。

3
00:00:10,000 --> 00:00:14,000
SRT 格式本身没有译文字段。

4
00:00:14,000 --> 00:00:18,000
双语是靠「同一时间码两行」配对的。

5
00:00:18,000 --> 00:00:26,000
这一句之后刻意留了 8 秒空档。

6
00:00:34,000 --> 00:00:38,000
空档结束。空档期间不应该画出任何字幕。

7
00:00:38,000 --> 00:00:42,000
切换显示模式是瞬时的，因为不重新解析文件。

8
00:00:42,000 --> 00:00:46,000
在选择面板里换一条候选，这段文字就会变。

9
00:00:46,000 --> 00:00:50,000
拖动进度条会直接跳到对应的那一句。

10
00:00:50,000 --> 00:00:56,000
这是这一段视频的最后一句。
'@
Write-Utf8 'TestClip.chs.srt' $srtChs

# 文件名里的「中英」应当被识别为双语字幕（SubtitleFileNaming 的一档）。
$srtZhEn = @'
1
00:00:02,000 --> 00:00:06,000
Subtitles from an external file.
来自外挂字幕文件。

2
00:00:06,000 --> 00:00:10,000
Line two.
第二句。

3
00:00:10,000 --> 00:00:14,000
SRT has no translation field.
SRT 没有译文字段。
'@
Write-Utf8 'TestClip.中英.srt' $srtZhEn

Write-Host '生成 中文片名.mp4（中文文件名要走一遍目录查询与匹配）…'
& $FfmpegPath -y -loglevel error `
    -f lavfi -i 'testsrc2=size=480x270:rate=25:duration=20' `
    -f lavfi -i 'sine=frequency=660:duration=20' `
    -c:v libx264 -preset veryfast -pix_fmt yuv420p `
    -c:a aac -b:a 128k -shortest -movflags +faststart `
    (Join-Path $dir '中文片名.mp4')

Write-Utf8 '中文片名.srt' @'
1
00:00:02,000 --> 00:00:08,000
中文文件名也要能自动挂上字幕。

2
00:00:08,000 --> 00:00:16,000
匹配靠的是文件名前缀，跟是不是中文无关。
'@

Write-Host '生成 GbkClip.mp4（字幕故意用 GB18030 编码）…'
& $FfmpegPath -y -loglevel error `
    -f lavfi -i 'testsrc2=size=480x270:rate=25:duration=20' `
    -f lavfi -i 'sine=frequency=330:duration=20' `
    -c:v libx264 -preset veryfast -pix_fmt yuv420p `
    -c:a aac -b:a 128k -shortest -movflags +faststart `
    (Join-Path $dir 'GbkClip.mp4')

# 编码探测/转换的实机验证：这个文件是 GBK 的，读进来必须仍然是中国字。
# 如果解码猜错了，屏幕上会是乱码而不是报错——只有在实机上跑一遍才知道。
Write-Gb18030 'GbkClip.srt' @'
1
00:00:02,000 --> 00:00:08,000
这个字幕文件是 GB18030 编码的。

2
00:00:08,000 --> 00:00:16,000
如果解码猜错了，这里会是一堆乱码。
'@

# ---------------------------------------------------------------------------
# 音频 + 歌词（双语 + 逐字）
# ---------------------------------------------------------------------------

Write-Host '生成 TestSong.mp3（60 秒）…'
& $FfmpegPath -y -loglevel error `
    -f lavfi -i 'sine=frequency=523:duration=60' `
    -c:a libmp3lame -b:a 192k `
    (Join-Path $dir 'TestSong.mp3')

# 两种语法混着来，一次覆盖三件事：
#  * `[mm:ss.xx]<mm:ss.xx>字<mm:ss.xx>字` —— 增强型 LRC，逐字高亮；
#  * 同一时间码两行 —— 原文 + 译文（双语模式）；
#  * 只有一行 —— 没有译文时的行为（「仅译文」应当退回原文并提示）。
# 另外最后一段的时长在文件里是「到下一句为止」，这正是 karaokeTimeline() 要补的漏洞。
$lrc = @'
[00:00.00]<00:00.00>夜<00:01.20>空<00:02.40>之<00:03.60>中
[00:00.00]夜空之中
[00:06.00]<00:06.00>Counting <00:07.50>every <00:09.00>word
[00:06.00]数着每一个字
[00:12.00]A line without any translation
[00:18.00]<00:18.00>Sweep <00:19.20>moves <00:20.40>left <00:21.60>to <00:22.80>right
[00:18.00]高亮从左扫到右
[00:24.00]Only one line here
[00:30.00]<00:30.00>Last <00:31.50>segment <00:33.00>is <00:34.50>short
[00:30.00]最后一段在文件里没有结束时间
[00:36.00]Plain line, no karaoke tags
[00:36.00]没有逐字标签的普通行
[00:42.00]<00:42.00>Seek <00:43.50>by <00:45.00>clicking
[00:42.00]点一下歌词就能跳过去
[00:48.00]Fin.
[00:48.00]完。
'@
Write-Utf8 'TestSong.lrc' $lrc

Write-Host ''
Write-Host '完成。文件列表：'
Get-ChildItem -LiteralPath $dir | Select-Object Name, Length | Format-Table -AutoSize
