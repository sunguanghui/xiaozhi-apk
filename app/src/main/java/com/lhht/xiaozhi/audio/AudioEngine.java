package com.lhht.xiaozhi.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import com.lhht.xiaozhi.utils.LogUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    }

    private final Context context;
    private final Listener listener;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private final OpusUtils opusUtils;
    private long encoderHandle;
    private long decoderHandle;
    private final PcmFifo audioFifo;
    private final LatencyTracker latencyTracker = new LatencyTracker();

    private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private Thread playbackThread;
    private volatile boolean playbackThreadRunning = false;

    private volatile boolean isRecording = false;
    private volatile boolean isPlaying = false;
    private volatile long flushUntilMs = 0;
    private volatile boolean firstAudioDataReceived = false;
    private volatile boolean firstAudioWritten = false;

    public AudioEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.opusUtils = OpusUtils.getInstance();
        this.audioFifo = new PcmFifo(FIFO_CAPACITY_SAMPLES);
    }

    /** 初始化 AudioTrack、Opus 编解码器，并启动独立播放消费线程。在宿主 onCreate 中调用一次。 */
    public void init() {
        initAudioTrack();
        encoderHandle = opusUtils.createEncoder(SAMPLE_RATE, 1, 10);
        decoderHandle = opusUtils.createDecoder(SAMPLE_RATE, 1);
        startPlaybackThread();
    }

    private void initAudioTrack() {
        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
        try {
            audioTrack = new AudioTrack.Builder()
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
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                Log.i(TAG, "AudioTrack初始化成功（低延迟模式，缓冲 " + minBufferSize + " 字节）");
                audioTrack.play();
            } else {
                Log.e(TAG, "AudioTrack初始化失败: " + audioTrack.getState());
            }
        } catch (Exception e) {
            LogUtils.getInstance().e(context, TAG, "创建AudioTrack失败", e);
        }
    }

    private void startPlaybackThread() {
        playbackThreadRunning = true;
        playbackThread = new Thread(() -> {
            short[] chunk = new short[PCM_CHUNK_SAMPLES];
            byte[] pcmBytes = new byte[PCM_CHUNK_SAMPLES * 2];
            while (playbackThreadRunning) {
                if (!isPlaying) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    continue;
                }
                int gotSamples = audioFifo.pull(chunk, 0, PCM_CHUNK_SAMPLES);
                for (int i = 0; i < PCM_CHUNK_SAMPLES; i++) {
                    short sample = chunk[i];
                    pcmBytes[i * 2] = (byte) (sample & 0xff);
                    pcmBytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
                }
                if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                    if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play();
                    }
                    int result = audioTrack.write(pcmBytes, 0, pcmBytes.length, AudioTrack.WRITE_BLOCKING);
                    if (result > 0 && !firstAudioWritten && gotSamples > 0) {
                        firstAudioWritten = true;
                        latencyTracker.mark("first_track_write");
                        String summary = latencyTracker.summary();
                        latencyTracker.logSummary(context, "TTS播放延迟");
                        if (listener != null) listener.onLatencySummary(summary);
                    }
                }
            }
        }, "AudioPlaybackThread");
        playbackThread.start();
    }

    // ── 录音 ─────────────────────────────────────────────────────────────

    public boolean isRecording() {
        return isRecording;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void startRecording() {
        if (audioRecord == null) {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
        }
        isRecording = true;
        recordExecutor.execute(() -> {
            audioRecord.startRecording();
            short[] buffer = new short[OPUS_FRAME_SIZE];
            byte[] encodedBuffer = new byte[1024];
            long lastAudioTime = System.currentTimeMillis();

            while (isRecording) {
                int samplesRead = audioRecord.read(buffer, 0, OPUS_FRAME_SIZE);
                if (samplesRead <= 0) continue;

                float amplitude = 0;
                for (int i = 0; i < samplesRead; i++) {
                    amplitude = Math.max(amplitude, Math.abs(buffer[i]) / 32768.0f);
                }
                if (listener != null) listener.onRecordingAmplitude(amplitude);

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
                        if (listener != null) listener.onEncodedAudio(encodedData);
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
                        if (listener != null) listener.onEncodedAudio(silenceData);
                    }
                    if (listener != null) listener.onRecordingAmplitude(0);
                }
            }
        });
    }

    public void stopRecording() {
        isRecording = false;
        isPlaying = false; // 重置播放状态，防止下次通话被屏蔽
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    // ── 播放 ─────────────────────────────────────────────────────────────

    /** 收到 tts.start / sentence_start：开始一轮新的播放，重置延迟埋点 */
    public void beginPlayback() {
        firstAudioDataReceived = false;
        firstAudioWritten = false;
        latencyTracker.reset();
        latencyTracker.mark("tts_start");
        isPlaying = true;
    }

    /**
     * 收到 tts.stop：异步等待 FIFO 排空后回调 onPlaybackDrained，
     * 排空后额外丢弃 300ms 录音帧以清除 AudioRecord 里积压的回声帧
     * （对齐 py-xiaozhi clear_audio_queue / xiaozhi-android waitForPlaybackCompletion）。
     */
    public void endPlayback() {
        audioExecutor.execute(() -> {
            waitForFifoDrain();
            isPlaying = false;
            if (listener != null) listener.onPlaybackDrained();
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
        } catch (InterruptedException ignored) {}
        flushUntilMs = System.currentTimeMillis() + ECHO_FLUSH_MS;
    }

    /** 收到服务端二进制 Opus 音频帧：解码后推入播放 FIFO，播放节奏由独立播放线程消费 */
    public void feedEncodedAudio(byte[] data) {
        if (!isPlaying) return;

        if (!firstAudioDataReceived) {
            firstAudioDataReceived = true;
            latencyTracker.mark("first_binary");
        }

        final byte[] audioData = data.clone();
        if (decoderHandle == 0) {
            Log.e(TAG, "错误: Opus解码器未初始化");
            return;
        }

        audioExecutor.execute(() -> {
            try {
                short[] decodeBuf = new short[OPUS_FRAME_SIZE];
                int decodedSamples = opusUtils.decode(decoderHandle, audioData, decodeBuf);
                if (decodedSamples < 0) {
                    Log.e(TAG, "Opus解码失败: " + decodedSamples);
                    return;
                }
                if (decodedSamples == 0) return;

                latencyTracker.mark("first_decode");

                long sumSq = 0;
                for (int i = 0; i < decodedSamples; i++) sumSq += (long) decodeBuf[i] * decodeBuf[i];
                float rms = (float) Math.sqrt((double) sumSq / decodedSamples) / 32768f;
                if (listener != null) listener.onPlaybackAmplitude(rms);

                audioFifo.push(decodeBuf, 0, decodedSamples);
            } catch (Exception e) {
                LogUtils.getInstance().e(context, TAG, "解码音频失败", e);
            }
        });
    }

    /** 释放所有资源，在宿主 onDestroy 中调用 */
    public void release() {
        isRecording = false;
        isPlaying = false;
        playbackThreadRunning = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        if (encoderHandle != 0) {
            opusUtils.destroyEncoder(encoderHandle);
            encoderHandle = 0;
        }
        if (decoderHandle != 0) {
            opusUtils.destroyDecoder(decoderHandle);
            decoderHandle = 0;
        }
        recordExecutor.shutdown();
        audioExecutor.shutdown();
    }
}
