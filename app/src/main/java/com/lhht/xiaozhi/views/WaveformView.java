package com.lhht.xiaozhi.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Aurora Wave View - Siri 极光风格多层波纹动画。
 *
 * 特性：
 * - 3 层独立正弦波，各有相位偏移和振幅系数
 * - 每层使用 LinearGradient 渐变填充，叠加 SCREEN 混合产生极光微光效果
 * - ValueAnimator 驱动相位持续自增，待机时保持呼吸感，波纹绝不静止
 * - 输入振幅（麦克风 / AI 播放）通过 Lerp 平滑过渡，避免突变
 * - 抛物线边缘衰减：波纹在 View 左右两侧收拢为零
 * - 硬件加速：setLayerType(LAYER_TYPE_HARDWARE) 保证 SCREEN 混合正确渲染
 */
public class WaveformView extends View {

    /** 每层初始相位偏移（弧度） */
    private static final float[] PHASE_OFFSETS = {0f, 2.09f, 4.19f};

    /** 每层相位推进速度倍率 */
    private static final float[] SPEED_MULTS = {1.0f, 0.72f, 0.51f};

    /** 每层振幅系数 */
    private static final float[] AMP_MULTS = {1.0f, 0.75f, 0.56f};

    /** 每层渐变色 [startColor, endColor] */
    private static final int[][] LAYER_COLORS = {
        {0xFF00CFFF, 0xFF0055FF},
        {0xFFFF6EC7, 0xFFA855F7},
        {0xFF00FFA3, 0xFF00D4FF},
    };

    private static final int   LAYER_COUNT     = 3;
    private static final float IDLE_AMP_FRAC   = 0.07f;
    private static final float ACTIVE_AMP_FRAC = 0.38f;
    private static final float LERP_FACTOR     = 0.12f;
    private static final float WAVE_FREQ       = 1.4f;

    private final Paint[] layerPaints = new Paint[LAYER_COUNT];
    private final Path    wavePath    = new Path();
    private final PorterDuffXfermode screenXfer =
            new PorterDuffXfermode(PorterDuff.Mode.SCREEN);

    private ValueAnimator phaseAnimator;
    private float globalPhase      = 0f;
    private float targetAmplitude  = 0f;
    private float displayAmplitude = 0f;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        for (int i = 0; i < LAYER_COUNT; i++) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            if (i > 0) p.setXfermode(screenXfer);
            layerPaints[i] = p;
        }
        phaseAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        phaseAnimator.setDuration(3000);
        phaseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        phaseAnimator.setRepeatMode(ValueAnimator.RESTART);
        phaseAnimator.setInterpolator(new LinearInterpolator());
        phaseAnimator.addUpdateListener(anim -> {
            globalPhase = (float) anim.getAnimatedValue();
            displayAmplitude += (targetAmplitude - displayAmplitude) * LERP_FACTOR;
            postInvalidateOnAnimation();
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;
        for (int i = 0; i < LAYER_COUNT; i++) {
            layerPaints[i].setShader(new LinearGradient(
                    0, h * 0.5f, w, h * 0.5f,
                    LAYER_COLORS[i][0], LAYER_COLORS[i][1],
                    Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float centerY  = h * 0.5f;
        float idleAmp  = h * IDLE_AMP_FRAC;
        float totalAmp = idleAmp + displayAmplitude;

        int sc = canvas.saveLayer(0, 0, w, h, null);
        for (int i = 0; i < LAYER_COUNT; i++) {
            float phaseShift = globalPhase * SPEED_MULTS[i] + PHASE_OFFSETS[i];
            float layerAmp   = totalAmp * AMP_MULTS[i];

            wavePath.reset();
            wavePath.moveTo(0, h);
            for (int x = 0; x <= w; x += 3) {
                float t          = (float) x / w;
                float nt         = 2f * t - 1f;
                float edgeFactor = 1f - nt * nt;
                float y = centerY - layerAmp * edgeFactor
                        * (float) Math.sin(2 * Math.PI * t * WAVE_FREQ + phaseShift);
                wavePath.lineTo(x, y);
            }
            wavePath.lineTo(w, h);
            wavePath.close();
            canvas.drawPath(wavePath, layerPaints[i]);
        }
        canvas.restoreToCount(sc);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!phaseAnimator.isRunning()) phaseAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        phaseAnimator.cancel();
    }

    /**
     * 设置麦克风输入振幅（0.0~1.0）。
     */
    public void setAmplitude(float amplitude) {
        targetAmplitude = amplitude * getHeight() * ACTIVE_AMP_FRAC;
    }

    /**
     * 设置 AI 播放输出振幅（RMS 0.0~1.0），供 onBinaryMessage 调用。
     */
    public void setPlayingAmplitude(float amplitude) {
        targetAmplitude = amplitude * getHeight() * ACTIVE_AMP_FRAC;
    }
}