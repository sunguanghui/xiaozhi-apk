package com.lhht.xiaozhi.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.lhht.xiaozhi.utils.LogUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 封装录音、播放、Opus 编解码与应用层播放缓冲（PcmFifo）的音频子系统。
 * 从 MainActivity 中拆出，UI 层只通过 Listener 回调和几个动作方法交互，
 * 不再直接操作 AudioRecord/AudioTrack。
 */
public class AudioEngine {
    private static final String TAG = "AudioEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
    private static final int OPUS_FRAME_SIZE = 960; // 60ms at 16kHz
    private static final int PCM_CHUNK_SAMPLES = 320; // 20ms @16kHz，播放线程每次消费的样本数
    private static final int FIFO_CAPACITY_SAMPLES = SAMPLE_RATE; // 应用层播放缓冲上限：1秒，超出则丢最旧样本
    private static final float SILENCE_AMPLITUDE_THRESHOLD = 0.02f;
    private static final long SILENCE_TIMEOUT_MS = 1000;
    private static final long ECHO_FLUSH_MS = 300;

    public interface Listener {
        /** 录音音量变化（0~1），用于波形动画 */
        void onRecordingAmplitude(float amplitude);
        /** 播放中的 RMS 振幅（0~1），用于波形动画 */
        void onPlaybackAmplitude(float rms);
        /** 需要发送给服务端的编码后音频（Opus），可能是语音帧或静音帧 */
        void onEncodedAudio(byte[] data);
        /** 一轮 TTS 播放完成，FIFO 已排空，可以安全重新开始录音发送 */
        void onPlaybackDrained();
        /** 一次 TTS 播放的结构化延迟汇总 */
        void onLatencySummary(String summary);
        /** 录音/播放不可用等需要用户感知的音频错误 */
        void onAudioError(String message);
    }

    private final Context context;
    private volatile Listener listener;

    private volatile AudioRecord audioRecord;
    private volatile AudioTrack audioTrack;
    private final OpusUtils opusUtils;
    private long encoderHandle;
    private long decoderHandle;
    private final PcmFifo audioFifo;
    private final LatencyTracker latencyTracker = new LatencyTracker();

    private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private volatile Thread playbackThread;
    private volatile boolean playbackThreadRunning = false;

    private volatile boolean isRecording = false;
    private volatile boolean isPlaying = false;
    private volatile long flushUntilMs = 0;
    private volatile boolean firstAudioDataReceived = false;
    private volatile boolean firstAudioWritten = false;

    /** 录音循环代次：stop/release/重新 start 时递增，旧循环检测到代次变化后退出并自行释放 AudioRecord。 */
    private volatile int recordGeneration = 0;
    /** 播放回合代次：新一轮开始/结束通话时递增，用于丢弃属于旧回合的迟到音频包。 */
    private volatile int playbackEpoch = 0;
    /** 本轮播放中 FIFO 欠载（补静音）的次数，排空时输出，便于观察网络抖动。 */
    private volatile int underrunChunks = 0;

