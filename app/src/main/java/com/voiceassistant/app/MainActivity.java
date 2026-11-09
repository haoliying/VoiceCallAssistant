package com.voiceassistant.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int CALL_SCREENING_ROLE_REQUEST = 101;
    private static final int MAX_DEBUG_LINES = 60;

    private Button btnStartStop;
    private Button btnSaveSettings;
    private Button btnPermissions;
    private TextView tvStatus;
    private TextView tvCall;
    private TextView tvPermissions;
    private TextView tvLastNotif;
    private TextView tvModelStatus;
    private TextView tvSettingsHint;
    private TextView tvDebug;
    private EditText etTime;
    private EditText etAnswer;
    private EditText etHangup;
    private CheckBox cbAnnounce;

    private boolean serviceRunning = false;
    /** 本次打开应用已经引导过的权限页，避免用户返回后又被弹走。 */
    private final java.util.Set<String> guidedSteps = new java.util.HashSet<>();
    private final StringBuilder debugLog = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(VoiceService.EXTRA_STATUS);
            // 能收到服务的广播说明服务还在；收到“已停止”则说明它主动退出了
            serviceRunning = !VoiceService.STATUS_STOPPED.equals(status);
            applyStatus(status,
                    intent.getStringExtra(VoiceService.EXTRA_ERROR),
                    intent.getStringExtra(VoiceService.EXTRA_DETAIL));
        }
    };

    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(VoiceService.EXTRA_DEBUG);
            if (message != null) {
                appendDebug(message);
            }
        }
    };

    private final BroadcastReceiver callReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(VoiceService.EXTRA_CALL_STATE);
            String text = intent.getStringExtra(VoiceService.EXTRA_CALL_TEXT);
            if (VoiceService.CALL_STATE_IDLE.equals(state)) {
                tvCall.setText("通话状态：空闲");
            } else if (VoiceService.CALL_STATE_RINGING.equals(state)) {
                tvCall.setText("来电播报：" + (text != null ? text : "有来电"));
            } else {
                tvCall.setText("通话状态：通话中");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartStop = findViewById(R.id.btnStartStop);
        btnSaveSettings = findViewById(R.id.btnSaveSettings);
        btnPermissions = findViewById(R.id.btnPermissions);
        tvStatus = findViewById(R.id.tvStatus);
        tvCall = findViewById(R.id.tvCall);
        tvPermissions = findViewById(R.id.tvPermissions);
        tvLastNotif = findViewById(R.id.tvLastNotif);
        tvSettingsHint = findViewById(R.id.tvSettingsHint);
        tvDebug = findViewById(R.id.tvDebug);
        tvModelStatus = findViewById(R.id.tvModelStatus);
        etTime = findViewById(R.id.etTimeCommand);
        etAnswer = findViewById(R.id.etAnswerCommand);
        etHangup = findViewById(R.id.etHangupCommand);
        cbAnnounce = findViewById(R.id.cbAnnounceCaller);

        loadSettingsIntoViews();

        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serviceRunning) {
                    startVoiceService();
                } else {
                    stopVoiceService();
                }
            }
        });

        btnSaveSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });

        btnPermissions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 重新走一遍引导：运行时权限 -> 通知使用权 -> 无障碍
                guidedSteps.clear();
                requestAllPermissions();
                guideMissingAccess();
            }
        });

        findViewById(R.id.btnAsrModel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelChooser();
            }
        });

        findViewById(R.id.btnAccessibility).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        findViewById(R.id.btnCopyLog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLogToClipboard();
            }
        });

        // 长按日志区域也能复制，方便反馈问题
        tvDebug.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copyLogToClipboard();
                return true;
            }
        });

        findViewById(R.id.btnCallScreening).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestCallScreeningRole();
            }
        });

        findViewById(R.id.btnNotificationAccess).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNotificationListenerSettings();
            }
        });

        requestAllPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(statusReceiver, new IntentFilter(VoiceService.ACTION_STATUS));
        manager.registerReceiver(debugReceiver, new IntentFilter(VoiceService.ACTION_DEBUG));
        manager.registerReceiver(callReceiver, new IntentFilter(VoiceService.ACTION_CALL));
        refreshLogFromStore();
        refreshServiceState();
        updatePermissionSummary();
        updateLastNotification();
        updateModelStatus();
        guideMissingAccess();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.unregisterReceiver(statusReceiver);
        manager.unregisterReceiver(debugReceiver);
        manager.unregisterReceiver(callReceiver);
    }

    // ========== 配置 ==========

    private void loadSettingsIntoViews() {
        etTime.setText(AppSettings.getTimeQuery(this));
        etAnswer.setText(AppSettings.getAnswer(this));
        etHangup.setText(AppSettings.getHangup(this));
        cbAnnounce.setChecked(AppSettings.isAnnounceCaller(this));
        tvSettingsHint.setText("默认：几点了 / 接电话 / 挂断电话，改成别的说法后立即生效。");
    }

    private void saveSettings() {
        String time = etTime.getText().toString().trim();
        String answer = etAnswer.getText().toString().trim();
        String hangup = etHangup.getText().toString().trim();

        if (TextUtils.isEmpty(time) || TextUtils.isEmpty(answer) || TextUtils.isEmpty(hangup)) {
            Toast.makeText(this, "三个口令都不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (time.equals(answer) || time.equals(hangup) || answer.equals(hangup)) {
            Toast.makeText(this, "口令不能重复，否则无法区分动作", Toast.LENGTH_SHORT).show();
            return;
        }

        AppSettings.save(this, time, answer, hangup, cbAnnounce.isChecked());
        etTime.setText(AppSettings.getTimeQuery(this));
        etAnswer.setText(AppSettings.getAnswer(this));
        etHangup.setText(AppSettings.getHangup(this));
        tvSettingsHint.setText("已保存：" + time + " / " + answer + " / " + hangup + "（服务无需重启）");
        Toast.makeText(this, "指令已保存", Toast.LENGTH_SHORT).show();
        appendDebug("配置已更新: " + time + " / " + answer + " / " + hangup);
    }

    // ========== 权限 ==========

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.READ_CALL_LOG);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);
        permissions.add(Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    private void requestAllPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            updatePermissionSummary();
            return;
        }
        ActivityCompat.requestPermissions(this,
                missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionSummary();
            guideMissingAccess();
        }
    }

    private void updatePermissionSummary() {
        boolean notif = CallNotificationListener.isEnabled(this);
        boolean a11y = CallAccessibilityService.isEnabledInSettings(this);
        boolean a11yRunning = CallAccessibilityService.isRunning();
        StringBuilder sb = new StringBuilder();
        sb.append("必开：\n");
        sb.append("  录音：").append(stateOf(Manifest.permission.RECORD_AUDIO));
        sb.append("  电话状态：").append(stateOf(Manifest.permission.READ_PHONE_STATE));
        sb.append("  接听电话：").append(stateOf(Manifest.permission.ANSWER_PHONE_CALLS));
        sb.append("\n  无障碍服务：").append(!a11y ? "未开启（不开启就无法自动接听）"
                : (a11yRunning ? "已连接（靠它点/划接听）"
                    : "已开启但没连上，去无障碍里关掉再开一次"));
        sb.append("\n  通知使用权：").append(notif ? "已开启（优先用通知里的姓名和按钮）" : "未开启（拿不到姓名和通知按钮）");
        sb.append("\n建议开启：\n");
        sb.append("  通讯录：").append(stateOf(Manifest.permission.READ_CONTACTS))
                .append("（把来电号码换成姓名，只查这一个号码）");
        sb.append("\n  通话记录：").append(stateOf(Manifest.permission.READ_CALL_LOG))
                .append("（通讯录没找到时补姓名）");
        sb.append("  拨打电话：").append(stateOf(Manifest.permission.CALL_PHONE));
        sb.append("\n  来电筛选：").append(IncomingCallScreeningService.isEnabled(this) ? "已开启" : "未开启");
        tvPermissions.setText(sb.toString());
    }

    /**
     * 权限引导：运行时权限 -> 通知使用权 -> 无障碍服务。
     * 打开应用时缺什么就跳对应授权页；每一步每次启动只跳一次，避免死循环。
     */
    private void guideMissingAccess() {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) return;

        if (!CallNotificationListener.isEnabled(this)) {
            if (guidedSteps.add("notification")) {
                toastLong("第 1 步：在列表里找到“语音电话助手”，开启通知使用权（来电姓名和接听都要它）");
                openNotificationListenerSettings();
            }
            return;
        }

        if (!CallAccessibilityService.isEnabledInSettings(this)) {
            if (guidedSteps.add("accessibility")) {
                new AlertDialog.Builder(this)
                        .setTitle("还差一步：开启无障碍（华为必须开）")
                        .setMessage("华为/鸿蒙不允许第三方应用直接接听电话，"
                                + "必须靠无障碍服务去点“接听”或上滑接听。\n\n"
                                + "1. 点下面的“去开启”，会直接跳到本服务的开关页；\n"
                                + "2. 打开“语音助手来电辅助”，弹窗点“允许/确定”；\n"
                                + "3. 找不到入口时：设置 → 辅助功能 → 无障碍 → 无障碍服务"
                                + "（有的版本叫“已下载的服务”）→ 语音助手来电辅助。\n\n"
                                + "华为还常把无障碍服务后台清掉：设置 → 应用和服务 → 应用启动管理 → "
                                + "语音电话助手 → 关闭“自动管理”，把“允许自启动/允许后台活动”都打开。")
                        .setPositiveButton("去开启", (dialog, which) -> openAccessibilitySettings())
                        .setNegativeButton("稍后", null)
                        .show();
            }
        }
    }

    private void toastLong(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** 显示最近一条来电通知的原文，方便确认“到底读到了什么”。 */
    private void updateLastNotification() {
        String raw = CallNotificationListener.lastRaw();
        tvLastNotif.setText("最近来电通知：" + (raw == null || raw.isEmpty() ? "暂无" : raw));
    }

    private String stateOf(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权";
    }

    private void openAccessibilitySettings() {
        // 优先直接跳到本服务的开关页（Android 11+），省得用户在一长串列表里翻
        ComponentName component = new ComponentName(this, CallAccessibilityService.class);
        Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        detail.putExtra("android.intent.extra.COMPONENT_NAME", component.flattenToString());
        detail.putExtra("android.provider.extra.COMPONENT_NAME", component.flattenToString());
        try {
            startActivity(detail);
            Toast.makeText(this, "把“语音助手来电辅助”打开（华为有时要点右上角“允许”）",
                    Toast.LENGTH_LONG).show();
            return;
        } catch (Exception ignored) {
            // 部分鸿蒙版本没有这个页面，退回到无障碍总列表
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "无障碍 → 无障碍服务 / 已下载的服务 → 语音助手来电辅助 → 开启",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            appendDebug("无法打开无障碍设置: " + e.getMessage());
        }
    }

    // ========== 识别模型 ==========

    private java.io.File hdModelDir() {
        return new java.io.File(getFilesDir(), "vosk-model-hd");
    }

    private java.io.File hdArchiveFile() {
        return new java.io.File(getFilesDir(), "downloads/vosk-model-hd.zip");
    }

    private boolean isHdModelDownloaded() {
        return containsFile(hdModelDir(), "final.mdl");
    }

    private static boolean containsFile(java.io.File dir, String name) {
        java.io.File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return false;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                if (containsFile(f, name)) return true;
            } else if (f.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void updateModelStatus() {
        boolean hd = AppSettings.ASR_MODEL_HD.equals(AppSettings.getAsrModel(this));
        boolean downloaded = isHdModelDownloaded();
        String text;
        if (hd && downloaded) {
            text = "当前：高清模型（已下载，约 2GB，准确率更高）";
        } else if (hd) {
            text = "当前：高清模型（尚未下载，服务会在后台下载约 1.27GB；"
                    + "下载期间先用标准模型，下载完自动切换）";
        } else if (downloaded) {
            text = "当前：标准模型（内置 66MB）；高清模型已下载，可随时切换";
        } else {
            text = "当前：标准模型（内置 66MB，省内存）";
        }
        if (tvModelStatus != null) tvModelStatus.setText(text);
    }

    private void showModelChooser() {
        boolean hd = AppSettings.ASR_MODEL_HD.equals(AppSettings.getAsrModel(this));
        String[] items = {
                "标准模型（内置 66MB，够用、省内存）",
                "高清模型（1.27GB，嘈杂环境、口音重时明显更准）"
        };
        final int[] selected = {hd ? 1 : 0};
        new AlertDialog.Builder(this)
                .setTitle("选择识别模型")
                .setSingleChoiceItems(items, selected[0],
                        (dialog, which) -> selected[0] = which)
                .setPositiveButton("应用", (dialog, which) -> applyModelChoice(selected[0]))
                .setNeutralButton("删除高清模型", (dialog, which) -> confirmDeleteHdModel())
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyModelChoice(int choice) {
        boolean hd = choice == 1;
        if (!hd) {
            AppSettings.setAsrModel(this, AppSettings.ASR_MODEL_STANDARD);
            updateModelStatus();
            restartServiceForModel("正在切换到标准模型...");
            return;
        }

        boolean downloaded = isHdModelDownloaded();
        if (!downloaded) {
            long freeMb = freeSpaceMb();
            if (freeMb >= 0 && freeMb < 3500) {
                Toast.makeText(this, "存储空间不足：下载加解压需要约 3.4GB，当前可用 "
                        + freeMb + "MB", Toast.LENGTH_LONG).show();
                return;
            }
            long ramMb = totalRamMb();
            if (ramMb > 0 && ramMb < 3000) {
                Toast.makeText(this, "这台手机总内存只有 " + ramMb
                        + "MB，加载高清模型可能导致应用被杀，建议继续用标准模型", Toast.LENGTH_LONG).show();
            }
            new AlertDialog.Builder(this)
                    .setTitle("下载高清模型")
                    .setMessage("文件约 1.27GB，建议在 Wi-Fi 下进行。\n"
                            + "下载期间请保持服务运行，中断后下次启动会从断点继续。\n"
                            + "空间占用：压缩包 1.27GB，解压后约 2.0GB，解压期间共需约 3.4GB；"
                            + "解压完会自动删除压缩包。\n\n"
                            + "现在开始下载？")
                    .setPositiveButton("开始下载", (dialog, w) -> {
                        AppSettings.setAsrModel(this, AppSettings.ASR_MODEL_HD);
                        updateModelStatus();
                        restartServiceForModel("正在准备高清模型，下载进度见状态栏");
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        AppSettings.setAsrModel(this, AppSettings.ASR_MODEL_HD);
        updateModelStatus();
        restartServiceForModel("正在加载高清模型...");
    }

    private void restartServiceForModel(String hint) {
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
        if (serviceRunning) {
            stopVoiceService();
            new android.os.Handler(getMainLooper()).postDelayed(this::startVoiceService, 800);
        } else {
            startVoiceService();
        }
    }

    private void confirmDeleteHdModel() {
        new AlertDialog.Builder(this)
                .setTitle("删除高清模型")
                .setMessage("会删除已下载的高清模型和压缩包（约 1.3GB）。\n"
                        + "如果正在使用高清模型，会自动切回标准模型并重启服务。")
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean wasHd = AppSettings.ASR_MODEL_HD.equals(AppSettings.getAsrModel(this));
                    deleteRecursive(hdModelDir());
                    deleteRecursive(new java.io.File(getFilesDir(), "vosk-model-hd.tmp"));
                    java.io.File archive = hdArchiveFile();
                    if (archive.exists()) archive.delete();
                    if (wasHd) {
                        AppSettings.setAsrModel(this, AppSettings.ASR_MODEL_STANDARD);
                        restartServiceForModel("已删除高清模型，切回标准模型");
                    } else {
                        Toast.makeText(this, "高清模型已删除", Toast.LENGTH_SHORT).show();
                    }
                    updateModelStatus();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private long freeSpaceMb() {
        try {
            return new android.os.StatFs(getFilesDir().getAbsolutePath())
                    .getAvailableBytes() / 1024 / 1024;
        } catch (Exception e) {
            return -1;
        }
    }

    private long totalRamMb() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo info =
                        new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                return info.totalMem / 1024 / 1024;
            }
        } catch (Exception e) {
            // 拿不到就当未知
        }
        return -1;
    }

    private void deleteRecursive(java.io.File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    /** 界面重新打开时，把后台期间产生的日志补齐到显示区。 */    private void refreshLogFromStore() {
        String history = LogStore.dump();
        if (history == null || history.isEmpty()) return;
        String[] lines = history.split("\n");
        int start = Math.max(0, lines.length - MAX_DEBUG_LINES);
        debugLog.setLength(0);
        for (int i = start; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            debugLog.append(lines[i]).append("\n");
        }
        tvDebug.setText(debugLog.toString());
    }

    /** 把日志和当前权限状态一起复制到剪贴板，方便反馈。 */
    private void copyLogToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 语音电话助手 ").append(BuildConfig.VERSION_NAME).append(" ===\n");
        sb.append("录音=").append(stateOf(Manifest.permission.RECORD_AUDIO));
        sb.append(" 电话状态=").append(stateOf(Manifest.permission.READ_PHONE_STATE));
        sb.append(" 通话记录=").append(stateOf(Manifest.permission.READ_CALL_LOG)).append("\n");
        sb.append("来电筛选=").append(IncomingCallScreeningService.isEnabled(this) ? "已开启" : "未开启");
        sb.append(" 通知使用权=").append(CallNotificationListener.isEnabled(this) ? "已开启" : "未开启");
        sb.append(" 无障碍=").append(CallAccessibilityService.isEnabledInSettings(this) ? "已开启" : "未开启");
        sb.append("\n最近来电通知=").append(CallNotificationListener.lastRaw());
        sb.append("\n--- 日志 ---\n");
        // 用服务侧的共享缓冲：界面没打开时的来电日志也在里面
        sb.append(LogStore.dump());

        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("voice-assistant-log", sb.toString()));
                Toast.makeText(this, "已复制到剪贴板，粘贴发给我即可", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 申请“来电筛选”角色：Android 10+ 响铃前就能拿到号码。 */
    private void requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Android 10 以下不需要该角色", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                Toast.makeText(this, "本机不支持来电筛选角色，请改用通知使用权", Toast.LENGTH_LONG).show();
                return;
            }
            if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                Toast.makeText(this, "来电筛选已开启", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                    CALL_SCREENING_ROLE_REQUEST);
        } catch (Exception e) {
            appendDebug("申请来电筛选角色失败: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CALL_SCREENING_ROLE_REQUEST) {
            updatePermissionSummary();
            Toast.makeText(this, resultCode == RESULT_OK ? "来电筛选已开启" : "未开启来电筛选",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openNotificationListenerSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            Toast.makeText(this, "找到“语音助手”并允许读取通知", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                appendDebug("无法打开通知使用权设置: " + e.getMessage());
            }
        }
    }

    // ========== 服务 ==========

    /**
     * 服务与界面在同一个进程里，直接看静态实例最准。
     * 之前用 bindService() 判断，系统会顺手把服务创建出来，
     * 结果一进应用就变成“运行中”，按钮和实际状态对不上。
     */
    private void refreshServiceState() {
        serviceRunning = VoiceService.isAlive();
        if (serviceRunning) {
            refreshStatusText();
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent(VoiceService.ACTION_REQUEST_STATUS));
        } else {
            applyStatus(VoiceService.STATUS_STOPPED, "", "");
        }
    }

    /** 先用服务保存的状态占位，随后会收到服务的实时状态。 */
    private void refreshStatusText() {
        ServiceState.Snapshot snapshot = ServiceState.load(this);
        if (snapshot.isFresh()) {
            applyStatus(snapshot.status, snapshot.error, snapshot.detail);
        } else {
            applyStatus(VoiceService.STATUS_LOADING, "", "正在连接服务...");
        }
    }

    private void startVoiceService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            appendDebug("请先授予录音权限，再启动服务");
            requestAllPermissions();
            return;
        }

        debugLog.setLength(0);
        tvDebug.setText("");

        try {
            Intent intent = new Intent(this, VoiceService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceRunning = true;
            applyStatus(VoiceService.STATUS_LOADING, "", "服务启动中...");
            appendDebug("服务启动中...");
        } catch (Exception e) {
            serviceRunning = false;
            applyStatus(VoiceService.STATUS_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), "");
            appendDebug("启动失败: " + e.getMessage());
        }
    }

    private void stopVoiceService() {
        try {
            stopService(new Intent(this, VoiceService.class));
        } catch (Exception e) {
            appendDebug("停止服务失败: " + e.getMessage());
        }
        serviceRunning = false;
        ServiceState.save(this, false, VoiceService.STATUS_STOPPED, "", "");
        applyStatus(VoiceService.STATUS_STOPPED, "", "");
        tvCall.setText("通话状态：空闲");
        appendDebug("服务已停止");
    }

    private void applyStatus(String status, String error, String detail) {
        String text;
        if (VoiceService.STATUS_LOADING.equals(status)) {
            text = "服务状态：" + (detail != null && !detail.isEmpty() ? detail : "正在加载模型...");
        } else if (VoiceService.STATUS_READY.equals(status)
                || VoiceService.STATUS_LISTENING.equals(status)) {
            text = "服务状态：监听中，可说“" + AppSettings.getTimeQuery(this)
                    + "”查时间，来电时说“" + AppSettings.getAnswer(this) + "”";
        } else if (VoiceService.STATUS_ERROR.equals(status)) {
            text = "服务状态：出错 - " + (error != null ? error : "");
        } else {
            text = "服务状态：已停止";
        }

        tvStatus.setText(text);
        btnStartStop.setText(serviceRunning ? "停止服务" : "启动服务");
    }

    private void appendDebug(String message) {
        String time = timeFormat.format(new Date());
        String line = "[" + time + "] " + message + "\n";
        debugLog.append(line);

        // 只保留最近若干行，避免长时间运行占用内存
        String[] lines = debugLog.toString().split("\n");
        if (lines.length > MAX_DEBUG_LINES) {
            debugLog.setLength(0);
            int start = lines.length - MAX_DEBUG_LINES;
            for (int i = start; i < lines.length; i++) {
                debugLog.append(lines[i]).append("\n");
            }
        }

        tvDebug.setText(debugLog.toString());
    }
}
