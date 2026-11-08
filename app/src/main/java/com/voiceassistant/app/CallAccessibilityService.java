package com.voiceassistant.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 无障碍辅助服务：系统不提供第三方“接听/挂断”的公开接口，
 * 这里在通话界面上找到按钮并点击；点不动时用模拟手势（点按/上滑），
 * 因为华为等 ROM 的来电界面是滑动接听的。
 * 只有用户手动在系统设置里开启后才会工作。
 */
public class CallAccessibilityService extends AccessibilityService {

    private static final String TAG = "CallA11y";

    private static final String[] HANG_UP_LABELS = {
            "挂断", "结束通话", "挂断电话", "结束", "end call", "hang up", "disconnect"
    };

    private static final String[] ANSWER_LABELS = {
            "接听", "接听电话", "接听来电", "接电话", "应答", "滑动接听", "上滑接听",
            "answer", "accept"
    };

    /** 上/下滑之间至少隔这么久，避免连续手势把界面越划越乱。 */
    private static final long SWIPE_COOLDOWN_MS = 2500L;

    private static volatile CallAccessibilityService instance;
    private static volatile long lastSwipeAt = 0L;

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

    /** 请求点击/滑动挂断。 */
    static boolean clickHangUp() {
        if (clickLabels(HANG_UP_LABELS, "挂断")) return true;
        return swipeGesture(false);
    }

    /** 请求点击/上滑接听。 */
    static boolean clickAnswer() {
        if (clickLabels(ANSWER_LABELS, "接听")) return true;
        return swipeGesture(true);
    }

    private static boolean clickLabels(String[] labels, String what) {
        CallAccessibilityService service = instance;
        if (service == null) {
            LogStore.append("[无障碍] 服务未运行，无法" + what);
            return false;
        }
        try {
            List<AccessibilityNodeInfo> nodes = service.find(labels);
            if (nodes.isEmpty()) {
                LogStore.append("[无障碍] 界面上没找到“" + what + "”按钮；窗口: "
                        + service.describeWindows());
                return false;
            }
            for (AccessibilityNodeInfo node : nodes) {
                if (service.click(node)) {
                    LogStore.append("[无障碍] 已点击“" + what + "”按钮 " + service.boundsOf(node));
                    return true;
                }
            }
            // 按钮本身不可点击（滑动接听），按它的位置点一下
            for (AccessibilityNodeInfo node : nodes) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0
                        && service.tap(bounds.centerX(), bounds.centerY())) {
                    LogStore.append("[无障碍] 已点按“" + what + "”按钮位置 " + service.boundsOf(node));
                    return true;
                }
            }
            LogStore.append("[无障碍] 找到“" + what + "”按钮但点不动，改用滑动");
        } catch (Exception e) {
            Log.w(TAG, "click " + what + " failed", e);
        }
        return false;
    }

    /** 当前可见窗口的简要信息，用来排查“找不到按钮”时到底是什么界面。 */
    private String describeWindows() {
        StringBuilder sb = new StringBuilder();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null || windows.isEmpty()) return "无";
        int count = 0;
        for (AccessibilityWindowInfo window : windows) {
            if (count++ >= 4) break;
            if (sb.length() > 0) sb.append(" / ");
            AccessibilityNodeInfo root = window.getRoot();
            sb.append("type=").append(window.getType());
            if (root != null) {
                sb.append(" ").append(root.getPackageName()).append(":")
                        .append(root.getClassName());
                String texts = sampleTexts(root);
                if (!texts.isEmpty()) sb.append(" 文字[").append(texts).append("]");
            }
        }
        return sb.toString();
    }

    /** 抓几个节点上的文字/描述/资源名，华为的接听控件长什么样一看就知道。 */
    private String sampleTexts(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        int picked = 0;
        while (!queue.isEmpty() && guard++ < 200 && picked < 10) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            if (node.isVisibleToUser()) {
                String text = node.getText() == null ? "" : node.getText().toString().trim();
                String desc = node.getContentDescription() == null ? ""
                        : node.getContentDescription().toString().trim();
                String id = node.getViewIdResourceName();
                String item = !text.isEmpty() ? text : (!desc.isEmpty() ? desc : id);
                if (item != null && !item.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(item);
                    picked++;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return sb.toString();
    }

    /** 收集所有窗口里文字/描述/资源名匹配的节点。 */
    private List<AccessibilityNodeInfo> find(String[] labels) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) queue.add(active);
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) queue.add(root);
            }
        }
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 600) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            // 只认屏幕上真正看得到的节点，隐藏/离屏节点点了也没用
            if (node.isVisibleToUser() && matches(node, labels)) result.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return result;
    }

    /** 节点的位置，日志里用来看点击落点对不对。 */
    private String boundsOf(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return "(" + rect.centerX() + "," + rect.centerY() + " "
                + rect.width() + "x" + rect.height() + ")";
    }

    private boolean matches(AccessibilityNodeInfo node, String[] labels) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String viewId = node.getViewIdResourceName();
        for (String label : labels) {
            if (contains(text, label) || contains(description, label)) return true;
            if (viewId != null && viewId.toLowerCase(Locale.ROOT).contains(label)) return true;
        }
        return false;
    }

    private boolean contains(CharSequence source, String label) {
        if (source == null) return false;
        return source.toString().toLowerCase(Locale.ROOT).contains(label);
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

    /** 在指定位置点一下（模拟手指触摸）。 */
    private boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(path, 60);
    }

    /**
     * 接听界面是上滑接听、下滑拒接时，按屏幕中下方的滑动来操作。
     * 每 2.5 秒最多一次，避免连续滑动把界面弄得不可控。
     */
    private static boolean swipeGesture(boolean up) {
        CallAccessibilityService service = instance;
        if (service == null) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSwipeAt < SWIPE_COOLDOWN_MS) return false;
        lastSwipeAt = now;
        try {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            int x = metrics.widthPixels / 2;
            int fromY = (int) (metrics.heightPixels * (up ? 0.82f : 0.30f));
            int toY = (int) (metrics.heightPixels * (up ? 0.40f : 0.70f));
            Path path = new Path();
            path.moveTo(x, fromY);
            path.lineTo(x, toY);
            boolean sent = service.dispatch(path, 300);
            LogStore.append("[无障碍] 已发送" + (up ? "上滑接听" : "下滑挂断") + "手势");
            return sent;
        } catch (Exception e) {
            Log.w(TAG, "swipe failed", e);
            return false;
        }
    }

    private boolean dispatch(Path path, long durationMs) {
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
        LogStore.append("[无障碍] 服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只在收到请求时按需操作，不处理事件流
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
