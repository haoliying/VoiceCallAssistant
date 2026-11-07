package com.voiceassistant.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 可选的无障碍辅助服务。系统不提供“挂断电话”的公开接口，
 * 这里退一步：在通话界面上找到挂断按钮并点击。
 * 只有用户手动在系统设置里开启后才会工作。
 */
public class CallAccessibilityService extends AccessibilityService {

    private static final String TAG = "CallA11y";

    private static final String[] HANG_UP_LABELS = {
            "挂断", "结束通话", "挂断电话", "结束", "end call", "hang up", "disconnect"
    };

    private static volatile CallAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    /** 判断用户是否在系统设置里启用了本服务。 */
    public static boolean isEnabledInSettings(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName expected = new ComponentName(context, CallAccessibilityService.class);
        for (String item : enabled.split(":")) {
            ComponentName parsed = ComponentName.unflattenFromString(item);
            if (parsed != null && parsed.equals(expected)) return true;
        }
        return false;
    }

    /** 请求点击通话界面上的挂断按钮。 */
    static boolean clickHangUp() {
        CallAccessibilityService service = instance;
        if (service == null) return false;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null && service.clickInTree(root)) {
                Log.i(TAG, "hang up clicked in active window");
                return true;
            }
            List<android.view.accessibility.AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo windowRoot = window.getRoot();
                    if (windowRoot != null && service.clickInTree(windowRoot)) {
                        Log.i(TAG, "hang up clicked in window " + window.getId());
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "clickHangUp failed", e);
        }
        return false;
    }

    private boolean clickInTree(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 400) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            if (matchesHangUp(node) && click(node)) return true;

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return false;
    }

    private boolean matchesHangUp(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        for (String label : HANG_UP_LABELS) {
            if (contains(text, label) || contains(description, label)) return true;
        }
        return false;
    }

    private boolean contains(CharSequence source, String label) {
        if (source == null) return false;
        String value = source.toString().toLowerCase();
        return value.contains(label);
    }

    /** 按钮本身不一定可点，往上找最近的可点击父节点。 */
    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只在收到请求时按需点击，不处理事件流
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
