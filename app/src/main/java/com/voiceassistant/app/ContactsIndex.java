package com.voiceassistant.app;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通讯录索引。
 *
 * 系统自带的 PhoneLookup 对号码格式要求很严，遇到 “+86 185 6018 1145”、
 * “185-6018-1145”、全角数字这类存法就查不到人。这里在 Java 侧把号码里的
 * 非数字字符去掉，再按“国内/国际 + 区号/手机号”的几种写法互相匹配，不做位数截断。
 */
final class ContactsIndex {

    private static final String TAG = "ContactsIndex";
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    static final class Snapshot {
        /** 通讯录里原始号码（只保留数字）-> 姓名 */
        final Map<String, String> byFull = new HashMap<>();
        /** 号码的各种写法（去 +86 / 去 0 等）-> 姓名 */
        final Map<String, String> byVariant = new HashMap<>();
        int numbers;
        int contacts;
        int phoneRows;
        int contactRows;
        int unnamed;
        long loadedAt;
        String error;

        String describeRows() {
            String text = "Phone 表 " + phoneRows + " 行, Contacts 表 " + contactRows + " 行";
            if (unnamed > 0) text += ", 无姓名 " + unnamed;
            if (error != null) text += ", 异常: " + error;
            return text;
        }
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

        // 1. 号码本体和各种带前缀的写法
        for (String key : variants(digits)) {
            String name = data.byVariant.get(key);
            if (name != null) return name;
        }

        // 2. 固话：通讯录里只存了局号，来电带区号
        for (Map.Entry<String, String> entry : data.byFull.entrySet()) {
            String stored = entry.getKey();
            if (stored.length() >= 7 && digits.length() > stored.length()
                    && digits.endsWith(stored)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 查不到人时给日志提供线索。 */
    static String diagnose(Context context, String number) {
        Snapshot data = ensureLoaded(context);
        if (data == null) return "通讯录索引不可用";
        String digits = normalize(number);
        StringBuilder sb = new StringBuilder();
        sb.append("索引 ").append(data.numbers).append(" 个号码/")
                .append(data.contacts).append(" 位联系人");
        String tail4 = tail(digits, 4);
        if (tail4 != null) {
            List<String> hits = new ArrayList<>();
            for (String stored : data.byFull.keySet()) {
                if (stored.endsWith(tail4)) {
                    hits.add(PhoneCallHelper.maskNumber(stored));
                    if (hits.size() >= 3) break;
                }
            }
            if (!hits.isEmpty()) {
                sb.append("，尾号 ").append(tail4).append(" 匹配到 ").append(hits);
            }
        }
        sb.append("（").append(data.describeRows()).append("）");
        return sb.toString();
    }

    /** 只读当前状态，不在主线程触发全量加载。 */
    static String describe(Context context) {
        Snapshot data = snapshot;
        if (data == null) return "通讯录索引: 正在加载";
        String text = "通讯录索引: " + data.numbers + " 个号码/" + data.contacts + " 位联系人";
        if (data.phoneRows == 0 || data.error != null) {
            text += "（" + data.describeRows() + "）";
        }
        if (data.phoneRows == 0) {
            text += appOpsHint(context);
        }
        return text;
    }

    /**
     * 有些系统（MIUI/HyperOS 的“空白通行证”）会显示权限已授予，
     * 但查询联系人时返回空列表。这里读一下 AppOps 状态，帮助定位。
     */
    static String appOpsHint(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return "";
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_READ_CONTACTS,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_READ_CONTACTS,
                        Process.myUid(), context.getPackageName());
            }
            if (mode == AppOpsManager.MODE_IGNORED) {
                return "，系统把联系人权限设成了“空白/忽略”，请到系统权限设置里改成允许";
            }
            if (mode == AppOpsManager.MODE_ERRORED) {
                return "，系统拒绝了联系人读取";
            }
        } catch (Exception e) {
            Log.w(TAG, "appOpsHint failed", e);
        }
        return "";
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
                            CommonDataKinds.Phone.NUMBER,
                            CommonDataKinds.Phone.CONTACT_ID
                    },
                    null, null, null);
            if (cursor != null) fresh.phoneRows = cursor.getCount();
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(0);
                String raw = cursor.getString(1);
                if (name == null || name.trim().isEmpty()) {
                    fresh.unnamed++;
                    continue;
                }
                String digits = normalize(raw);
                if (digits.length() < 5) continue;
                addNumber(fresh, digits, name.trim());
            }
        } catch (Exception e) {
            fresh.error = e.getClass().getSimpleName();
            Log.w(TAG, "load contacts failed", e);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) { }
            }
        }

        // Phone 表读不到内容时，改用 Contacts -> Phone 两步查询再试一次
        if (fresh.numbers == 0) {
            loadByContactIds(context, fresh);
        }

        countContacts(context, fresh);
        fresh.contacts = new HashSet<>(fresh.byFull.values()).size();
        snapshot = fresh;
        Log.i(TAG, "contacts index: " + fresh.numbers + " numbers, " + fresh.contacts
                + " names, " + fresh.describeRows());
        return fresh;
    }

    private static void addNumber(Snapshot snapshot, String digits, String name) {
        snapshot.numbers++;
        if (!snapshot.byFull.containsKey(digits)) {
            snapshot.byFull.put(digits, name);
        }
        for (String variant : variants(digits)) {
            if (!snapshot.byVariant.containsKey(variant)) {
                snapshot.byVariant.put(variant, name);
            }
        }
    }

    /** 有些 ROM 上直接查 Phone 表为空，按联系人逐个取号码更保险。 */
    private static void loadByContactIds(Context context, Snapshot snapshot) {
        Cursor contacts = null;
        try {
            contacts = context.getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME
                    },
                    ContactsContract.Contacts.HAS_PHONE_NUMBER + "=1",
                    null, null);
            while (contacts != null && contacts.moveToNext()) {
                String id = contacts.getString(0);
                String name = contacts.getString(1);
                if (name == null || name.trim().isEmpty() || id == null) continue;
                Cursor phones = null;
                try {
                    phones = context.getContentResolver().query(
                            CommonDataKinds.Phone.CONTENT_URI,
                            new String[]{CommonDataKinds.Phone.NUMBER},
                            CommonDataKinds.Phone.CONTACT_ID + "=?",
                            new String[]{id}, null);
                    while (phones != null && phones.moveToNext()) {
                        String digits = normalize(phones.getString(0));
                        if (digits.length() >= 5) addNumber(snapshot, digits, name.trim());
                    }
                } finally {
                    if (phones != null) {
                        try { phones.close(); } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loadByContactIds failed", e);
        } finally {
            if (contacts != null) {
                try { contacts.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static void countContacts(Context context, Snapshot snapshot) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID},
                    null, null, null);
            if (cursor != null) snapshot.contactRows = cursor.getCount();
        } catch (Exception e) {
            Log.w(TAG, "countContacts failed", e);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 同一个号码在国内可能存成 13800138000 / +8613800138000 / 008613800138000，
     * 固话还可能带长途冠码 0。这里把所有等价写法列出来，双向都能对上。
     */
    static List<String> variants(String digits) {
        Set<String> keys = new LinkedHashSet<>();
        if (digits == null || digits.isEmpty()) return new ArrayList<>(keys);
        keys.add(digits);

        String core = digits;
        if (core.startsWith("0086") && core.length() > 4) {
            core = core.substring(4);
            keys.add(core);
        }
        if (core.startsWith("86") && core.length() > 11) {
            core = core.substring(2);
            keys.add(core);
        }
        if (core.startsWith("0")) {
            keys.add(core.substring(1));
        }
        if (digits.startsWith("0") && digits.length() > 2) {
            keys.add(digits.substring(1));
        }
        return new ArrayList<>(keys);
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
