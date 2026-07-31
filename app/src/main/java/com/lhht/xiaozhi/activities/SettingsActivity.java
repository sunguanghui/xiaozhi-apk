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
    private MaterialCardView officialConfigCard;
    private MaterialCardView selfHostConfigCard;
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

    private void loadSettings() {
        // 局域网配置
        wsUrlInput.setText(settingsManager.getWsUrl());
        tokenInput.setText(settingsManager.getToken());
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
        // 保存官方/自建开关
        settingsManager.setUseOfficialServer(useOfficialSwitch.isChecked());

        // 保存局域网配置（即使是官方模式也保存，方便切换回来）
        String wsUrl = wsUrlInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
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
