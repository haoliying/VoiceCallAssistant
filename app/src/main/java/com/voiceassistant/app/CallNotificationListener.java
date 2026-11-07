package com.voiceassistant.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 来电姓名和接听的主要来源：从系统通话通知里取来电人姓名/号码，
 * 并直接点击通知上的“接听”“挂断”按钮完成操作。
 * 需要用户在“通知使用权”里授权；未授权时只能念系统回调给出的号码，
 * 接听则退回系统接口。
 *
 * 每读到一条来电通知，都会把原文写进日志（[通知] ...），
 * 方便确认到底读到了什么，而不是只能听猜。
 */
public class CallNotificationListener extends NotificationListenerService {

    private static final String TAG = "CallNotifListener";
    private static final long VALID_MS = 20000L;

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d[\\d\\-\\s]{4,20}\\d)");
    /** 标题里常见的“张三(13800138000)”这种括号号码。 */
    private static final Pattern BRACKET_NUMBER =
            Pattern.compile("[（(\\[]?\\+?\\d[\\d\\-\\s]{4,}[)）\\]]?");

    private static volatile String pendingNumber;
    private static volatile String pendingName;
    private static volatile long capturedAt;
    /** 通知原文，界面和日志都会显示，用来排查“读到的到底是什么”。 */
    private static volatile String lastRaw = "";
    /** 来电通知上的操作按钮（接听/挂断），系统不允许第三方直接操作通话时用它兜底。 */
    private static volatile List<Notification.Action> callActions;
    private static volatile long callActionsAt;

    private static final String[] ANSWER_KEYWORDS = {"接听", "接电话", "应答", "answer", "accept"};
    private static final String[] DECLINE_KEYWORDS = {"挂断", "拒接", "结束通话", "decline", "reject", "hang up", "end call"};

    /** 通知里可能是运营商、状态提示而不是来电人姓名。 */
    private static final String[] NOISE_WORDS = {
            "移动", "联通", "电信", "运营商", "未知", "陌生", "号码", "主叫", "被叫",
            "呼叫", "响铃", "振铃", "接通", "未接", "拒接", "挂断", "标记", "骚扰",
            "广告", "推销", "视频", "语音", "通话", "来电提醒", "volte", "sim", "hd",
            "通知", "提醒", "已连接", "已开启", "已关闭", "充电", "网络",
            "高清", "中国移动", "中国联通", "中国电信", "未接来电"
    };

    /** 点击来电通知上的“接听”按钮。 */
    static boolean acceptCall() {
        return sendAction(ANSWER_KEYWORDS, "接听");
    }

    /** 点击来电通知上的“挂断/拒接”按钮。 */
    static boolean declineCall() {
        return sendAction(DECLINE_KEYWORDS, "挂断");
    }

    private static boolean sendAction(String[] keywords, String what) {
        List<Notification.Action> actions = callActions;
        if (actions == null || System.currentTimeMillis() - callActionsAt > VALID_MS) {
            LogStore.append("[通知] 没有可用的" + what + "按钮（通知里没有操作按钮或已过期）");
            return false;
        }
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null) continue;
            CharSequence title = action.title;
            if (title == null) continue;
            String value = title.toString().toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (value.contains(keyword)) {
                    try {
                        action.actionIntent.send();
                        Log.i(TAG, "sent call action: " + title);
                        LogStore.append("[通知] 已点击通知按钮: " + title);
                        return true;
                    } catch (Exception e) {
                        Log.w(TAG, "send call action failed", e);
                        LogStore.append("[通知] 点击通知按钮失败: " + e);
                    }
                }
            }
        }
        LogStore.append("[通知] 通知里没有“" + what + "”按钮，现有: " + actionTitles(actions));
        return false;
    }

    private static String actionTitles(List<Notification.Action> actions) {
        if (actions == null || actions.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (Notification.Action action : actions) {
            if (action == null || action.title == null) continue;
            if (sb.length() > 0) sb.append("/");
            sb.append(action.title);
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 最近 20 秒内从通话通知里抓到的号码，没有则返回 null。 */
    static String recentNumber() {
        if (System.currentTimeMillis() - capturedAt > VALID_MS) return null;
        return pendingNumber;
    }

    /** 最近 20 秒内从通话通知里抓到的名字（有些系统直接显示联系人名字）。 */
    static String recentName() {
        if (System.currentTimeMillis() - capturedAt > VALID_MS) return null;
        return pendingName;
    }

    /** 最近一条来电通知的原文，界面用来显示“读到了什么”。 */
    static String lastRaw() {
        return lastRaw;
    }

    /**
     * 响铃开始时调用：丢掉上一轮的残留，避免把几分钟前的通知文本当成本次来电。
     * 2.5 秒内的保留，因为来电通知可能比响铃回调早到一点。
     */
    static void clearIfStale() {
        if (System.currentTimeMillis() - capturedAt > 2500L) clear();
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
     *
     * 只认三类：系统标记为通话类、来自拨号/通话进程并且带号码或接听按钮、
     * 带“接听/挂断”这类按钮。这样运营商、SIM 卡之类的系统通知不会被误读成来电人。
     */
    private static boolean looksLikeCall(StatusBarNotification sbn, Notification notification,
                                         List<CharSequence> texts, boolean hasAnswerAction) {
        boolean hasNumber = false;
        for (CharSequence text : texts) {
            if (text != null && PHONE_PATTERN.matcher(text.toString()).find()) {
                hasNumber = true;
                break;
            }
        }
        if (Notification.CATEGORY_CALL.equals(notification.category)) return true;
        if (hasAnswerAction) return true;
        if (!hasNumber) return false;
        String pkg = sbn.getPackageName();
        if (pkg != null) {
            String lower = pkg.toLowerCase(Locale.ROOT);
            return lower.contains("dialer") || lower.contains("telecom")
                    || lower.contains("incallui") || lower.endsWith(".phone");
        }
        return false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) return;
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            List<CharSequence> texts = new ArrayList<>();
            if (extras != null) {
                addText(texts, extras.getCharSequence(Notification.EXTRA_TITLE));
                addText(texts, extras.getCharSequence(Notification.EXTRA_TEXT));
                addText(texts, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
                addText(texts, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
                addText(texts, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) {
                    for (CharSequence line : lines) addText(texts, line);
                }
            }
            if (notification.tickerText != null) addText(texts, notification.tickerText);

            boolean hasAnswerAction = hasAnswerAction(notification);
            if (!looksLikeCall(sbn, notification, texts, hasAnswerAction)) return;

            if (notification.actions != null && notification.actions.length > 0) {
                callActions = java.util.Arrays.asList(notification.actions);
                callActionsAt = System.currentTimeMillis();
            }

            lastRaw = describe(sbn, notification, texts);
            LogStore.append("[通知] " + lastRaw);

            // 同一条通知里可能既有姓名又有号码（标题是姓名、正文是号码），
            // 两个都记下来：播报时优先用姓名，没有姓名才念号码。
            String number = null;
            String name = null;
            for (int i = 0; i < texts.size(); i++) {
                String value = texts.get(i).toString();
                if (number == null) {
                    Matcher matcher = PHONE_PATTERN.matcher(value);
                    if (matcher.find()) number = matcher.group(1);
                }
                if (name == null) {
                    name = cleanName(value);
                }
            }

            if (name != null) {
                pendingName = name;
                capturedAt = System.currentTimeMillis();
                Log.i(TAG, "captured caller name from notification: " + name);
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

    private static boolean hasAnswerAction(Notification notification) {
        Notification.Action[] actions = notification.actions;
        if (actions == null) return false;
        for (Notification.Action action : actions) {
            if (action == null || action.title == null) continue;
            String title = action.title.toString().toLowerCase(Locale.ROOT);
            for (String keyword : ANSWER_KEYWORDS) {
                if (title.contains(keyword)) return true;
            }
            for (String keyword : DECLINE_KEYWORDS) {
                if (title.contains(keyword)) return true;
            }
        }
        return false;
    }

    /** 把一条通知的可读字段拼成一行，方便在日志里看清“到底读到了什么”。 */
    private static String describe(StatusBarNotification sbn, Notification notification,
                                   List<CharSequence> texts) {
        StringBuilder sb = new StringBuilder();
        sb.append("来自 ").append(sbn.getPackageName());
        sb.append(" [").append(notification.category == null ? "-" : notification.category).append("]");
        for (int i = 0; i < texts.size() && i < 6; i++) {
            sb.append(" | ").append(trim(texts.get(i).toString(), 40));
        }
        sb.append(" | 按钮=").append(actionTitles(
                notification.actions == null ? null
                        : java.util.Arrays.asList(notification.actions)));
        return trim(sb.toString(), 400);
    }

    private static String trim(String value, int max) {
        String single = value.replace("\n", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    /**
     * 从一条通知文本里提取姓名：
     * 去掉括号里的号码、去掉“来电/电话”等字样，剩下的必须是人名形态。
     */
    private static String cleanName(String raw) {
        if (raw == null) return null;
        // 先在原文上排掉“高清通话/中国移动/未接来电”这类整句提示，
        // 否则去掉“通话”两个字后可能剩下“高清”被当成人名
        if (isNoise(raw)) return null;
        String value = BRACKET_NUMBER.matcher(raw).replaceAll(" ")
                .replaceAll("(?i)(incoming call|voip|来电|电话|语音通话|视频通话)", "")
                .replaceAll("[\\[\\]（）()<>《》\\s·,，、|/\\\\-]", "")
                .trim();
        if (value.length() < 2 || value.length() > 16) return null;
        if (!isNameLike(value)) return null;
        return value;
    }

    /** 只允许中文、英文字母，避免把“VoLTE”“5G”之类的提示当成姓名。 */
    private static boolean isNameLike(String value) {
        int valid = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) return false;
            if (Character.isLetter(c)) valid++;
        }
        return valid == value.length() && valid >= 2;
    }

    /** 运营商名、“未知号码”之类的通知文案不是人名，不能拿去播报。 */
    private static boolean isNoise(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String word : NOISE_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    private void addText(List<CharSequence> list, CharSequence value) {
        if (value == null) return;
        String text = value.toString();
        if (!TextUtils.isEmpty(text.trim())) list.add(value);
    }
}
