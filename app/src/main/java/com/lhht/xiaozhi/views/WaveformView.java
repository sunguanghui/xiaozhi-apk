package com.lhht.xiaozhi.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 音频波形动画 View。
 * 振幅越大颜色越绿（说话时蓝→绿渐变），静音时柔和蓝色。
 * 参考：xiaozhi-android-client VoiceCallScreen._getBarColor()
 */
public class WaveformView extends View {
    private final Paint wavePaint = new Paint();
    private final Path  wavePath  = new Path();
    private float amplitude = 0f;
    private float phase     = 0f;

    // 动画参数
    private static final float FREQUENCY = 1.2f;
    private static final float VELOCITY  = 0.2f;

    // 颜色渐变端点：静音蓝 → 活跃绿
    private static final int COLOR_IDLE   = Color.parseColor("#40C4FF"); // 柔和蓝
    private static final int COLOR_ACTIVE = Color.parseColor("#4CAF50"); // 活跃绿

    public WaveformView(Context context) {
        super(context);
        initPaint();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaint();
    }

    private void initPaint() {
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(4f);
        wavePaint.setAntiAlias(true);
        wavePaint.setColor(COLOR_IDLE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width   = getWidth();
        float height  = getHeight();
        float centerY = height / 2f;

        // 根据振幅（0~1）在蓝绿之间插值
        float t = Math.min(1f, amplitude / (height / 4f)); // 归一化振幅
        wavePaint.setColor(lerpColor(COLOR_IDLE, COLOR_ACTIVE, t));
        // 振幅越大线条越粗（2~5dp），增强视觉反馈
        wavePaint.setStrokeWidth(2f + 3f * t);

        wavePath.reset();
        wavePath.moveTo(0, centerY);
        for (float x = 0; x < width; x++) {
            float y = centerY + amplitude
                    * (float) Math.sin(2 * Math.PI * (x / width) * FREQUENCY + phase);
            wavePath.lineTo(x, y);
        }
        canvas.drawPath(wavePath, wavePaint);

        phase += VELOCITY;
        if (phase > 2 * Math.PI) phase = 0;

        // 有振幅时持续刷新；静音时停止刷新节省电量
        if (amplitude > 0.5f) {
            invalidate();
        }
    }

    public void setAmplitude(float amplitude) {
        this.amplitude = amplitude * getHeight() / 4f;
        invalidate();
    }

    /** 线性插值两个颜色 */
    private static int lerpColor(int from, int to, float t) {
        int r = (int) (Color.red(from)   + t * (Color.red(to)   - Color.red(from)));
        int g = (int) (Color.green(from) + t * (Color.green(to) - Color.green(from)));
        int b = (int) (Color.blue(from)  + t * (Color.blue(to)  - Color.blue(from)));
        int a = (int) (Color.alpha(from) + t * (Color.alpha(to) - Color.alpha(from)));
        return Color.argb(a, r, g, b);
    }
}
