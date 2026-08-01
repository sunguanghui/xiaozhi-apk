package com.lhht.xiaozhi.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.utils.LogUtils;

import java.io.File;

public class SettingsActivity extends AppCompatActivity {
    private SettingsManager settingsManager;
    private TextInputEditText wsUrlInput;
    private TextInputEditText tokenInput;
    private SwitchMaterial enableTokenSwitch;
    private SwitchMaterial useOfficialSwitch;
    // 大屏布局（sw600dp）中该容器为 LinearLayout，使用基类 View 兼容两种布局
    private View officialConfigCard;
    private View selfHostConfigCard;
    private TextView deviceIdText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);

        // Toolbar 设置
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // 初始化视图
        wsUrlInput = findViewById(R.id.wsUrlInput);
        tokenInput = findViewById(R.id.tokenInput);
        enableTokenSwitch = findViewById(R.id.enableTokenSwitch);
        useOfficialSwitch = findViewById(R.id.useOfficialSwitch);
        officialConfigCard = findViewById(R.id.officialConfigCard);
        selfHostConfigCard = findViewById(R.id.selfHostConfigCard);
        deviceIdText = findViewById(R.id.deviceIdText);
        MaterialButton saveButton = findViewById(R.id.saveButton);

        // 加载当前设置
        loadSettings();

        // 官方/自建开关切换
        useOfficialSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateConfigVisibility(isChecked);
        });

        // 保存按钮
        saveButton.setOnClickListener(v -> saveSettings());
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_export_log) {
            exportLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String actualToken = ""; // 存储真实 token，脱敏显示时备用

    private void loadSettings() {
        // 局域网配置
        wsUrlInput.setText(settingsManager.getWsUrl());

        // Token 脱敏显示：参考 xiaozhi-android-client，前8位 + ****
        actualToken = settingsManager.getToken();
        tokenInput.setText(maskToken(actualToken));
        // 获焦时清空以便重新输入新 Token
        tokenInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                String current = tokenInput.getText().toString();
                if (isMasked(current)) {
                    tokenInput.setText("");
                    tokenInput.setHint("输入新 Token（留空则保持原值不变）");
                }
            }
        });

        enableTokenSwitch.setChecked(settingsManager.isTokenEnabled());

        // 官方平台开关
        boolean useOfficial = settingsManager.isUseOfficialServer();
        useOfficialSwitch.setChecked(useOfficial);

        // 设备ID
        String deviceId = settingsManager.getFormattedDeviceId(this);
        deviceIdText.setText(deviceId);

        // 更新卡片可见性
        updateConfigVisibility(useOfficial);
    }

    /** 将 token 脱敏：前8位明文 + **** */
    private static String maskToken(String token) {
        if (token == null || token.isEmpty()) return "";
        int visible = Math.min(8, token.length());
        return token.substring(0, visible) + "****";
    }

    /** 判断当前字段内容是否为脱敏格式（以 **** 结尾） */
    private static boolean isMasked(String s) {
        return s != null && s.endsWith("****");
    }

    private void updateConfigVisibility(boolean useOfficial) {
        if (useOfficial) {
            officialConfigCard.setVisibility(View.VISIBLE);
            selfHostConfigCard.setVisibility(View.GONE);
        } else {
            officialConfigCard.setVisibility(View.GONE);
            selfHostConfigCard.setVisibility(View.VISIBLE);
        }
    }

    private void saveSettings() {
        settingsManager.setUseOfficialServer(useOfficialSwitch.isChecked());

        String wsUrl = wsUrlInput.getText().toString().trim();
        String inputToken = tokenInput.getText().toString().trim();
        // Token 修改判断：与脱敏后的原始值完全相同，或为空，则视为未修改，保留原值
        String token = (inputToken.isEmpty() || inputToken.equals(maskToken(actualToken)))
                ? actualToken : inputToken;
        boolean enableToken = enableTokenSwitch.isChecked();
        settingsManager.saveSettings(wsUrl, token, enableToken);

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void exportLog() {
        try {
            File logFile = LogUtils.getInstance().getLogFile(this);
            if (logFile == null || !logFile.exists()) {
                Toast.makeText(this, "日志文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri logUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    logFile
            );

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, logUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_log)));
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
