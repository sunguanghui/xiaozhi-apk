package com.lhht.xiaozhi.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.utils.LogUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 用内置语音资源播报官方平台的绑定码。
 *
 * <p>这台音箱（Phicomm R1，Android 5.1.1）没有触摸屏，也通常不接显示器，平台下发的
 * 6 位绑定码在界面上根本看不到。系统里也没有安装任何 TTS 引擎
 * （{@code pm list packages} 中没有 com.svox.pico / com.google.android.tts），
 * 所以 {@code android.speech.tts.TextToSpeech} 初始化必然失败，不能用它朗读。
 *
 * <p>因此改为把常用语句（开头提示、重复提示、绑定成功）和 0–9 十个数字
 * 用 Windows 中文语音预生成成 16kHz/单声道/16bit 的 WAV，放在 {@code res/raw}，
 * 运行时按顺序拼接播放：
 * <pre>
 *   bind_intro.wav → digit_1.wav → digit_2.wav → … （每 20 秒重播一轮）
 * </pre>
 * 这样完全离线、不依赖任何 TTS 引擎，也不需要在没有屏幕的情况下看界面。
 *
 * <p>所有播放都在后台线程上完成，避免 {@link AudioTrack} 的构造/写入（会经过
 * Binder 进入 audioserver/HAL）阻塞主线程。
 */
public class BindCodeAnnouncer {
    private static final String TAG = "XiaoZhi-BindVoice";
    private static final int SAMPLE_RATE = 16000;
    /** 首次播报太长会让用户等太久，但重复太频繁也吵；20 秒一轮比较合适。 */
    private static final long REPEAT_INTERVAL_MS = 20000;
    /** 数字之间留一点空隙，避免连读听不清。 */
    private static final int GAP_BETWEEN_DIGITS_MS = 90;
    /** “绑定码是”和第一位数字之间留长一点，起分隔作用。 */
    private static final int GAP_BEFORE_DIGITS_MS = 350;
    /** 每次写入的音频块：100ms，便于 stop() 时尽快静音。 */
    private static final int WRITE_CHUNK_BYTES = SAMPLE_RATE * 2 / 10;

    private final Context appContext;
    /** 保护 active / code / generation / worker 的状态，并通过 wait/notify 控制重播间隔。 */
    private final Object lock = new Object();

    private volatile int generation;
    private volatile boolean active;
    private volatile AudioTrack currentTrack;
    private String code = "";

    /** WAV 解码后的 PCM 数据缓存（只在后台线程访问，用 synchronized 兜底）。 */
    private final Map<Integer, byte[]> pcmCache = new HashMap<>();

    public BindCodeAnnouncer(Context context) {
        appContext = context.getApplicationContext();
    }

    public boolean isActive() {
        return active;
    }

    /**
     * 开始（或重新开始）播报绑定码：先念提示语，再逐位念出数字，之后每隔
     * {@link #REPEAT_INTERVAL_MS} 重播一次，直到 {@link #stop()} / {@link #announceSuccess()}。
     */
    public void announce(String code) {
        if (code == null) return;
        String trimmed = code.trim();
        if (trimmed.isEmpty()) return;

        // 先停掉上一轮（例如 OTA 重新下发了一个新码）
        stop();
        synchronized (lock) {
            this.code = trimmed;
            final int gen = ++generation;
            active = true;
            Thread worker = new Thread(() -> runBindLoop(gen), "BindCodeAnnouncer");
            worker.start();
            LogUtils.getInstance().d(appContext, TAG, "开始语音播报绑定码：" + maskCode(trimmed));
        }
    }

    /** 绑定成功提示。仅在正在播报绑定码时播放，避免正常连接时多出声音。 */
    public void announceSuccess() {
        boolean wasActive = active;
        stop();
        if (!wasActive) return;

        final int gen = ++generation;
        new Thread(() -> {
            AudioTrack track = null;
            try {
                track = createTrack();
                if (track == null) return;
                playFile(track, R.raw.bind_success, gen);
            } catch (Exception e) {
                LogUtils.getInstance().e(appContext, TAG, "播报绑定成功提示失败", e);
            } finally {
                releaseTrack(track);
            }
        }, "BindCodeAnnouncer-Success").start();
    }

