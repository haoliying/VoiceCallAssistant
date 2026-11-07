package com.voiceassistant.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可选的兜底方案：Android 9 以后，没有通话记录权限时来电号码会是空的，
 * 这里从系统通话通知里把号码（或联系人名字）抓出来备用。
 * 需要用户在“通知使用权”里授权，未授权时整个类不工作，不影响其它功能。
 */
public class CallNotificationListener extends NotificationListenerService {

    private static final String TAG = "CallNotifListener";
    private static final long VALID_MS = 30000L;

    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?\\d[\\d\\-\\s]{4,20}\\d)");

    private static volatile String pendingNumber;
    private static volatile String pendingName;
    private static volatile long capturedAt;
    /** 来电通知上的操作按钮（接听/挂断），系统不允许第三方直接操作通话时用它兜底。 */
    private static volatile List<Notification.Action> callActions;
    private static volatile long callActionsAt;

    private static final String[] ANSWER_KEYWORDS = {"接听", "接电话", "应答", "answer", "accept"};
    private static final String[] DECLINE_KEYWORDS = {"挂断", "拒接", "结束通话", "decline", "reject", "hang up", "end call"};

    /** 点击来电通知上的“接听”按钮。 */
    static boolean acceptCall() {
        return sendAction(ANSWER_KEYWORDS);
    }

    /** 点击来电通知上的“挂断/拒接”按钮。 */
    static boolean declineCall() {
        return sendAction(DECLINE_KEYWORDS);
    }

    private static boolean sendAction(String[] keywords) {
        List<Notification.Action> actions = callActions;
        if (actions == null || System.currentTimeMillis() - callActionsAt > VALID_MS) return false;
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null) continue;
            CharSequence title = action.title;
            if (title == null) continue;
            String value = title.toString().toLowerCase();
            for (String keyword : keywords) {
                if (value.contains(keyword)) {
                    try {
                        action.actionIntent.send();
                        Log.i(TAG, "sent call action: " + title);
                        return true;
                    } catch (Exception e) {
                        Log.w(TAG, "send call action failed", e);
                    }
                }
            }
        }
        return false;
    }

    /** 最近 30 秒内从通话通知里抓到的号码，没有则返回 null。 */
    static String recentNumber() {
        if (System.currentTimeMillis() - capturedAt > VALID_MS) return null;
        return pendingNumber;
    }

    /** 最近 30 秒内从通话通知里抓到的名字（有些系统直接显示联系人名字）。 */
    static String recentName() {
        if (System.currentTimeMillis() - capturedAt > VALID_MS) return null;
        return pendingName;
    }

    /** 用户是否在“通知使用权”里启用了本应用。 */
    static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (TextUtils.isEmpty(enabled)) return false;
            ComponentName expected = new ComponentName(context, CallNotificationListener.class);
            for (String item : enabled.split(":")) {
                ComponentName parsed = ComponentName.unflattenFromString(item);
                if (parsed != null && parsed.equals(expected)) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "isEnabled failed", e);
        }
        return false;
    }

    static void clear() {
        pendingNumber = null;
        pendingName = null;
        capturedAt = 0L;
        callActions = null;
        callActionsAt = 0L;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            Notification notification = sbn.getNotification();
            if (notification.category == null
                    || !Notification.CATEGORY_CALL.equals(notification.category)) {
                return;
            }
            if (notification.actions != null && notification.actions.length > 0) {
                callActions = java.util.Arrays.asList(notification.actions);
                callActionsAt = System.currentTimeMillis();
            }
            Bundle extras = notification.extras;
            if (extras == null) return;

            List<CharSequence> texts = new ArrayList<>();
            addText(texts, extras.getCharSequence(Notification.EXTRA_TITLE));
            addText(texts, extras.getCharSequence(Notification.EXTRA_TEXT));
            addText(texts, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            addText(texts, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            addText(texts, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence line : lines) addText(texts, line);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && notification.tickerText != null) {
                addText(texts, notification.tickerText);
            }

            for (CharSequence text : texts) {
                String value = text.toString();
                Matcher matcher = PHONE_PATTERN.matcher(value);
                if (matcher.find()) {
                    pendingNumber = matcher.group(1);
                    capturedAt = System.currentTimeMillis();
                    Log.i(TAG, "captured caller number");
                    return;
                }
            }
            // 没有号码，但标题像个人名，也可以用来播报
            if (!texts.isEmpty()) {
                String title = texts.get(0).toString().trim();
                if (title.length() >= 2 && title.length() <= 20 && !containsDigit(title)) {
                    pendingName = title;
                    capturedAt = System.currentTimeMillis();
                    Log.i(TAG, "captured caller name from notification");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onNotificationPosted failed", e);
        }
    }

    private void addText(List<CharSequence> list, CharSequence value) {
        if (value == null) return;
        String text = value.toString();
        if (!TextUtils.isEmpty(text)) list.add(value);
    }

    private boolean containsDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) return true;
        }
        return false;
    }
}
