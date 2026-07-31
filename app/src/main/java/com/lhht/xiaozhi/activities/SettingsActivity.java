package com.lhht.xiaozhi.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.lhht.xiaozhi.R;
import com.lhht.xiaozhi.settings.SettingsManager;
import com.lhht.xiaozhi.utils.LogUtils;

public class SettingsActivity extends AppCompatActivity {
    private SettingsManager settingsManager;
    private EditText wsUrlInput;
    private EditText tokenInput;
    private Switch enableTokenSwitch;
    private Switch useOfficialServerSwitch;
    private EditText deviceIdInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);

        wsUrlInput = findViewById(R.id.wsUrlInput);
        tokenInput = findViewById(R.id.tokenInput);
        enableTokenSwitch = findViewById(R.id.enableTokenSwitch);
        useOfficialServerSwitch = findViewById(R.id.useOfficialServerSwitch);
        Button saveButton = findViewById(R.id.saveButton);
        Button exportLogButton = findViewById(R.id.exportLogButton);

        // 加载当前设置
        wsUrlInput.setText(settingsManager.getWsUrl());
        tokenInput.setText(settingsManager.getToken());
        enableTokenSwitch.setChecked(settingsManager.isTokenEnabled());
        useOfficialServerSwitch.setChecked(settingsManager.isUseOfficialServer());

        deviceIdInput = findViewById(R.id.deviceIdInput);
        deviceIdInput.setText(settingsManager.getDeviceId(this));

        // 初始化禁用/启用状态
        updateOfficialServerState(settingsManager.isUseOfficialServer());
        updateTokenInputState();

        // 官方平台 Switch：开启时禁用 URL 和 Token 区域
        useOfficialServerSwitch.setOnCheckedChangeListener((btn, isChecked) ->
                updateOfficialServerState(isChecked));

        // Token 开关仅在手动模式下有效
        enableTokenSwitch.setOnCheckedChangeListener((btn, isChecked) ->
                updateTokenInputState());

        // 保存设置
        saveButton.setOnClickListener(v -> {
            String wsUrl = wsUrlInput.getText().toString();
            String token = tokenInput.getText().toString();
            boolean enableToken = enableTokenSwitch.isChecked();
            boolean useOfficial = useOfficialServerSwitch.isChecked();
            String deviceId = deviceIdInput.getText().toString();

            settingsManager.saveSettings(wsUrl, token, enableToken);
            settingsManager.setUseOfficialServer(useOfficial);
            settingsManager.saveDeviceId(deviceId);
            finish();
        });

        // 导出日志按钮
        exportLogButton.setOnClickListener(v ->
                LogUtils.getInstance().startExportLog(this));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LogUtils.EXPORT_LOG_REQUEST_CODE
                && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                LogUtils.getInstance().handleExportResult(this, uri);
            }
        }
    }

    /** 官方模式下禁用手动 URL / Token 输入区域 */
    private void updateOfficialServerState(boolean useOfficial) {
        wsUrlInput.setEnabled(!useOfficial);
        wsUrlInput.setAlpha(useOfficial ? 0.4f : 1.0f);
        enableTokenSwitch.setEnabled(!useOfficial);
        enableTokenSwitch.setAlpha(useOfficial ? 0.4f : 1.0f);
        tokenInput.setEnabled(!useOfficial);
        tokenInput.setAlpha(useOfficial ? 0.4f : 1.0f);
    }

    private void updateTokenInputState() {
        // Token 输入框跟随 Token 开关，但前提是非官方模式
        boolean officialMode = useOfficialServerSwitch.isChecked();
        tokenInput.setEnabled(!officialMode && enableTokenSwitch.isChecked());
    }
}
