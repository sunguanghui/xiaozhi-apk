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

        // 准备引导页数据
        pages = new ArrayList<>();
        pages.add(new OnboardingPage(
                R.drawable.ic_call,
                "欢迎使用小智语音助手",
                "通过语音与AI助手自然对话\n支持官方平台和自建服务器"
        ));
        pages.add(new OnboardingPage(
                R.drawable.ic_settings,
                "灵活的连接方式",
                "官方平台：开箱即用，扫码绑定\n自建服务器：完全掌控，私有部署"
        ));
        pages.add(new OnboardingPage(
                R.drawable.ic_chat,
                "语音+文字双模式",
                "主界面大按钮轻松开启语音对话\n文字输入作为辅助交互方式"
        ));
        pages.add(new OnboardingPage(
                android.R.drawable.ic_dialog_info,
                "需要麦克风权限",
                "为了实现语音对话功能\n应用需要访问您的麦克风"
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