    /** 停止播报并立即静音。 */
    public void stop() {
        synchronized (lock) {
            active = false;
            generation++;
            lock.notifyAll();
        }
        AudioTrack track = currentTrack;
        if (track != null) {
            // 播放线程可能正阻塞在 write() 里。pause() 不会让阻塞的 write 返回，
            // 线程会一直卡在 AudioTrack 上；stop() 会丢弃缓冲并让 write 立刻返回/报错。
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            try {
                track.flush();
            } catch (Exception ignored) {
            }
        }
    }

    // ── 播放线程 ──────────────────────────────────────────────────────────

    private void runBindLoop(int gen) {
        AudioTrack track = null;
        try {
            track = createTrack();
            if (track == null) {
                LogUtils.getInstance().e(appContext, TAG,
                        "AudioTrack 创建失败，无法语音播报绑定码",
                        new IllegalStateException("AudioTrack unavailable"));
                return;
            }
            boolean first = true;
            int round = 0;
            while (isCurrent(gen)) {
                round++;
                LogUtils.getInstance().d(appContext, TAG,
                        "语音播报第 " + round + " 轮（每 " + REPEAT_INTERVAL_MS / 1000 + " 秒重播）");
                int prompt = first ? R.raw.bind_intro : R.raw.bind_repeat;
                if (!playFile(track, prompt, gen)) break;
                if (!playSilence(track, GAP_BEFORE_DIGITS_MS, gen)) break;
                if (!playDigits(track, gen)) break;

                // 6 位数字只听一遍容易听错（一/七、四/十），同一轮里紧接着再念一遍
                if (!playFile(track, R.raw.bind_again, gen)) break;
                if (!playSilence(track, GAP_BEFORE_DIGITS_MS, gen)) break;
                if (!playDigits(track, gen)) break;
                if (!isCurrent(gen)) break;

                first = false;
                // 等下一轮；stop()/announceSuccess() 会 notify 唤醒
                synchronized (lock) {
                    long deadline = SystemClock.elapsedRealtime() + REPEAT_INTERVAL_MS;
                    while (isCurrent(gen)) {
                        long remaining = deadline - SystemClock.elapsedRealtime();
                        if (remaining <= 0) break;
                        try {
                            lock.wait(remaining);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.getInstance().e(appContext, TAG, "绑定码播报线程异常", e);
        } finally {
            releaseTrack(track);
        }
    }

    /**
     * 只比较 generation：stop() 会自增 generation，让正在播放的线程（包括
     * announceSuccess() 里的一次性成功提示）立即停止。
     */
    private boolean isCurrent(int gen) {
        return gen == generation;
    }

    /** 逐位播报当前绑定码；返回 false 表示被 stop() 打断或写入失败。 */
    private boolean playDigits(AudioTrack track, int gen) {
        String currentCode = code;
        for (int i = 0; i < currentCode.length(); i++) {
            char ch = currentCode.charAt(i);
            int res = digitResource(ch);
            if (res == 0) {
                LogUtils.getInstance().d(appContext, TAG, "无法朗读的字符：" + ch);
                continue;
            }
            if (!playFile(track, res, gen)) return false;
            if (!playSilence(track, GAP_BETWEEN_DIGITS_MS, gen)) return false;
        }
        return true;
    }

    // ── AudioTrack / WAV ──────────────────────────────────────────────────

    private AudioTrack createTrack() {
        try {
            LogUtils.getInstance().d(appContext, TAG,
                    "创建播报 AudioTrack（16kHz 单声道），若长时间没有后续日志说明音频 HAL 被占用");
            int minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferSize <= 0) {
                // HAL 繁忙时可能返回错误码，退化成半秒缓冲兜底
                minBufferSize = SAMPLE_RATE / 2 * 2;
            }
            // API 22 上没有 AudioTrack.Builder，必须走旧构造方法；与 AudioEngine 保持一致
            // 使用 STREAM_MUSIC，设备上播报和对话音频走同一条输出通路。
            AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBufferSize, SAMPLE_RATE), AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                LogUtils.getInstance().e(appContext, TAG,
                        "AudioTrack 初始化失败: state=" + track.getState(),
                        new IllegalStateException("AudioTrack init failed"));
                track.release();
                return null;
            }
            currentTrack = track;
            track.play();
            LogUtils.getInstance().d(appContext, TAG, "播报 AudioTrack 已就绪");
            return track;
        } catch (Exception e) {
            LogUtils.getInstance().e(appContext, TAG, "创建 AudioTrack 失败", e);
            return null;
        }
    }

    private void releaseTrack(AudioTrack track) {
        if (track == null) return;
        if (track == currentTrack) currentTrack = null;
        try {
            track.stop();
        } catch (Exception ignored) {
        }
        try {
            track.release();
        } catch (Exception ignored) {
        }
    }

    private boolean playFile(AudioTrack track, int resId, int gen) {
        byte[] pcm = loadPcm(resId);
        if (pcm == null || pcm.length == 0) return isCurrent(gen);
        return writePcm(track, pcm, gen);
    }

    private boolean playSilence(AudioTrack track, int milliseconds, int gen) {
        int bytes = SAMPLE_RATE * 2 * milliseconds / 1000;
        // 4 字节对齐即可；16bit 单声道下 2 字节为一帧
        bytes &= ~1;
        byte[] silence = new byte[bytes];
        return writePcm(track, silence, gen);
    }

    private boolean writePcm(AudioTrack track, byte[] pcm, int gen) {
        int offset = 0;
        while (offset < pcm.length) {
            if (!isCurrent(gen)) return false;
            int count = Math.min(WRITE_CHUNK_BYTES, pcm.length - offset);
            int written = track.write(pcm, offset, count);
            if (written < 0) {
                LogUtils.getInstance().e(appContext, TAG, "AudioTrack.write 失败: " + written,
                        new IllegalStateException("AudioTrack write error"));
                return false;
            }
            if (written == 0) {
                // 被 pause()/flush() 打断，或缓冲暂时满：稍等再试
                SystemClock.sleep(20);
                continue;
            }
            offset += written;
        }
        return true;
    }

    /** 资源 → PCM 数据（去掉 WAV 头）。失败返回 null。 */
    private synchronized byte[] loadPcm(int resId) {
        byte[] cached = pcmCache.get(resId);
        if (cached != null) return cached;

        InputStream in = null;
        try {
            in = appContext.getResources().openRawResource(resId);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
            byte[] wav = out.toByteArray();
            byte[] pcm = extractWavData(wav);
            if (pcm != null) pcmCache.put(resId, pcm);
            return pcm;
        } catch (Exception e) {
            LogUtils.getInstance().e(appContext, TAG, "读取语音资源失败: resId=" + resId, e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 解析 RIFF/WAVE，返回 data 块内容。所有生成资源都是 16kHz 单声道 16bit。 */
    private static byte[] extractWavData(byte[] wav) {
        if (wav == null || wav.length < 44) return null;
        if (!"RIFF".equals(ascii(wav, 0, 4)) || !"WAVE".equals(ascii(wav, 8, 4))) return null;

        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = ascii(wav, pos, 4);
            long size = readLe32(wav, pos + 4);
            if (size < 0) size = 0;
            int body = pos + 8;
            if ("data".equals(id)) {
                int len = (int) Math.min(size, wav.length - body);
                if (len <= 0) return null;
                byte[] pcm = new byte[len];
                System.arraycopy(wav, body, pcm, 0, len);
                return pcm;
            }
            // 块按偶数字节对齐
            pos = body + (int) size + ((int) size & 1);
        }
        return null;
    }

    private static String ascii(byte[] data, int offset, int len) {
        if (offset + len > data.length) return "";
        return new String(data, offset, len, StandardCharsets.US_ASCII);
    }

    private static long readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static int digitResource(char ch) {
        switch (ch) {
            case '0': return R.raw.digit_0;
            case '1': return R.raw.digit_1;
            case '2': return R.raw.digit_2;
            case '3': return R.raw.digit_3;
            case '4': return R.raw.digit_4;
            case '5': return R.raw.digit_5;
            case '6': return R.raw.digit_6;
            case '7': return R.raw.digit_7;
            case '8': return R.raw.digit_8;
            case '9': return R.raw.digit_9;
            default: return 0;
        }
    }

    /** 日志里不要直接写完整绑定码，避免日志被导出后泄露。 */
    private static String maskCode(String code) {
        if (code.length() <= 2) return "**";
        return code.charAt(0) + "**" + code.charAt(code.length() - 1);
    }
}
