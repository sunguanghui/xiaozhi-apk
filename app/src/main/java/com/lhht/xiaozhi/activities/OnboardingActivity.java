package com.lhht.xiaozhi.activities;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.lhht.xiaozhi.R;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "xiaozhi_prefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private ViewPager2 viewPager;
    private MaterialButton nextButton;
    private LinearLayout dotIndicator;
    private List<OnboardingPage> pages;
    private int currentPage = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查是否首次启动
        if (!shouldShowOnboarding(this)) {
            // 不是首次启动，直接进入主界面
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);

        viewPager = findViewById(R.id.viewPager);
        nextButton = findViewById(R.id.nextButton);
        dotIndicator = findViewById(R.id.dotIndicator);
        TextView skipButton = findViewById(R.id.skipButton);

        // 准备引导页数据（与当前功能保持一致）
        pages = new ArrayList<>();

        // 第1页：欢迎
        pages.add(new OnboardingPage(
                R.drawable.ic_mic,
                "欢迎使用灵犀",
                "心有灵犀一点通\n与 AI 实时语音对话，还支持文字交流\n官方小智平台与自建服务器均可接入"
        ));

        // 第2页：连接方式（修正：是验证码绑定，不是扫码）
        pages.add(new OnboardingPage(
                R.drawable.ic_settings,
                "两种接入方式",
                "🌐 官方平台（推荐）\n设置→开启官方模式→点击连接\n输入6位验证码在 xiaozhi.me 完成绑定\n\n🏠 自建服务器\n填写 WebSocket 地址即可直连"
        ));

        // 第3页：核心交互三步走
        pages.add(new OnboardingPage(
                R.drawable.ic_chat,
                "三步开始聊天",
                "① 点击右上角 ⚙ 进入设置并配置\n② 点击「连接」，等待状态点变绿\n③ 点击麦克风按钮，开始语音对话！\n\n💡 长按麦克风可快速打开设置"
        ));

        // 第4页：权限申请
        pages.add(new OnboardingPage(
                R.drawable.ic_mic,
                "需要麦克风权限",
                "语音对话需要访问麦克风\n如暂不授权，仍可使用文字模式与 AI 交流\n后续可随时在系统设置中开启"
        ));

        // 设置ViewPager
        OnboardingAdapter adapter = new OnboardingAdapter(pages);
        viewPager.setAdapter(adapter);
        setupDotIndicator();
        updateDotIndicator(0);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPage = position;
                updateDotIndicator(position);
                nextButton.setText(position == pages.size() - 1 ? "开始使用" : "下一步");
            }
        });

        nextButton.setOnClickListener(v -> {
            if (currentPage == pages.size() - 1) {
                // 最后一页：请求权限并进入主页
                requestPermissionsAndFinish();
            } else {
                viewPager.setCurrentItem(currentPage + 1, true);
            }
        });

        skipButton.setOnClickListener(v -> {
            // 跳过但仍需请求权限
            requestPermissionsAndFinish();
        });
    }

    private void setupDotIndicator() {
        for (int i = 0; i < pages.size(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(12, 12);
            params.setMargins(6, 0, 6, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.bg_status_dot);
            dotIndicator.addView(dot);
        }
    }

    private void updateDotIndicator(int position) {
        for (int i = 0; i < dotIndicator.getChildCount(); i++) {
            View dot = dotIndicator.getChildAt(i);
            dot.setBackgroundTintList(getColorStateList(
                    i == position ? R.color.primary : R.color.text_secondary
            ));
        }
    }

    private void requestPermissionsAndFinish() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        } else {
            finishOnboarding();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            finishOnboarding();
        }
    }

    private void finishOnboarding() {
        // 标记已完成引导
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();

        // 进入主界面
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    // 引导页数据类
    private static class OnboardingPage {
        int imageRes;
        String title;
        String description;

        OnboardingPage(int imageRes, String title, String description) {
            this.imageRes = imageRes;
            this.title = title;
            this.description = description;
        }
    }

    // ViewPager适配器
    private static class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.ViewHolder> {
        private final List<OnboardingPage> pages;

        OnboardingAdapter(List<OnboardingPage> pages) {
            this.pages = pages;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            OnboardingPage page = pages.get(position);
            holder.image.setImageResource(page.imageRes);
            holder.title.setText(page.title);
            holder.description.setText(page.description);
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView image;
            TextView title;
            TextView description;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.onboardingImage);
                title = itemView.findViewById(R.id.onboardingTitle);
                description = itemView.findViewById(R.id.onboardingDesc);
            }
        }
    }

    // 工具方法：检查是否需要显示引导页
    public static boolean shouldShowOnboarding(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true);
    }
}
