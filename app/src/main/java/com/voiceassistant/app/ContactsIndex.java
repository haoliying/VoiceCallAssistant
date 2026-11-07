package com.voiceassistant.app;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.ContactsContract.CommonDataKinds;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 通讯录索引。
 *
 * 系统自带的 PhoneLookup 要求号码格式比较严格，遇到 “+86 185 6018 1145”、
 * “185-6018-1145”、全角数字、带括号分机号这类存法就查不到人。
 * 这里在 Java 侧把号码里的非数字字符全部去掉再比对，兼容各种存法，
 * 并把“后 8 位”做成索引，避免每次来电都全表扫描。
 */
final class ContactsIndex {

    private static final String TAG = "ContactsIndex";
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    static final class Snapshot {
        final Map<String, String> byFull = new HashMap<>();
        final Map<String, String> bySuffix = new HashMap<>();
        int numbers;
        int contacts;
        long loadedAt;
        String error;
    }

    private static volatile Snapshot snapshot;

    private ContactsIndex() {}

    /** 服务启动时预热一次，来电时就不用现场读通讯录。 */
    static void warmUp(Context context) {
        try {
            load(context);
        } catch (Exception e) {
            Log.w(TAG, "warmUp failed", e);
        }
    }

    static String lookup(Context context, String number) {
        String digits = normalize(number);
        if (digits.length() < 5) return null;

        Snapshot data = ensureLoaded(context);
        if (data == null) return null;

        String[] variants = {
                digits,
                tail(digits, 13),
                tail(digits, 12),
                tail(digits, 11),
                tail(digits, 10),
                tail(digits, 9),
                tail(digits, 8)
        };
        for (String variant : variants) {
            if (variant == null) continue;
            String name = data.byFull.get(variant);
            if (name != null) return name;
        }

        // 通讯录里可能是 “+86 185 6018 1145”，用后 8 位对一次
        for (int length = 8; length >= 7; length--) {
            String key = tail(digits, length);
            if (key == null) continue;
            String name = data.bySuffix.get(key);
            if (name != null) return name;
        }

        // 通讯录里的号码比来电号码短（例如只存了 8 位固话）
        for (Map.Entry<String, String> entry : data.byFull.entrySet()) {
            String stored = entry.getKey();
            if (stored.length() >= 7 && digits.endsWith(stored)) return entry.getValue();
        }
        return null;
    }

    /** 查不到人时给日志提供线索：通讯录里有没有尾号相同的号码。 */
    static String diagnose(Context context, String number) {
        Snapshot data = ensureLoaded(context);
        if (data == null) return "通讯录索引不可用";
        String digits = normalize(number);
        String tail4 = tail(digits, 4);
        if (tail4 == null) return "索引 " + data.numbers + " 个号码";
        List<String> hits = new ArrayList<>();
        for (String stored : data.byFull.keySet()) {
            if (stored.endsWith(tail4)) {
                hits.add(PhoneCallHelper.maskNumber(stored));
                if (hits.size() >= 3) break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("索引 ").append(data.numbers).append(" 个号码/")
                .append(data.contacts).append(" 位联系人");
        if (data.error != null) sb.append("，读取异常: ").append(data.error);
        if (!hits.isEmpty()) sb.append("，尾号 ").append(tail4).append(" 匹配到 ").append(hits);
        return sb.toString();
    }

    /** 只读当前状态，不在主线程触发全量加载。 */
    static String describe(Context context) {
        Snapshot data = snapshot;
        if (data == null) return "通讯录索引: 正在加载";
        String text = "通讯录索引: " + data.numbers + " 个号码/" + data.contacts + " 位联系人";
        if (data.error != null) text += "（异常: " + data.error + "）";
        return text;
    }

    private static Snapshot ensureLoaded(Context context) {
        Snapshot current = snapshot;
        long now = SystemClock.elapsedRealtime();
        if (current != null && now - current.loadedAt < CACHE_TTL_MS) return current;
        return load(context);
    }

    private static synchronized Snapshot load(Context context) {
        Snapshot fresh = new Snapshot();
        fresh.loadedAt = SystemClock.elapsedRealtime();
        if (!PhoneCallHelper.hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            fresh.error = "未授权读取联系人";
            snapshot = fresh;
            return fresh;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            CommonDataKinds.Phone.DISPLAY_NAME,
                            CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (name == null || name.trim().isEmpty()) continue;
                String digits = normalize(cursor.getString(1));
                if (digits.length() < 5) continue;
                fresh.numbers++;
                if (!fresh.byFull.containsKey(digits)) {
                    fresh.byFull.put(digits, name.trim());
                }
                if (digits.length() >= 8) {
                    String key = digits.substring(digits.length() - 8);
                    if (!fresh.bySuffix.containsKey(key)) {
                        fresh.bySuffix.put(key, name.trim());
                    }
                }
            }
        } catch (Exception e) {
            fresh.error = e.getClass().getSimpleName();
            Log.w(TAG, "load contacts failed", e);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) { }
            }
        }
        fresh.contacts = new HashSet<>(fresh.byFull.values()).size();
        snapshot = fresh;
        Log.i(TAG, "contacts index loaded: " + fresh.numbers + " numbers, " + fresh.contacts + " names");
        return fresh;
    }

    /** 只保留数字，全角数字也一并转换。 */
    static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            int digit = Character.digit(raw.charAt(i), 10);
            if (digit >= 0) sb.append((char) ('0' + digit));
        }
        return sb.toString();
    }

    private static String tail(String value, int length) {
        if (value == null || value.length() < length) return null;
        return value.substring(value.length() - length);
    }
}
