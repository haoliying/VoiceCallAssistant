package com.voiceassistant.app;

import android.content.Context;

/**
 * 把识别出来的一句话映射成动作。
 *
 * 判定顺序：先看用户配置的口令，再看内置同义词，最后做一次容错匹配
 * （Vosk 偶尔会多字/少字，例如把“挂断电话”识别成“挂断电话了”）。
 */
final class VoiceCommands {

    enum Action { NONE, TIME, ANSWER, HANGUP }

    /** 用户没改配置时也常用的说法，避免识别差一个字就不响应。 */
    private static final String[] TIME_SYNONYMS = {
            "几点了", "几点啦", "几点", "现在几点", "什么时间", "现在时间", "报时"};
    private static final String[] ANSWER_SYNONYMS = {
            "接电话", "接听电话", "接听", "接通", "接起来", "接一下电话", "接个电话",
            "接下电话", "接听一下", "帮我接"};
    private static final String[] HANGUP_SYNONYMS = {
            "挂断电话", "挂断", "挂掉电话", "挂掉", "挂电话", "挂了", "结束通话"};

    /**
     * 同音字归并表：小模型在嘈杂环境里最常犯的错是“同音不同字”，
     * 例如把“几点了”识别成“机点啦”、“接电话”识别成“接点话”。
     * 匹配前把两边都映射到同一个代表字，这类错误就不再影响判定。
     */
    private static final String[] HOMOPHONE_GROUPS = {
            "电点店垫典惦淀碘甸",        // diàn / diǎn
            "接结截街节借届杰洁阶劫",    // jiē / jié
            "话画化划桦",                // huà
            "几机鸡基积击迹吉急记挤己技季寄计", // jī / jǐ / jì
            "了啦乐勒垃喇辣腊",          // le / la
            "听厅停挺庭亭艇",            // tīng / tíng
            "挂卦褂寡刮瓜",              // guà / guā
            "断段锻缎短端煅",            // duàn / duǎn
            "时十实识石食拾湿失师诗事是使世市试", // shí / shì
            "间建件见简减检肩坚尖键健箭践荐渐",   // jiān / jiàn
            "现先线县显险限献陷鲜闲贤嫌",         // xiàn / xiān
            "在再载哉崽",                // zài
            "什深身申神审沈伸甚",        // shén / shēn
            "么末抹魔模摸莫沫",          // me / mo
            "通同铜童桶筒统",            // tōng / tóng
            "关观官管馆惯灌冠",          // guān / guàn
    };

    private static final java.util.Map<Character, Character> HOMOPHONE_MAP = buildHomophoneMap();

    private static java.util.Map<Character, Character> buildHomophoneMap() {
        java.util.Map<Character, Character> map = new java.util.HashMap<>();
        for (String group : HOMOPHONE_GROUPS) {
            char representative = group.charAt(0);
            for (int i = 0; i < group.length(); i++) {
                map.put(group.charAt(i), representative);
            }
        }
        return map;
    }

    /** 把同音字统一成代表字，用于“听起来一样”的匹配。 */
    static String canonical(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character mapped = HOMOPHONE_MAP.get(c);
            sb.append(mapped != null ? mapped.charValue() : c);
        }
        return sb.toString();
    }

    private VoiceCommands() {}

    static Action match(Context context, String rawText) {
        return match(rawText,
                AppSettings.getTimeQuery(context),
                AppSettings.getAnswer(context),
                AppSettings.getHangup(context));
    }

    /** 纯函数版本，方便脱离 Android 环境做匹配测试。 */
    static Action match(String rawText, String timeQueryCfg, String answerCfg, String hangupCfg) {
        String text = canonical(normalize(rawText));
        if (text.isEmpty()) return Action.NONE;

        String timeQuery = canonical(normalize(timeQueryCfg));
        String answer = canonical(normalize(answerCfg));
        String hangup = canonical(normalize(hangupCfg));

        // 用户改过口令就只认新口令；保持默认时才附带同义说法，避免改了以后旧口令仍然生效
        String[] hangupWords = wordsFor(hangup, AppSettings.DEFAULT_HANGUP, HANGUP_SYNONYMS);
        String[] answerWords = wordsFor(answer, AppSettings.DEFAULT_ANSWER, ANSWER_SYNONYMS);
        String[] timeWords = wordsFor(timeQuery, AppSettings.DEFAULT_TIME_QUERY, TIME_SYNONYMS);

        // 先说“挂断电话”再说“接电话”，两个口令互相不是子串，顺序只影响打分相同时的取舍
        int hangupScore = score(text, hangupWords, hangup);
        int answerScore = score(text, answerWords, answer);
        int timeScore = score(text, timeWords, timeQuery);

        int best = Math.max(hangupScore, Math.max(answerScore, timeScore));
        if (best <= 0) return Action.NONE;

        if (best == hangupScore) return Action.HANGUP;
        if (best == answerScore) return Action.ANSWER;
        return Action.TIME;
    }

    private static String[] wordsFor(String config, String defaultWord, String[] synonyms) {
        if (!config.equals(canonical(normalize(defaultWord)))) {
            return new String[]{config};
        }
        return merge(config, synonyms);
    }

    private static String[] merge(String primary, String[] synonyms) {
        String[] result = new String[synonyms.length + 1];
        result[0] = primary;
        for (int i = 0; i < synonyms.length; i++) {
            result[i + 1] = canonical(normalize(synonyms[i]));
        }
        return result;
    }

    private static int score(String text, String[] words, String configWord) {
        int best = 0;
        for (String word : words) {
            if (word == null || word.isEmpty()) continue;
            int value;
            if (text.contains(word)) {
                value = word.equals(configWord) ? 100 : 90;
            } else {
                value = approxContains(text, word) ? 70 : 0;
            }
            if (value > best) best = value;
        }
        return best;
    }

    /** 去掉空格和标点，只留下中文、字母、数字，防止分词带来的干扰。 */
    static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 文本里任意等长片段与口令的编辑距离不超过 1，就认为说到了。 */
    private static boolean approxContains(String text, String word) {
        // 三个字的短口令容易和日常用语撞车（比如“来电话”和“接电话”只差一个字），
        // 只有四个字及以上才允许错一个字
        int wordLen = word.length();
        if (wordLen < 4 || text.length() < wordLen - 1) return false;
        // 只在疑似出现的位置附近比对，全文滑动对短句来说开销可以忽略
        for (int i = 0; i + wordLen - 1 <= text.length(); i++) {
            int end = Math.min(i + wordLen, text.length());
            if (end - i < wordLen - 1) break;
            if (distance(text.substring(i, end), word) <= 1) return true;
        }
        return false;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
