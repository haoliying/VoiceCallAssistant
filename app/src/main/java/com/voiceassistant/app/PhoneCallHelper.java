package com.voiceassistant.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.telecom.TelecomManager;
import android.view.KeyEvent;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 来电相关能力：接听、挂断。
 *
 * 来电姓名和号码都来自来电通知（{@link CallNotificationListener}），不再读通讯录。
 * 接听优先点通知栏的“接听”按钮，再用 TelephonyManager/TelecomManager 的公开 API；
 * 挂断没有公开 API，按“通知栏按钮 -> TelecomManager.endCall -> ITelephony.endCall
 * -> 无障碍点击挂断按钮”依次尝试，每一步都用通话状态验证。
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

    /**
     * 从通话记录里取系统缓存的姓名（华为会把联系人名字写进 CACHED_NAME）。
     * 不需要通讯录权限，也不需要来电通知，号码对得上就能拿到名字。
     */
    static String lookupNameFromCallLog(Context context, String number) {
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) return null;
        String digits = number == null ? "" : number.replaceAll("[^0-9]", "");
        if (digits.length() < 7) return null;
        String suffix = digits.substring(digits.length() - 8);
        android.database.Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    new String[]{android.provider.CallLog.Calls.CACHED_NAME},
                    android.provider.CallLog.Calls.NUMBER + " LIKE ?",
                    new String[]{"%" + suffix},
                    android.provider.CallLog.Calls.DATE + " DESC");
            int scanned = 0;
            while (cursor != null && cursor.moveToNext() && scanned++ < 30) {
                String name = cursor.getString(0);
                if (name == null) continue;
                name = name.trim();
                if (name.isEmpty()) continue;
                // 有些记录里 CACHED_NAME 直接是号码
                if (name.replaceAll("[^0-9]", "").equals(digits)) continue;
                return name;
            }
        } catch (Exception e) {
            Log.w(TAG, "lookupNameFromCallLog failed", e);
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
        List<String> tried = new ArrayList<>();

        // 1. 通知栏上的“接听”按钮：不需要通话权限，成功率最高
        if (CallNotificationListener.isEnabled(context)) {
            if (CallNotificationListener.acceptCall()) {
                tried.add("通知栏按钮");
                if (waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 3000)) {
                    result.onResult(true, "已通过通知栏的接听按钮接听");
                    return true;
                }
                Log.w(TAG, "notification answer action did not take effect");
            }
        } else {
            tried.add("通知使用权未开启");
        }

        // 2. 无障碍：华为等 ROM 只有这条路能真正点/划到接听（不需要通话权限）
        if (CallAccessibilityService.isRunning()) {
            tried.add("无障碍");
            for (int attempt = 0; attempt < 3; attempt++) {
                if (CallAccessibilityService.clickAnswer()
                        && waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 1500)) {
                    result.onResult(true, "已通过无障碍服务接听");
                    return true;
                }
                sleepQuietly(300);
            }
        } else if (CallAccessibilityService.isEnabledInSettings(context)) {
            tried.add("无障碍未连接");
        } else {
            tried.add("无障碍未开启");
        }

        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            result.onResult(false, "接听没有成功（已尝试: " + join(tried)
                    + "）。请开启“无障碍服务”并授予“接听电话”权限");
            return false;
        }

        // 3. 系统公开接口
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

        // 4. 旧版 ITelephony 接口
        if (invokeITelephony(context, "answerRingingCall")) {
            tried.add("兼容接口");
            if (waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 1200)) {
                result.onResult(true, "已通过兼容方式接听");
                return true;
            }
        }

        // 5. 耳机接听键：不少 ROM 会把它当成“接听”。正在放音乐时不发，
        //    否则可能被音乐播放器当成播放/暂停。
        if (sendHeadsetHook(context)) {
            tried.add("耳机键");
            if (waitForState(context, TelephonyManager.CALL_STATE_OFFHOOK, 1500)) {
                result.onResult(true, "已通过耳机键接听");
                return true;
            }
        }

        result.onResult(false, "没能接通（已尝试: " + join(tried)
                + "）。请开启“无障碍服务”，或在系统设置里允许本应用接听电话");
        return false;
    }

    /**
     * 发送耳机按键事件。响铃时很多 ROM 会把它当成“接听”。
     * 正在播放音乐时跳过，避免误触播放器的播放/暂停。
     */
    private static boolean sendHeadsetHook(Context context) {
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null || audioManager.isMusicActive()) return false;
            long now = android.os.SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0);
            KeyEvent up = new KeyEvent(now, now + 80, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0);
            audioManager.dispatchMediaKeyEvent(down);
            sleepQuietly(80);
            audioManager.dispatchMediaKeyEvent(up);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "headset hook failed", e);
            return false;
        }
    }

    /** 挂断当前通话或拒接来电。 */
    static boolean endCall(Context context, Result result) {
        List<String> tried = new ArrayList<>();

        if (CallNotificationListener.isEnabled(context) && CallNotificationListener.declineCall()) {
            tried.add("通知栏按钮");
            if (waitForState(context, TelephonyManager.CALL_STATE_IDLE, 1500)) {
                result.onResult(true, "已通过通知栏按钮挂断");
                return true;
            }
        }

        if (CallAccessibilityService.isRunning()) {
            tried.add("无障碍");
            for (int attempt = 0; attempt < 2; attempt++) {
                if (CallAccessibilityService.clickHangUp()
                        && waitForState(context, TelephonyManager.CALL_STATE_IDLE, 1200)) {
                    result.onResult(true, "已通过无障碍服务挂断");
                    return true;
                }
                sleepQuietly(300);
            }
        }

        if (!hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            result.onResult(false, "挂断没有成功（已尝试: " + join(tried)
                    + "）。请开启“无障碍服务”并授予“接听电话”权限");
            return false;
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

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
