package com.lhht.xiaozhi.utils;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 解决软键盘遮挡页面底部内容（聊天记录、输入框、发送按钮）的问题。
 *
 * <p><b>为什么 adjustResize 不够：</b>从 targetSdk 35 开始，Android 15 对应用强制
 * edge-to-edge，键盘弹出时系统不再缩小应用窗口，{@code android:windowSoftInputMode=
 * "adjustResize"} 不再生效，键盘只是"盖"在页面上；系统仅通过
 * {@link WindowInsetsCompat.Type#ime()} 向上报键盘高度。应用若不消费该 inset，
 * 页面底部就会永久被键盘遮住。</p>
 *
 * <p><b>做法：</b>在 {@code android.R.id.content} 上按键盘高度增加底部内边距，
 * 效果等价于旧版本的窗口 resize——内容区域整体收缩到键盘上方，
 * 同时把正在编辑的输入框滚动到可视区域内。</p>
 *
 * <p><b>兼容：</b>旧系统（API 21~34）上 adjustResize 正常，ime inset 通常为 0，
 * 此时不改变布局；若遇到忽略 adjustResize 的定制 ROM，则用可见区域高度差兜底。</p>
 */
public final class ImeInsetsHelper {

    private ImeInsetsHelper() {
    }

    /**
     * @param activity     目标 Activity
     * @param scrollTarget 键盘弹出时需要滚动到可视区域的 View（通常是输入框），
     *                     传 null 时使用当前焦点 View
     */
    public static void apply(Activity activity, @Nullable View scrollTarget) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        new Impl(activity, content, scrollTarget).register();
    }

    private static final class Impl {
        private final Activity activity;
        private final View content;
        private final View scrollTarget;
        private final int initialPaddingBottom;
        /** 只要系统上报过一次非零 IME inset，就不再使用可见区域兜底，避免重复加高 */
        private boolean imeInsetsSeen = false;
        private int lastKeyboardHeight = -1;

        /** 键盘高度稳定前 inset 会连续变化，合并成一次滚动，避免反复抖动 */
        private final Runnable scrollRunnable = this::doScrollTargetIntoView;

        Impl(Activity activity, View content, @Nullable View scrollTarget) {
            this.activity = activity;
            this.content = content;
            this.scrollTarget = scrollTarget;
            this.initialPaddingBottom = content.getPaddingBottom();
        }

        void register() {
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                if (ime.bottom > 0) {
                    imeInsetsSeen = true;
                }
                applyKeyboardHeight(ime.bottom);
                // 不消费 inset：状态栏/导航栏仍交给各布局里的 fitsSystemWindows 处理
                return insets;
            });

            // 兜底：部分定制 ROM（或旧系统）ime() 始终返回 0，且 adjustResize 被忽略，
            // 只能通过"窗口可见区域底部被抬高了多少"来判断键盘高度。
            content.getViewTreeObserver().addOnGlobalLayoutListener(this::detectByVisibleFrame);
        }

        private void detectByVisibleFrame() {
            if (imeInsetsSeen) return;
            Rect visible = new Rect();
            content.getWindowVisibleDisplayFrame(visible);
            int windowHeight = content.getRootView().getHeight();
            int covered = windowHeight - visible.bottom;
            // 小于 15% 屏高视为状态栏/导航栏等误差，不当作键盘
            int keyboardHeight = covered > windowHeight * 0.15f ? covered : 0;
            applyKeyboardHeight(keyboardHeight);
        }

        private void applyKeyboardHeight(int keyboardHeight) {
            int clamped = Math.max(keyboardHeight, 0);
            if (clamped == lastKeyboardHeight) return;
            lastKeyboardHeight = clamped;

            int paddingBottom = initialPaddingBottom + clamped;
            if (content.getPaddingBottom() != paddingBottom) {
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), paddingBottom);
                LogUtils.getInstance().d(activity, "ImeInsets",
                        "键盘高度 " + clamped + "px，内容区底部内边距 → " + paddingBottom + "px");
            }
            if (clamped > 0) {
                scrollTargetIntoView();
            }
        }

        /** 键盘遮住输入框时，把它滚动到键盘上方的可视区域内。 */
        private void scrollTargetIntoView() {
            // 等内边距生效、窗口完成布局后再滚动
            content.removeCallbacks(scrollRunnable);
            content.postDelayed(scrollRunnable, 50);
        }

        private void doScrollTargetIntoView() {
            View target = scrollTarget;
            if (target == null || !target.isShown()) {
                target = activity.getCurrentFocus();
            }
            if (target == null) return;
            if (target.getHeight() <= 0 || target.getWidth() <= 0) return;
            Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight() + dp(16));
            target.requestRectangleOnScreen(rect, true);
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }
    }
}
