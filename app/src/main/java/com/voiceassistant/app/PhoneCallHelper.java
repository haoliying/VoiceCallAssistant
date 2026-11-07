package com.voiceassistant.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 来电相关能力：号码 -> 联系人名字、接听、挂断。
 *
 * 接听用 TelephonyManager/TelecomManager 的公开 API；挂断没有公开 API，
 * 这里按“TelecomManager.endCall -> ITelephony.endCall -> 无障碍点击挂断按钮”
 * 三种方式依次尝试，并把每一步的结果交给调用方展示。
 */
final class PhoneCallHelper {

    private static final String TAG = "PhoneCallHelper";

    /** 上一次查询命中的来源，方便日志定位。 */
    static volatile String lastSource = "";

    interface Result {
        void onResult(boolean success, String message);
    }

    private PhoneCallHelper() {}

    static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canReadPhoneState(Context context) {
        return hasPermission(context, Manifest.permission.READ_PHONE_STATE);
    }

    /** 号码能查到联系人姓名就返回姓名，查不到返回 null（陌生号码）。 */
    static String lookupContactName(Context context, String number) {
        lastSource = "";
        if (number == null || number.trim().isEmpty()) return null;
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return null;

        // 同一个号码可能存成 +8613800138000 / 13800138000，逐个候选值试一遍
        java.util.List<String> candidates = candidates(number);
        for (String candidate : candidates) {
            String name = queryName(context, candidate);
            if (name != null) {
                lastSource = "系统 PhoneLookup";
                return name;
            }
        }
        // 本地索引：号码归一化后按国内/国际写法整号比对，不做位数截断
        String indexed = ContactsIndex.lookup(context, number);
        if (indexed != null) {
            lastSource = "本地通讯录索引";
            return indexed;
        }

        // 通话记录里系统自己缓存过姓名，顺带抄一份
        String cached = queryCallLogName(context, candidates);
        if (cached != null) {
            lastSource = "通话记录缓存";
            return cached;
        }

        // 最后的兜底：按号码后 8 位模糊找一次，只用于“通讯录只存了局号”的固话
        String bySuffix = queryNameBySuffix(context, candidates);
        if (bySuffix != null) lastSource = "号码后 8 位";
        return bySuffix;
    }

