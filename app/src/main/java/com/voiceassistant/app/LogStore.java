package com.voiceassistant.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 进程内共享的日志缓冲。
 *
 * 界面没打开时也会记录（来电报姓名正好发生在后台），
 * 用户回到界面点“复制日志”就能拿到完整过程，不用猜。
 */
final class LogStore {

    private static final int MAX_LINES = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private LogStore() {}

    private static String lastMessage;
    private static String lastLine;
    private static int repeatCount;

    static void append(String message) {
        if (message == null) return;
        synchronized (LINES) {
            // 连续重复的消息只累计次数：万一有循环，也不会把前面的日志冲掉
            if (message.equals(lastMessage)) {
                repeatCount++;
                if (!LINES.isEmpty()) LINES.removeLast();
                LINES.addLast(lastLine + "（重复" + (repeatCount + 1) + "次）");
                return;
            }
            String line;
            synchronized (TIME) {
                line = "[" + TIME.format(new Date()) + "] " + message;
            }
            lastMessage = message;
            lastLine = line;
            repeatCount = 0;
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
    }

    static void clear() {
        synchronized (LINES) {
            LINES.clear();
            lastMessage = null;
            lastLine = null;
            repeatCount = 0;
        }
    }

    static String dump() {
        synchronized (LINES) {
            if (LINES.isEmpty()) return "(暂无日志)";
            StringBuilder sb = new StringBuilder();
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
