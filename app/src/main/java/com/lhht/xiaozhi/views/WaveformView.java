package com.lhht.xiaozhi.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

import com.lhht.xiaozhi.R;

/**
 * Aurora Wave View - Siri 极光风格多层波纹动画。
 *
 * 渲染策略（按 API 自动切换）：
 *   API 26+：硬件加速 + canvas.saveLayer + PorterDuff.SCREEN，颜色从资源读取（支持深色模式覆盖）
 *   API 21-25 降级：底层 FILL（alpha 0x22 基底）+ 中层/顶层 STROKE（精细线条交错），
 *                   统一使用 primary 蓝，避免多色叠加产生脏色，同时保留流动感。
 *
 * 动画生命周期：onVisibilityChanged / onDetachedFromWindow 感知，GONE 时暂停节省资源。
 */
public class WaveformView extends View {

    private static final float[] PHASE_OFFSETS = {0f, 2.09f, 4.19f};
    private static final float[] SPEED_MULTS   = {1.0f, 0.72f, 0.51f};
    private static final float[] AMP_MULTS     = {1.0f, 0.75f, 0.56f};

    private static final int   LAYER_COUNT     = 3;
    private static final float IDLE_AMP_FRAC   = 0.07f;
    private static final float ACTIVE_AMP_FRAC = 0.38f;
    private static final float LERP_FACTOR     = 0.12f;
    private static final float WAVE_FREQ       = 1.4f;

    private final boolean useScreenBlend = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

    /** 每层渐变色 [start, end]，在 init() 中从资源或主色计算 */
    private final int[][] resolvedColors = new int[LAYER_COUNT][2];

    private final Paint[] layerPaints = new Paint[LAYER_COUNT];
    private final Path    wavePath    = new Path();

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
        float density = getContext().getResources().getDisplayMetrics().density;

        if (useScreenBlend) {
            // ── 高端机（API 26+）：SCREEN 混合，颜色从资源读取支持深色模式 ────────
            setLayerType(LAYER_TYPE_HARDWARE, null);
            resolvedColors[0][0] = ContextCompat.getColor(getContext(), R.color.wave_l0_start);
            resolvedColors[0][1] = ContextCompat.getColor(getContext(), R.color.wave_l0_end);
            resolvedColors[1][0] = ContextCompat.getColor(getContext(), R.color.wave_l1_start);
            resolvedColors[1][1] = ContextCompat.getColor(getContext(), R.color.wave_l1_end);
            resolvedColors[2][0] = ContextCompat.getColor(getContext(), R.color.wave_l2_start);
            resolvedColors[2][1] = ContextCompat.getColor(getContext(), R.color.wave_l2_end);

            PorterDuffXfermode screenXfer = new PorterDuffXfermode(PorterDuff.Mode.SCREEN);
            for (int i = 0; i < LAYER_COUNT; i++) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.FILL);
                if (i > 0) p.setXfermode(screenXfer);
                layerPaints[i] = p;
            }
        } else {
            // ── 低端机（API 21-25）：FILL 基底 + STROKE 精细线条，无多色叠加 ───────
            int base = ContextCompat.getColor(getContext(), R.color.primary);
            int r = Color.red(base), g = Color.green(base), b = Color.blue(base);

            // 底层：极低 alpha 的填充面，只为提供轮廓感
            int cFill = Color.argb(0x22, r, g, b);
            resolvedColors[0] = new int[]{cFill, cFill};
            Paint p0 = new Paint(Paint.ANTI_ALIAS_FLAG);
            p0.setStyle(Paint.Style.FILL);
            layerPaints[0] = p0;

            // 中层：中等 alpha 的细描边线
            int cMid = Color.argb(0x80, r, g, b);
            resolvedColors[1] = new int[]{cMid, cMid};
            Paint p1 = new Paint(Paint.ANTI_ALIAS_FLAG);
            p1.setStyle(Paint.Style.STROKE);
            p1.setStrokeWidth(1.5f * density);
            layerPaints[1] = p1;

            // 顶层：高 alpha 的粗描边线（主波形轮廓）
            int cTop = Color.argb(0xEE, r, g, b);
            resolvedColors[2] = new int[]{cTop, cTop};
            Paint p2 = new Paint(Paint.ANTI_ALIAS_FLAG);
            p2.setStyle(Paint.Style.STROKE);
            p2.setStrokeWidth(2.5f * density);
            layerPaints[2] = p2;
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
                    resolvedColors[i][0], resolvedColors[i][1],
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
        float totalAmp = h * IDLE_AMP_FRAC + displayAmplitude;

        if (useScreenBlend) {
            int sc = canvas.saveLayer(0, 0, w, h, null);
            drawLayers(canvas, w, h, centerY, totalAmp);
            canvas.restoreToCount(sc);
        } else {
            drawLayers(canvas, w, h, centerY, totalAmp);
        }
    }

    private void drawLayers(Canvas canvas, int w, int h, float centerY, float totalAmp) {
        for (int i = 0; i < LAYER_COUNT; i++) {
            float phaseShift = globalPhase * SPEED_MULTS[i] + PHASE_OFFSETS[i];
            float layerAmp   = totalAmp * AMP_MULTS[i];
            boolean isStroke = layerPaints[i].getStyle() == Paint.Style.STROKE;

            wavePath.reset();
            if (isStroke) {
                // STROKE：只画波浪线，不需要封底，避免轮廓矩形
                float y0 = centerY - layerAmp
                        * (float) Math.sin(2 * Math.PI * 0f * WAVE_FREQ + phaseShift);
                wavePath.moveTo(0, y0);
                for (int x = 3; x <= w; x += 3) {
                    float t          = (float) x / w;
                    float nt         = 2f * t - 1f;
                    float edgeFactor = 1f - nt * nt;
                    float y = centerY - layerAmp * edgeFactor
                            * (float) Math.sin(2 * Math.PI * t * WAVE_FREQ + phaseShift);
                    wavePath.lineTo(x, y);
                }
            } else {
                // FILL：波形曲线 + 封底，形成填充区域
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
            }
            canvas.drawPath(wavePath, layerPaints[i]);
        }
    }

    // ── 动画生命周期 ──────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resumeAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pauseAnimator();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) resumeAnimator();
        else pauseAnimator();
    }

    private void resumeAnimator() {
        if (phaseAnimator != null && !phaseAnimator.isRunning()) phaseAnimator.start();
    }

    private void pauseAnimator() {
        if (phaseAnimator != null && phaseAnimator.isRunning()) phaseAnimator.cancel();
    }

    // ── 公开接口 ──────────────────────────────────────────────────────────────

    /** 设置麦克风输入振幅（0.0~1.0） */
    public void setAmplitude(float amplitude) {
        targetAmplitude = amplitude * getHeight() * ACTIVE_AMP_FRAC;
    }

    /** 设置 AI 播放输出振幅（RMS 0.0~1.0） */
    public void setPlayingAmplitude(float amplitude) {
        targetAmplitude = amplitude * getHeight() * ACTIVE_AMP_FRAC;
    }
}