    /** 通话记录里系统解析出的联系人姓名（需要“通话记录”权限）。 */
    private static String queryCallLogName(Context context, java.util.List<String> candidates) {
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) return null;
        for (String candidate : candidates) {
            if (candidate.length() < 7) continue;
            String suffix = "%" + candidate.substring(candidate.length() - 7);
            android.database.Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        new String[]{android.provider.CallLog.Calls.CACHED_NAME},
                        android.provider.CallLog.Calls.NUMBER + " LIKE ?",
                        new String[]{suffix},
                        android.provider.CallLog.Calls.DATE + " DESC");
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()
                            && !ContactsIndex.normalize(name).equals(candidate)) {
                        return name.trim();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "queryCallLogName failed", e);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception ignored) { }
                }
            }
        }
        return null;
    }

    private static String queryNameBySuffix(Context context, java.util.List<String> candidates) {
        for (String candidate : candidates) {
            if (candidate.length() < 8) continue;
            String suffix = candidate.substring(candidate.length() - 8);
            android.database.Cursor cursor = null;
            try {
                String normalizedColumn = "REPLACE(REPLACE(REPLACE("
                        + CommonDataKinds.Phone.NUMBER + ",' ',''),'-',''),'+','')";
                cursor = context.getContentResolver().query(
                        CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{CommonDataKinds.Phone.DISPLAY_NAME},
                        normalizedColumn + " LIKE ?",
                        new String[]{"%" + suffix},
                        null);
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            } catch (Exception e) {
                Log.w(TAG, "queryNameBySuffix failed", e);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception ignored) { }
                }
            }
        }
        return null;
    }

    private static java.util.List<String> candidates(String number) {
        java.util.List<String> result = new java.util.ArrayList<>();
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return result;
        result.add(digits);

        if (digits.length() > 11) {
            result.add(digits.substring(digits.length() - 11));
        }
        if (digits.startsWith("86") && digits.length() == 13) {
            result.add(digits.substring(2));
        }
        return result;
    }

    private static String queryName(Context context, String number) {
        android.database.Cursor cursor = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            cursor = context.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "queryName failed", e);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /**
     * 接听正在响铃的电话。
     *
     * 很多 ROM 上 TelecomManager.acceptRingingCall() 不抛异常但也不生效，
     * 所以每一步都要用 getCallState() 验证，失败再换下一种方式。
     */
    static boolean answerCall(Context context, Result result) {
        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            result.onResult(false, "缺少“接听电话”权限，请在应用里授予");
            return false;
        }
        List<String> tried = new ArrayList<>();

        // 1. 通知栏上的“接听”按钮：不需要通话权限，成功率最高
        if (CallNotificationListener.isEnabled(context) && CallNotificationListener.acceptCall()) {
            tried.add("通知栏按钮");
            if (waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 1500)) {
                result.onResult(true, "已通过通知栏的接听按钮接听");
                return true;
            }
            Log.w(TAG, "notification answer action did not take effect");
        }

        // 2. 系统公开接口
        tried.add("系统接口");
        try {
            TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom != null) telecom.acceptRingingCall();
        } catch (Exception e) {
            Log.w(TAG, "acceptRingingCall failed", e);
        }
        if (waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 1500)) {
            result.onResult(true, "已通过系统接口接听");
            return true;
        }

        // 3. 旧版 ITelephony 接口
        if (invokeITelephony(context, "answerRingingCall")) {
            tried.add("兼容接口");
            if (waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 1000)) {
                result.onResult(true, "已通过兼容方式接听");
                return true;
            }
        }

        // 4. 无障碍服务点击系统的接听按钮
        if (CallAccessibilityService.clickAnswer()) {
            tried.add("无障碍点击");
            if (waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 1500)) {
                result.onResult(true, "已通过无障碍服务接听");
                return true;
            }
        }

        result.onResult(false, "接听没有被系统接受（已尝试: " + join(tried)
                + "）。请在应用里开启“通知使用权”或无障碍服务，或手动接听");
        return false;
    }

    /** 挂断当前通话或拒接来电。 */
    static boolean endCall(Context context, Result result) {
        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            result.onResult(false, "缺少“接听电话”权限，请在应用里授予");
            return false;
        }
        List<String> tried = new ArrayList<>();

        if (CallNotificationListener.isEnabled(context) && CallNotificationListener.declineCall()) {
            tried.add("通知栏按钮");
            if (waitForState(context, TelephonyManager.CALL_STATE_IDLE, 1500)) {
                result.onResult(true, "已通过通知栏按钮挂断");
                return true;
            }
        }

        tried.add("系统接口");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
                if (telecom != null) telecom.endCall();
            } catch (Exception e) {
                Log.w(TAG, "endCall failed", e);
            }
            if (waitForState(context, TelephonyManager.CALL_STATE_IDLE, 1200)) {
                result.onResult(true, "已挂断");
                return true;
            }
        }

        if (invokeITelephony(context, "endCall")) {
            tried.add("兼容接口");
            if (waitForState(context, TelephonyManager.CALL_STATE_IDLE, 1000)) {
                result.onResult(true, "已通过兼容方式挂断");
                return true;
            }
        }

        // 最后兜底：让无障碍服务去点系统通话界面上的挂断按钮
        if (CallAccessibilityService.clickHangUp()) {
            tried.add("无障碍点击");
            if (waitForState(context, TelephonyManager.CALL_STATE_IDLE, 1500)) {
                result.onResult(true, "已通过无障碍服务挂断");
                return true;
            }
        }

        result.onResult(false, "挂断没有被系统接受（已尝试: " + join(tried)
                + "）。请开启“通知使用权”或无障碍辅助功能");
        return false;
    }

    /** 轮询通话状态，直到变成期望值或超时。 */
    private static boolean waitForState(Context context, int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int state = currentCallState(context);
            if (state == expected) return true;
            if (state == TelephonyManager.CALL_STATE_IDLE
                    && expected == TelephonyManager.CALL_STATE_OFFHOOK) {
                return false;
            }
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return currentCallState(context) == expected;
    }

    private static int currentCallState(Context context) {
        try {
            TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony != null) return telephony.getCallState();
        } catch (Exception e) {
            Log.w(TAG, "getCallState failed", e);
        }
        return TelephonyManager.CALL_STATE_IDLE;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append("、");
            sb.append(item);
        }
        return sb.toString();
    }

    /** 通过反射调用 ITelephony，仅作兼容手段，失败返回 false。 */
    private static boolean invokeITelephony(Context context, String methodName) {
        try {
            TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony == null) return false;
            Method getITelephony = telephony.getClass().getDeclaredMethod("getITelephony");
            getITelephony.setAccessible(true);
            Object itelephony = getITelephony.invoke(telephony);
            if (itelephony == null) return false;
            Method target = itelephony.getClass().getDeclaredMethod(methodName);
            target.setAccessible(true);
            Object returned = target.invoke(itelephony);
            return !(returned instanceof Boolean) || (Boolean) returned;
        } catch (Exception e) {
            Log.w(TAG, "ITelephony." + methodName + " unavailable: " + e.getMessage());
            return false;
        }
    }

    /** 号码脱敏，日志里不要出现完整号码。 */
    static String maskNumber(String number) {
        if (number == null) return "";
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) return digits;
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }
}
