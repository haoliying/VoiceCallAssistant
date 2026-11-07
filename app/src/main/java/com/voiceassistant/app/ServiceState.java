package com.voiceassistant.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 进程内/跨进程共享的服务运行状态。
 * 服务每 5 秒写一次心跳，界面重新进入时据此判断服务是否真的在运行，
 * 避免静态变量丢失或被系统回收后按钮与状态文字不一致。
 */
final class ServiceState {

    private static final String PREFS = "voice_clock_state";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_STATUS = "status";
    private static final String KEY_ERROR = "error";
    private static final String KEY_DETAIL = "detail";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final String KEY_TTS_FAILURES = "tts_failures";

    /** 超过这个时间没有心跳，认为服务已经不在运行。 */
    static final long STALE_TIMEOUT_MS = 60000L;

    private ServiceState() {}

    static void save(Context context, boolean running, String status, String error, String detail) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, running)
                .putString(KEY_STATUS, status)
                .putString(KEY_ERROR, error)
                .putString(KEY_DETAIL, detail)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    static Snapshot load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Snapshot s = new Snapshot();
        s.running = p.getBoolean(KEY_RUNNING, false);
        s.status = p.getString(KEY_STATUS, VoiceService.STATUS_STOPPED);
        s.error = p.getString(KEY_ERROR, "");
        s.detail = p.getString(KEY_DETAIL, "");
        s.updatedAt = p.getLong(KEY_UPDATED_AT, 0L);
        return s;
    }

    static int getTtsFailures(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TTS_FAILURES, 0);
    }

    static void setTtsFailures(Context context, int value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_TTS_FAILURES, value)
                .apply();
    }

    static final class Snapshot {        boolean running;
        String status;
        String error;
        String detail;
        long updatedAt;

        boolean isFresh() {
            return running && System.currentTimeMillis() - updatedAt < STALE_TIMEOUT_MS;
        }
    }
}
