package com.lhht.xiaozhi.session;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.lhht.xiaozhi.audio.AudioEngine;
import com.lhht.xiaozhi.utils.OtaService;
import com.lhht.xiaozhi.websocket.WebSocketManager;

/**
 * 跨 Activity 配置变更（旋转屏幕、深色/浅色切换等）保存连接与音频会话。
 *
 * <p>Activity 重建时只重新绑定 Listener，不重建 WebSocket/AudioEngine，
 * 避免旋转一次就断线、结束通话。真正的资源释放发生在 ViewModel 被清理时
 * （Activity finish / 进程退出）。</p>
 */
public class SessionViewModel extends ViewModel {

    private WebSocketManager webSocketManager;
    private AudioEngine audioEngine;
    /** 本次会话是否已经自动发起过连接（避免旋转屏幕后重复自动连接）。 */
    private boolean autoConnectAttempted;
    /**
     * 是否处于一轮语音对话中（已发送 listen.start 且尚未 listen.stop）。
     * 注意：自动模式下麦克风会一直保持打开，所以不能用 isRecording() 判断是否在通话。
     */
    private boolean callActive;

    public boolean isCallActive() {
        return callActive;
    }

    public void setCallActive(boolean active) {
        callActive = active;
    }

    public boolean isAutoConnectAttempted() {
        return autoConnectAttempted;
    }

    public void markAutoConnectAttempted() {
        autoConnectAttempted = true;
    }


    /** 只在首次创建时初始化；Activity 重建时直接复用已有实例。 */
    public synchronized void init(Context context, String deviceId, String clientId) {
        if (webSocketManager != null) return;

        Context appContext = context.getApplicationContext();
        webSocketManager = new WebSocketManager(appContext, deviceId, clientId);
        audioEngine = new AudioEngine(appContext, null);
        audioEngine.init();
    }

    public WebSocketManager getWebSocketManager() {
        return webSocketManager;
    }

    public AudioEngine getAudioEngine() {
        return audioEngine;
    }

    @Override
    protected void onCleared() {
        OtaService.stopPolling();
        if (webSocketManager != null) {
            webSocketManager.disconnect();
        }
        if (audioEngine != null) {
            audioEngine.release();
        }
    }
}
