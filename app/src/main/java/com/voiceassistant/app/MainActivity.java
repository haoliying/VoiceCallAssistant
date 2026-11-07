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
    private TextView tvSettingsHint;
    private TextView tvDebug;
    private EditText etTime;
    private EditText etAnswer;
    private EditText etHangup;
    private CheckBox cbAnnounce;

    private boolean serviceRunning = false;
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
        tvSettingsHint = findViewById(R.id.tvSettingsHint);
        tvDebug = findViewById(R.id.tvDebug);
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
                requestAllPermissions();
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

        // 来电姓名和接听都依赖通知使用权，没有就直接把用户带到授权页
        if (savedInstanceState == null && !CallNotificationListener.isEnabled(this)) {
            Toast.makeText(this, "请勾选“语音电话助手”，否则拿不到来电姓名、也无法接听",
                    Toast.LENGTH_LONG).show();
            openNotificationListenerSettings();
        }
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
        }
    }

    private void updatePermissionSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("录音：").append(stateOf(Manifest.permission.RECORD_AUDIO));
        sb.append("  电话状态：").append(stateOf(Manifest.permission.READ_PHONE_STATE));
        sb.append("\n通话记录：").append(stateOf(Manifest.permission.READ_CALL_LOG));
        sb.append("  接听电话：").append(stateOf(Manifest.permission.ANSWER_PHONE_CALLS));
        sb.append("\n拨打电话：").append(stateOf(Manifest.permission.CALL_PHONE));
        sb.append("\n来电筛选：").append(IncomingCallScreeningService.isEnabled(this) ? "已开启" : "未开启");
        sb.append("  通知使用权：").append(CallNotificationListener.isEnabled(this) ? "已开启" : "未开启");
        sb.append("\n无障碍服务：").append(CallAccessibilityService.isEnabledInSettings(this) ? "已开启" : "未开启");
        if (!CallNotificationListener.isEnabled(this)) {
            sb.append("\n提示：姓名和接听都靠“通知使用权”，请点下面的按钮开启");
        }
        tvPermissions.setText(sb.toString());
    }

    private String stateOf(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权";
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "在列表里找到“语音助手挂断辅助”并开启", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            appendDebug("无法打开无障碍设置: " + e.getMessage());
        }
    }

    /** 界面重新打开时，把后台期间产生的日志补齐到显示区。 */
    private void refreshLogFromStore() {
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
