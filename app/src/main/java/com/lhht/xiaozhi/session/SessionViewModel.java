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
