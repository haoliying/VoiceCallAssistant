package com.voiceassistant.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 语音指令配置。界面上可以修改这三个口令，服务端每次识别都现读，
 * 改完不需要重启服务。
 */
final class AppSettings {

    private static final String PREFS = "voice_assistant_settings";

    static final String DEFAULT_TIME_QUERY = "几点了";
    static final String DEFAULT_ANSWER = "接电话";
    static final String DEFAULT_HANGUP = "挂断电话";

    private static final String KEY_TIME_QUERY = "cmd_time_query";
    private static final String KEY_ANSWER = "cmd_answer";
    private static final String KEY_HANGUP = "cmd_hangup";
    private static final String KEY_ANNOUNCE_CALLER = "announce_caller";
    private static final String KEY_ASR_MODEL = "asr_model";
    private static final String KEY_SPEAKER_OUTPUT = "speaker_output";

    /** 内置的 66MB 小模型，够用、占内存少。 */
    static final String ASR_MODEL_STANDARD = "standard";
    /** 需要另外下载 1.3GB 的高清模型，嘈杂环境、口音重时明显更准。 */
    static final String ASR_MODEL_HD = "hd";

    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getTimeQuery(Context context) {
        return read(context, KEY_TIME_QUERY, DEFAULT_TIME_QUERY);
    }

    static String getAnswer(Context context) {
        return read(context, KEY_ANSWER, DEFAULT_ANSWER);
    }

    static String getHangup(Context context) {
        return read(context, KEY_HANGUP, DEFAULT_HANGUP);
    }

    static boolean isAnnounceCaller(Context context) {
        return prefs(context).getBoolean(KEY_ANNOUNCE_CALLER, true);
    }

    static String getAsrModel(Context context) {
        String value = prefs(context).getString(KEY_ASR_MODEL, ASR_MODEL_STANDARD);
        return ASR_MODEL_HD.equals(value) ? ASR_MODEL_HD : ASR_MODEL_STANDARD;
    }

    static void setAsrModel(Context context, String model) {
        prefs(context).edit()
                .putString(KEY_ASR_MODEL,
                        ASR_MODEL_HD.equals(model) ? ASR_MODEL_HD : ASR_MODEL_STANDARD)
                .apply();
    }

    /** true=强制用手机喇叭外放；false=跟随系统当前输出设备（蓝牙耳机、车机等）。 */
    static boolean isSpeakerOutput(Context context) {
        return prefs(context).getBoolean(KEY_SPEAKER_OUTPUT, false);
    }

    static void setSpeakerOutput(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SPEAKER_OUTPUT, enabled).apply();
    }

    static void save(Context context, String timeQuery, String answer, String hangup,
                     boolean announceCaller) {
        prefs(context).edit()
                .putString(KEY_TIME_QUERY, clean(timeQuery, DEFAULT_TIME_QUERY))
                .putString(KEY_ANSWER, clean(answer, DEFAULT_ANSWER))
                .putString(KEY_HANGUP, clean(hangup, DEFAULT_HANGUP))
                .putBoolean(KEY_ANNOUNCE_CALLER, announceCaller)
                .apply();
    }

    private static String read(Context context, String key, String fallback) {
        String value = prefs(context).getString(key, fallback);
        return clean(value, fallback);
    }

    /** 空口令会让匹配逻辑命中一切，这里统一兜底成默认值。 */
    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
