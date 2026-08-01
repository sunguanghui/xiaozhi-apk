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
 * 颜色来源：wave_l0_start/end、wave_l1_start/end、wave_l2_start/end
 *   在 values/colors.xml 中定义亮色极光，values-night/colors.xml 中定义低饱和暗色。
 *
 * 渲染策略（按 API 自动切换）：
 *   API 26+：硬件加速 + canvas.saveLayer + PorterDuff.SCREEN（完整极光效果）
 *   API 21-25：直接叠绘，颜色统一为 primary 主色不同 alpha，色彩纯净无脏色
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

    /** API 26+ 使用 SCREEN 混合，低端机退为半透明叠绘 */
    private final boolean useScreenBlend = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

    /** 每层渐变色 [start, end]，在 init() 中根据模式从资源解析 */
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
        if (useScreenBlend) {
            setLayerType(LAYER_TYPE_HARDWARE, null);
            // 高端机：从资源读取极光色，支持 values-night 深色覆盖
            resolvedColors[0][0] = ContextCompat.getColor(getContext(), R.color.wave_l0_start);
            resolvedColors[0][1] = ContextCompat.getColor(getContext(), R.color.wave_l0_end);
            resolvedColors[1][0] = ContextCompat.getColor(getContext(), R.color.wave_l1_start);
            resolvedColors[1][1] = ContextCompat.getColor(getContext(), R.color.wave_l1_end);
            resolvedColors[2][0] = ContextCompat.getColor(getContext(), R.color.wave_l2_start);
            resolvedColors[2][1] = ContextCompat.getColor(getContext(), R.color.wave_l2_end);
        } else {
            // 低端机降级：统一使用 primary 蓝，三层只区分 alpha，不混色，不出脏色
            int base = ContextCompat.getColor(getContext(), R.color.primary);
            int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
            int c0 = Color.argb(0xCC, r, g, b); // 80% 不透明
            int c1 = Color.argb(0x80, r, g, b); // 50% 不透明
            int c2 = Color.argb(0x4D, r, g, b); // 30% 不透明
            resolvedColors[0] = new int[]{c0, c0};
            resolvedColors[1] = new int[]{c1, c1};
            resolvedColors[2] = new int[]{c2, c2};
        }

        PorterDuffXfermode screenXfer = new PorterDuffXfermode(PorterDuff.Mode.SCREEN);
        for (int i = 0; i < LAYER_COUNT; i++) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            if (useScreenBlend && i > 0) p.setXfermode(screenXfer);
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
        // 将解析好的颜色绑定到各层渐变 shader
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