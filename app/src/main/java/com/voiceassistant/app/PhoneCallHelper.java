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

/**
 * 来电相关能力：号码 -> 联系人名字、接听、挂断。
 *
 * 接听用 TelephonyManager/TelecomManager 的公开 API；挂断没有公开 API，
 * 这里按“TelecomManager.endCall -> ITelephony.endCall -> 无障碍点击挂断按钮”
 * 三种方式依次尝试，并把每一步的结果交给调用方展示。
 */
final class PhoneCallHelper {

    private static final String TAG = "PhoneCallHelper";

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
        if (number == null || number.trim().isEmpty()) return null;
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return null;

        // 同一个号码可能存成 +8613800138000 / 13800138000，逐个候选值试一遍
        java.util.List<String> candidates = candidates(number);
        for (String candidate : candidates) {
            String name = queryName(context, candidate);
            if (name != null) return name;
        }
        // PhoneLookup 没命中时，再按号码后 8 位模糊找一次，
        // 覆盖通讯录里存成“138 0013 8000”“+86 138…”这类格式的号码
        String bySuffix = queryNameBySuffix(context, candidates);
        if (bySuffix != null) return bySuffix;

        // 通话记录里系统自己缓存过姓名，顺带抄一份
        String cached = queryCallLogName(context, candidates);
        if (cached != null) return cached;

        // 最后用本地索引（Java 侧归一化后比对），兼容任何分隔符、全角数字
        return ContactsIndex.lookup(context, number);
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

    /** 接听正在响铃的电话。系统接口 -> 旧接口 -> 通知按钮 -> 无障碍点击，逐级兜底。 */
    static boolean answerCall(Context context, Result result) {
        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            result.onResult(false, "缺少“接听电话”权限，请在应用里授予");
            return false;
        }
        String firstError = null;
        try {
            TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom != null) {
                telecom.acceptRingingCall();
                result.onResult(true, "已发送接听指令");
                return true;
            }
        } catch (SecurityException e) {
            firstError = e.getMessage();
            Log.w(TAG, "acceptRingingCall denied: " + firstError);
        } catch (Exception e) {
            firstError = e.getMessage();
            Log.w(TAG, "acceptRingingCall failed", e);
        }

        // 部分定制系统只暴露旧的 ITelephony 接口
        if (invokeITelephony(context, "answerRingingCall")) {
            result.onResult(true, "已通过兼容方式接听");
            return true;
        }
        // 来电通知上的“接听”按钮
        if (CallNotificationListener.acceptCall()) {
            result.onResult(true, "已通过通知栏按钮接听");
            return true;
        }
        // 最后让无障碍服务去点系统通话界面上的接听按钮
        if (CallAccessibilityService.clickAnswer()) {
            result.onResult(true, "已通过无障碍服务接听");
            return true;
        }
        result.onResult(false, "接听失败" + (firstError == null ? "" : "（" + firstError + "）")
                + "，请开启“通知使用权”或无障碍辅助功能");
        return false;
    }

    /** 挂断当前通话或拒接来电。 */
    static boolean endCall(Context context, Result result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
                if (telecom != null && telecom.endCall()) {
                    result.onResult(true, "已挂断");
                    return true;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "endCall not permitted: " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "endCall failed", e);
            }
        }

        if (invokeITelephony(context, "endCall")) {
            result.onResult(true, "已通过兼容方式挂断");
            return true;
        }

        if (CallNotificationListener.declineCall()) {
            result.onResult(true, "已通过通知栏按钮挂断");
            return true;
        }

        // 最后兜底：让无障碍服务去点系统通话界面上的挂断按钮
        if (CallAccessibilityService.clickHangUp()) {
            result.onResult(true, "已通过无障碍服务挂断");
            return true;
        }

        result.onResult(false, "挂断失败：系统不允许第三方应用挂断，"
                + "请开启无障碍辅助功能，或使用通知栏上的挂断按钮");
        return false;
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