    public AudioEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.opusUtils = OpusUtils.getInstance();
        this.audioFifo = new PcmFifo(FIFO_CAPACITY_SAMPLES);
    }

    /** 宿主 Activity 重建时重新绑定回调；旧 Activity 销毁后应置空。 */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 初始化 AudioTrack、Opus 编解码器，并启动独立播放消费线程。在会话首次创建时调用一次。 */
    public void init() {
        initAudioTrack();
        encoderHandle = opusUtils.createEncoder(SAMPLE_RATE, 1, 10);
        decoderHandle = opusUtils.createDecoder(SAMPLE_RATE, 1);
        if (encoderHandle == 0 || decoderHandle == 0) {
            LogUtils.getInstance().e(context, TAG,
                    "Opus 编解码器初始化失败 encoder=" + encoderHandle + " decoder=" + decoderHandle,
                    new IllegalStateException("opus init failed"));
        }
        startPlaybackThread();
    }

    private void initAudioTrack() {
        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
        try {
            AudioTrack track;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // AudioTrack.Builder 是 API 23 才有的；API 21/22 必须走下面的旧构造方法
                AudioTrack.Builder builder = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(minBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM);

                // PERFORMANCE_MODE_LOW_LATENCY 是 API 26 才有的方法，低版本调用会 NoSuchMethodError
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                }
                track = builder.build();
            } else {
                //noinspection deprecation
                track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT,
                        minBufferSize, AudioTrack.MODE_STREAM);
            }
            audioTrack = track;
            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                Log.i(TAG, "AudioTrack初始化成功（缓冲 " + minBufferSize + " 字节，API " + Build.VERSION.SDK_INT + "）");
                audioTrack.play();
            } else {
                Log.e(TAG, "AudioTrack初始化失败: " + audioTrack.getState());
                audioTrack = null;
            }
        } catch (Exception e) {
            LogUtils.getInstance().e(context, TAG, "创建AudioTrack失败", e);
            audioTrack = null;
        }
    }

    /** 播放通道是否可用；初始化失败时 UI 应给出明确提示而不是“已连接但没声音”。 */
    public boolean isPlaybackAvailable() {
        AudioTrack track = audioTrack;
        return track != null && track.getState() == AudioTrack.STATE_INITIALIZED;
    }

    private void startPlaybackThread() {
        playbackThreadRunning = true;
        playbackThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            short[] chunk = new short[PCM_CHUNK_SAMPLES];
            byte[] pcmBytes = new byte[PCM_CHUNK_SAMPLES * 2];
            while (playbackThreadRunning) {
                if (!isPlaying) {
                    sleepQuietly(20);
                    continue;
                }
                AudioTrack track = audioTrack;
                // 播放通道不可用时必须退避，否则会 100% 空转
                if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
                    sleepQuietly(50);
                    continue;
                }
                try {
                    int gotSamples = audioFifo.pull(chunk, 0, PCM_CHUNK_SAMPLES);
                    if (gotSamples < PCM_CHUNK_SAMPLES) {
                        underrunChunks++;
                    }
                    for (int i = 0; i < PCM_CHUNK_SAMPLES; i++) {
                        short sample = chunk[i];
                        pcmBytes[i * 2] = (byte) (sample & 0xff);
                        pcmBytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play();
                    }
                    int result = writeBlocking(track, pcmBytes);
                    if (result <= 0) {
                        Log.w(TAG, "AudioTrack.write 返回 " + result + "，暂停 50ms");
                        sleepQuietly(50);
                        continue;
                    }
                    if (!firstAudioWritten && gotSamples > 0) {
                        firstAudioWritten = true;
                        latencyTracker.mark("first_track_write");
                        String summary = latencyTracker.summary();
                        latencyTracker.logSummary(context, "TTS播放延迟");
                        Listener l = listener;
                        if (l != null) l.onLatencySummary(summary);
                    }
                } catch (Exception e) {
                    LogUtils.getInstance().e(context, TAG, "播放线程异常", e);
                    sleepQuietly(50);
                }
            }
        }, "AudioPlaybackThread");
        playbackThread.start();
    }

    private static int writeBlocking(AudioTrack track, byte[] data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return track.write(data, 0, data.length, AudioTrack.WRITE_BLOCKING);
        }
        // API 21/22 没有带 writeMode 的重载；MODE_STREAM 下三参 write 即为阻塞写
        return track.write(data, 0, data.length);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 录音 ─────────────────────────────────────────────────────────────

    public boolean isRecording() {
        return isRecording;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void startRecording() {
        if (isRecording) return;

        // 用户可能在系统设置里撤销了权限；显式检查，避免 AudioRecord 抛 SecurityException
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            notifyAudioError("未授予麦克风权限，无法开始语音通话");
            return;
        }

        final int generation = ++recordGeneration;
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
        } catch (Exception e) {
            LogUtils.getInstance().e(context, TAG, "创建AudioRecord失败", e);
            notifyAudioError("麦克风初始化失败: " + e.getMessage());
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            LogUtils.getInstance().e(context, TAG,
                    "AudioRecord 状态异常: " + record.getState(),
                    new IllegalStateException("AudioRecord not initialized"));
            notifyAudioError("麦克风不可用，请检查权限或重启应用");
            return;
        }

        audioRecord = record;
        isRecording = true;
        recordExecutor.execute(() -> recordLoop(record, generation));
    }

    private void recordLoop(AudioRecord record, int generation) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buffer = new short[OPUS_FRAME_SIZE];
        byte[] encodedBuffer = new byte[1024];
        long lastAudioTime = System.currentTimeMillis();
        try {
            record.startRecording();
            while (isRecording && generation == recordGeneration) {
                int samplesRead = record.read(buffer, 0, OPUS_FRAME_SIZE);
                if (samplesRead <= 0) continue;

                float amplitude = 0;
                for (int i = 0; i < samplesRead; i++) {
                    amplitude = Math.max(amplitude, Math.abs(buffer[i]) / 32768.0f);
                }
                Listener l = listener;
                if (l != null) l.onRecordingAmplitude(amplitude);

                boolean isSilent = amplitude < SILENCE_AMPLITUDE_THRESHOLD;
                if (!isSilent) {
                    lastAudioTime = System.currentTimeMillis();
                }

                // 当 samplesRead < 960 时补零，避免 Opus 编码器因帧不足而失败
                if (samplesRead < OPUS_FRAME_SIZE) {
                    for (int i = samplesRead; i < OPUS_FRAME_SIZE; i++) buffer[i] = 0;
                }
                int encodedBytes = opusUtils.encode(encoderHandle, buffer, 0, encodedBuffer);
                if (encodedBytes > 0) {
                    long now = System.currentTimeMillis();
                    // isPlaying=true：AI 正在说话，跳过发送（防止实时回声）
                    // flushUntilMs：播放刚排空，清空 AudioRecord 里积压的回声帧
                    if (!isPlaying && now >= flushUntilMs) {
                        byte[] encodedData = new byte[encodedBytes];
                        System.arraycopy(encodedBuffer, 0, encodedData, 0, encodedBytes);
                        Listener callback = listener;
                        if (callback != null) callback.onEncodedAudio(encodedData);
                    }
                } else {
                    Log.e(TAG, "Opus编码失败: " + encodedBytes);
                }

                if (!isPlaying && System.currentTimeMillis() >= flushUntilMs
                        && System.currentTimeMillis() - lastAudioTime > SILENCE_TIMEOUT_MS) {
                    short[] silenceFrame = new short[OPUS_FRAME_SIZE];
                    int silenceBytes = opusUtils.encode(encoderHandle, silenceFrame, 0, encodedBuffer);
                    if (silenceBytes > 0) {
                        byte[] silenceData = new byte[silenceBytes];
                        System.arraycopy(encodedBuffer, 0, silenceData, 0, silenceBytes);
                        Listener callback = listener;
                        if (callback != null) callback.onEncodedAudio(silenceData);
                    }
                    Listener callback = listener;
                    if (callback != null) callback.onRecordingAmplitude(0);
                }
            }
        } catch (Exception e) {
            if (isRecording && generation == recordGeneration) {
                LogUtils.getInstance().e(context, TAG, "录音线程异常", e);
                notifyAudioError("录音异常: " + e.getMessage());
            }
        } finally {
            try {
                record.stop();
            } catch (Exception ignored) {
                // 可能尚未 startRecording 或已被系统回收
            }
            try {
                record.release();
            } catch (Exception ignored) {
                // 忽略重复释放
            }
            if (audioRecord == record) {
                audioRecord = null;
            }
            Log.i(TAG, "录音线程退出 (generation=" + generation + ")");
        }
    }

    /**
     * 停止录音：只置标志 + 递增代次，音频资源由录音线程在 finally 中释放，
     * 避免在 UI 线程上 release() 与正在执行的 read() 发生竞态。
     */
    public void stopRecording() {
        isRecording = false;
        recordGeneration++;
        isPlaying = false;
        playbackEpoch++;
        audioFifo.clear();
        underrunChunks = 0;
    }

    // ── 播放 ─────────────────────────────────────────────────────────────

    /**
     * 收到 tts.start / sentence_start。
     *
     * @param newRound true 表示一条新的 TTS 回复（tts.start）：清空 FIFO 中上一轮的残余音频、
     *                 重置延迟埋点与欠载计数；false 表示同一回复的下一句（sentence_start），
     *                 仅保持播放状态，避免把上一句还没播完的音频清掉。
     */
    public void beginPlayback(boolean newRound) {
        if (newRound) {
            playbackEpoch++;
            audioFifo.clear();
            underrunChunks = 0;
            firstAudioDataReceived = false;
            firstAudioWritten = false;
            latencyTracker.reset();
            latencyTracker.mark("tts_start");
        }
        isPlaying = true;
    }

    /**
     * 收到 tts.stop：异步等待 FIFO 排空后回调 onPlaybackDrained，
     * 排空后额外丢弃 300ms 录音帧以清除 AudioRecord 里积压的回声帧
     * （对齐 py-xiaozhi clear_audio_queue / xiaozhi-android waitForPlaybackCompletion）。
     */
    public void endPlayback() {
        final int epoch = playbackEpoch;
        audioExecutor.execute(() -> {
            waitForFifoDrain();
            // 等待期间可能已经开始下一轮（或用户挂断），此时不能再把状态改成“已停止”
            if (epoch != playbackEpoch) return;
            isPlaying = false;
            Log.i(TAG, "本轮播放排空，FIFO 欠载 " + underrunChunks + " 次");
            Listener l = listener;
            if (l != null) l.onPlaybackDrained();
        });
    }

    private void waitForFifoDrain() {
        try {
            int stableCount = 0;
            while (stableCount < 5) {
                Thread.sleep(20);
                if (audioFifo.available() == 0) {
                    stableCount++;
                } else {
                    stableCount = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushUntilMs = System.currentTimeMillis() + ECHO_FLUSH_MS;
    }

    /** 收到服务端二进制 Opus 音频帧：解码后推入播放 FIFO，播放节奏由独立播放线程消费 */
    public void feedEncodedAudio(byte[] data) {
        if (!isPlaying) return;

        if (!firstAudioDataReceived) {
            firstAudioDataReceived = true;
            latencyTracker.mark("first_binary");
        }

        if (decoderHandle == 0) {
            Log.e(TAG, "错误: Opus解码器未初始化");
            return;
        }

        final byte[] audioData = data.clone();
        final int epoch = playbackEpoch;
        audioExecutor.execute(() -> {
            // 解码任务排队期间可能已经开始新一轮 / 挂断，旧回合的包直接丢弃
            if (epoch != playbackEpoch) return;
            try {
                short[] decodeBuf = new short[OPUS_FRAME_SIZE];
                int decodedSamples = opusUtils.decode(decoderHandle, audioData, decodeBuf);
                if (decodedSamples < 0) {
                    Log.e(TAG, "Opus解码失败: " + decodedSamples);
                    return;
                }
                if (decodedSamples == 0 || epoch != playbackEpoch) return;

                latencyTracker.mark("first_decode");

                long sumSq = 0;
                for (int i = 0; i < decodedSamples; i++) sumSq += (long) decodeBuf[i] * decodeBuf[i];
                float rms = (float) Math.sqrt((double) sumSq / decodedSamples) / 32768f;
                Listener l = listener;
                if (l != null) l.onPlaybackAmplitude(rms);

                audioFifo.push(decodeBuf, 0, decodedSamples);
            } catch (Exception e) {
                LogUtils.getInstance().e(context, TAG, "解码音频失败", e);
            }
        });
    }

    /** 释放所有资源，在会话 ViewModel 被清理时调用 */
    public void release() {
        isRecording = false;
        isPlaying = false;
        recordGeneration++;
        playbackEpoch++;
        playbackThreadRunning = false;
        audioFifo.clear();

        Thread thread = playbackThread;
        playbackThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
        }

        // 录音线程会在 finally 中自行释放 AudioRecord；这里只等待它退出
        recordExecutor.shutdownNow();
        audioExecutor.shutdownNow();
        try {
            recordExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
            audioExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (encoderHandle != 0) {
            opusUtils.destroyEncoder(encoderHandle);
            encoderHandle = 0;
        }
        if (decoderHandle != 0) {
            opusUtils.destroyDecoder(decoderHandle);
            decoderHandle = 0;
        }
        Log.i(TAG, "AudioEngine 已释放");
    }

    private void notifyAudioError(String message) {
        LogUtils.getInstance().e(context, TAG, message, new IllegalStateException(message));
        Listener l = listener;
        if (l != null) l.onAudioError(message);
    }
}
