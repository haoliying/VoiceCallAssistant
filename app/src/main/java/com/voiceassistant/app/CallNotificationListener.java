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
 * 来电姓名和接听的主要来源：从系统通话通知里取来电人姓名/号码，
 * 并直接点击通知上的“接听”“挂断”按钮完成操作。
 * 需要用户在“通知使用权”里授权；未授权时只能念系统回调给出的号码，
 * 接听则退回系统接口。
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

    /** 通知里可能是运营商、状态提示而不是来电人姓名。 */
    private static final String[] NOISE_WORDS = {
            "移动", "联通", "电信", "运营商", "未知", "陌生", "号码", "主叫", "被叫",
            "呼叫", "响铃", "振铃", "接通", "未接", "拒接", "挂断", "标记", "骚扰",
            "广告", "推销", "视频", "语音", "通话", "来电提醒", "volte", "hd"
    };

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

    /**
     * 判断一条通知是不是来电通知。
     * 有些 ROM（华为等）不给通话通知打 CATEGORY_CALL，
     * 所以再看包名和按钮文案，避免漏掉。
     */
    private static boolean looksLikeCall(StatusBarNotification sbn, Notification notification) {
        if (Notification.CATEGORY_CALL.equals(notification.category)) return true;
        String pkg = sbn.getPackageName();
        if (pkg != null) {
            String lower = pkg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("dialer") || lower.contains("telecom")
                    || lower.contains("incallui") || lower.endsWith(".phone")) {
                return true;
            }
        }
        Notification.Action[] actions = notification.actions;
        if (actions != null) {
            for (Notification.Action action : actions) {
                if (action == null || action.title == null) continue;
                String title = action.title.toString().toLowerCase(java.util.Locale.ROOT);
                for (String keyword : ANSWER_KEYWORDS) {
                    if (title.contains(keyword)) return true;
                }
                for (String keyword : DECLINE_KEYWORDS) {
                    if (title.contains(keyword)) return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            Notification notification = sbn.getNotification();
            if (!looksLikeCall(sbn, notification)) return;
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

            // 同一条通知里可能既有姓名又有号码（例如标题是姓名、正文是号码），
            // 两个都记下来：播报时优先用姓名，没有姓名才念号码。
            String number = null;
            String name = null;
            for (CharSequence text : texts) {
                String value = text.toString();
                if (number == null) {
                    Matcher matcher = PHONE_PATTERN.matcher(value);
                    if (matcher.find()) number = matcher.group(1);
                }
                if (name == null) {
                    name = cleanName(value);
                }
                if (name != null && number != null) break;
            }

            if (name != null) {
                pendingName = name;
                capturedAt = System.currentTimeMillis();
                Log.i(TAG, "captured caller name from notification");
            }
            if (number != null) {
                pendingNumber = number;
                capturedAt = System.currentTimeMillis();
                Log.i(TAG, "captured caller number from notification");
            }
        } catch (Exception e) {
            Log.w(TAG, "onNotificationPosted failed", e);
        }
    }

    /** 去掉“来电/电话”等字样，剩下 2-20 个非数字字符才当成姓名。 */
    private static String cleanName(String raw) {
        if (raw == null) return null;
        String value = raw.replaceAll("(?i)(来电|电话|语音通话|视频通话|incoming call|voip)", "")
                .replaceAll("[\\[\\]（）()<>《》\\s·,，、|/\\\\-]", "")
                .trim();
        if (value.length() < 2 || value.length() > 20) return null;
        if (containsDigit(value)) return null;
        if (isNoise(value)) return null;
        return value;
    }

    /** 运营商名、“未知号码”之类的通知文案不是人名，不能拿去播报。 */
    private static boolean isNoise(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String word : NOISE_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    private void addText(List<CharSequence> list, CharSequence value) {
        if (value == null) return;
        String text = value.toString();
        if (!TextUtils.isEmpty(text)) list.add(value);
    }

    private static boolean containsDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) return true;
        }
        return false;
    }
}
