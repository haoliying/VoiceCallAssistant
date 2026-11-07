package com.voiceassistant.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class VoiceService extends Service {

    private static final String TAG = "VoiceService";
    private static final String CHANNEL_ID = "voice_clock_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 16000;

    public static final String ACTION_STATUS = "com.voiceclock.STATUS";
    public static final String ACTION_DEBUG = "com.voiceclock.DEBUG";
    public static final String ACTION_REQUEST_STATUS = "com.voiceclock.REQUEST_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_DEBUG = "debug";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_LOADING = "loading";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_LISTENING = "listening";

    /** 通话状态变化（界面上用来显示“来电：张三”）。 */
    public static final String ACTION_CALL = "com.voiceassistant.CALL";
    public static final String EXTRA_CALL_STATE = "call_state";
    public static final String EXTRA_CALL_TEXT = "call_text";
    public static final String CALL_STATE_IDLE = "idle";
    public static final String CALL_STATE_RINGING = "ringing";
    public static final String CALL_STATE_OFFHOOK = "offhook";

    /** 通知栏上的兜底按钮，语音识别不灵时也能接/挂。 */
    private static final String ACTION_ANSWER_CALL = "com.voiceassistant.ANSWER_CALL";
    private static final String ACTION_HANG_UP_CALL = "com.voiceassistant.HANG_UP_CALL";

    private static final String TTS_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-theresa.tar.bz2";
    private static final String TTS_DIR_NAME = "tts-model";
    private static final String TTS_TMP_DIR_NAME = "tts-model.tmp";
    private static final String TTS_ARCHIVE_REL = "downloads/tts-model.tar.bz2";
    /** 只解压了一半的 onnx 文件不可能这么大（完整的 theresa.onnx 约 117MB），用来判断模型是否完整。 */
    private static final long MIN_MODEL_FILE_SIZE = 100L * 1024 * 1024;
    private static final long HEARTBEAT_INTERVAL_MS = 10000L;
    /** 数字的语速（>1 更快）。1.15 倍长度≈0.87 语速，接近正常念号码的节奏。 */
    private static final float DIGIT_SPEED = 0.87f;
    /** 数字之间的停顿（毫秒）。 */
    private static final int DIGIT_GAP_MS = 130;
    /** 手机号按 3-4-4 分组，组间多留一点空档。 */
    private static final int DIGIT_GROUP_GAP_MS = 280;
    /** 一轮播报结束到下一轮开始之间的静默间隔，留给用户下指令。 */
    private static final long RING_GAP_MS = 1200L;
    /** 兜底上限，防止状态判断失灵时无限播报。 */
    private static final int MAX_RING_ROUNDS = 30;
    /** 等整句号码合成的上限，超时就退回数字拼接。 */
    private static final long NUMBER_WAIT_MS = 6000L;

    /** 进程级锁：防止多个初始化线程同时读写模型目录。 */
    private static final Object MODEL_LOCK = new Object();

    // Vosk (speech recognition)
    private Model voskModel;
    private Recognizer recognizer;
    private AudioRecord audioRecord;

    // SherpaOnnx (TTS)
    private OfflineTts tts;
    private int ttsSampleRate = 22050;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private Handler handler;
    private Thread recognitionThread;
    private Thread initThread;
    private volatile boolean isListening = false;
    private volatile boolean modelReady = false;
    private volatile boolean ttsReady = false;
    private volatile boolean destroyed = false;
    private volatile long lastSpeakAt = 0L;
    private volatile String lastError = "";
    private volatile String currentStatus = STATUS_LOADING;
    private volatile String loadingDetail = "";
    private PowerManager.WakeLock wakeLock;

    // 通话相关
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private volatile int callState = TelephonyManager.CALL_STATE_IDLE;
    private volatile String callerNumber = "";
    private volatile String callerName = null;
    private volatile boolean contactLookupDone = false;
    private volatile int ringRound = 0;
    private volatile boolean speaking = false;
    private volatile long lastCommandAt = 0L;
    private volatile long lastAnswerAt = 0L;
    private volatile long lastHangupAt = 0L;
    /** 收到接听/挂断指令时置位，用来立刻掐断正在播报的语音。 */
    private volatile boolean abortSpeech = false;
    /** 正在执行接听/挂断，期间暂停重复播报。 */
    private volatile boolean answering = false;
    /** 同进程内的活动实例，界面用它判断服务是否真的在运行。 */
    private static volatile VoiceService instance;
    /** 每次通话结束就 +1，用来作废还在合成/排队中的播报。 */
    private volatile int speechEpoch = 0;
    /** 这一通电话是否已经提示过“无法接听”，避免反复喊。 */
    private volatile boolean answerFailureReported = false;
    private volatile long answerFailedAt = 0L;
    /**
     * 整个号码一句话合成好的音频（按号码缓存 6 条）。
     * 拼接单个数字会丢掉自然的气息和连读，听起来“喘不上气”，
     * 整句合成是唯一能保证语感的做法，所以第一次来电先合成，之后直接复用。
     */
    private final java.util.LinkedHashMap<String, float[]> numberAudioCache =
            new java.util.LinkedHashMap<String, float[]>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
                    return size() > 6;
                }
            };
    /** 当前正在播报的文本，用来过滤“麦克风把自己的声音听回去”的假指令。 */
    private volatile String currentSpeechText = "";
    /** TTS 与识别共用，保证同一时刻只有一句话在播。 */
    private final Object ttsLock = new Object();
    /** 当前正在播放的音轨，挂断时可以直接掐掉。 */
    private volatile AudioTrack activeTrack;
    /**
     * 0-9 和“来电话”提前合成就缓存起来。号码播报时直接拼接播放，
     * 不需要等模型逐字生成，避免电话都挂了才念出来。
     */
    private final java.util.concurrent.ConcurrentHashMap<Character, float[]> digitAudioCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile float[] callTailAudio;
    private volatile int ttsCacheRate = 22050;
    private volatile boolean digitCacheReady = false;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            ServiceState.save(VoiceService.this, true, currentStatus, lastError, loadingDetail);
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver statusRequestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_REQUEST_STATUS.equals(intent.getAction())) {
                broadcastStatus(currentStatus);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();

        // 必须在 onCreate 里就进入前台，否则 Android 14 上麦克风采集会直接抛异常
        try {
            Notification notification = buildNotification("正在初始化...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Log.e(TAG, "startForeground failed", e);
            ServiceState.save(this, false, STATUS_ERROR, lastError, "");
            Intent failure = new Intent(ACTION_STATUS);
            failure.putExtra(EXTRA_STATUS, STATUS_ERROR);
            failure.putExtra(EXTRA_ERROR, lastError);
            LocalBroadcastManager.getInstance(this).sendBroadcast(failure);
            stopSelf();
            return;
        }

        IntentFilter filter = new IntentFilter(ACTION_REQUEST_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusRequestReceiver, filter);

        LogStore.clear();
        ServiceState.save(this, true, STATUS_LOADING, "", "");
        handler.post(heartbeatRunnable);

        registerPhoneStateListener();
        initAll();
    }

    private void initAll() {
        broadcastStatus(STATUS_LOADING);
        broadcastDebug("[init] 初始化中...");

        initThread = new Thread(() -> {
            try {
                initVosk();

                // 2. Load SherpaOnnx TTS model
                initSherpaOnnxTts();

                // 后台预热号码读音，来电时才能立刻播报
                new Thread(this::buildDigitAudioCache, "tts-digit-cache").start();
                // 预热通讯录索引，来电时不用现场读联系人
                new Thread(() -> ContactsIndex.warmUp(this), "contacts-index").start();

                // 3. Init AudioRecord
                if (!initAudioRecord()) {
                    lastError = "AudioRecord init failed";
                    broadcastDebug("[error] 音频录制初始化失败");
                    broadcastStatus(STATUS_ERROR);
                    return;
                }

                broadcastDebug("[ok] 全部初始化成功");
                modelReady = true;
                startRecognitionLoop();
                broadcastStatus(STATUS_READY);
                broadcastDebug("[ready] 已就绪，开始监听...请说话");
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                broadcastDebug("[error] " + lastError);
                Log.e(TAG, "Failed to start: " + lastError, e);
                broadcastStatus(STATUS_ERROR);
            }
        });
        initThread.start();
    }

    // ========== Vosk (Speech Recognition) ==========

    private void initVosk() throws Exception {
        broadcastDebug("[init] 加载语音识别模型...");

        String modelPath = getFilesDir().getAbsolutePath() + "/vosk-model";
        File modelDir = new File(modelPath);
        File markerFile = new File(modelDir, ".ready");

        if (!markerFile.exists()) {
            broadcastDebug("[init] 首次启动，复制识别模型文件(约66MB)...");
            if (modelDir.exists()) {
                deleteRecursive(modelDir);
            }
            modelDir.mkdirs();
            copyAssetFolder("model", modelPath);
            markerFile.createNewFile();
        }

        voskModel = new Model(modelPath);
        recognizer = new Recognizer(voskModel, SAMPLE_RATE);
        lastError = "";
        broadcastDebug("[ok] 语音识别引擎就绪 (Vosk)");
    }

    // ========== SherpaOnnx TTS ==========

    private void initSherpaOnnxTts() throws Exception {
        broadcastDebug("[tts] 初始化 SherpaOnnx TTS...");

        File modelDir = new File(getFilesDir(), TTS_DIR_NAME);
        File archive = new File(getFilesDir(), TTS_ARCHIVE_REL);

        ModelFiles files = locateModelFiles(modelDir);
        if (files != null) {
            broadcastDebug("[tts] 本地模型已就绪，跳过下载");
            if (archive.exists()) archive.delete();
        } else {
            acquireWakeLock();
            try {
                // 同一个进程里可能有多个初始化线程（例如停止后立刻重新启动），
                // 用进程级锁保证同一时刻只有一个线程在写模型目录
                synchronized (MODEL_LOCK) {
                    files = locateModelFiles(modelDir);
                    if (files == null) {
                        prepareModel(archive, modelDir);
                        files = locateModelFiles(modelDir);
                        if (files == null) {
                            broadcastModelDir(modelDir);
                            throw new Exception("语音模型文件不完整，请重新启动服务重试");
                        }
                        // 只有确认模型可用后才删除压缩包，失败时保留以便重试
                        archive.delete();
                    }
                }
            } finally {
                releaseWakeLock();
            }
        }

        if (files == null) {
            throw new Exception("语音模型文件缺失，请检查网络后重新启动服务");
        }

        broadcastDebug("[tts] model: " + files.model);
        broadcastDebug("[tts] lexicon: " + files.lexicon);
        broadcastDebug("[tts] tokens: " + files.tokens);
        if (files.dictDir != null) broadcastDebug("[tts] dict: " + files.dictDir);

        OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig();
        vitsConfig.model = files.model;
        vitsConfig.lexicon = files.lexicon != null ? files.lexicon : "";
        vitsConfig.tokens = files.tokens;
        vitsConfig.dictDir = files.dictDir != null ? files.dictDir : "";
        vitsConfig.noiseScale = 0.667f;
        vitsConfig.noiseScaleW = 0.8f;
        vitsConfig.lengthScale = 1.0f;

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.vits = vitsConfig;
        modelConfig.numThreads = 2;
        modelConfig.debug = true;
        modelConfig.provider = "cpu";

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.model = modelConfig;
        config.ruleFsts = files.ruleFsts;
        config.maxNumSentences = 1;

        broadcastDebug("[tts] 加载语音合成引擎(需要一点时间)...");
        setLoadingDetail("正在加载语音合成引擎...");

        tts = new OfflineTts(config);
        ttsSampleRate = tts.getSampleRate();
        ttsReady = true;
        broadcastDebug("[tts-ok] 语音合成引擎就绪, sampleRate=" + ttsSampleRate);
    }

    private void prepareModel(File archive, File modelDir) throws IOException {
        if (archive.isFile() && archive.length() > 0) {
            broadcastDebug("[tts] 发现已下载的模型包，继续下载/解压...");
        } else {
            broadcastDebug("[tts] 未找到语音模型，开始下载(约116MB，请保持网络畅通)...");
        }

        // 下载失败时保留已下载的部分，下次启动可以续传
        downloadArchiveIfNeeded(archive);

        try {
            extractArchive(archive, modelDir);
        } catch (IOException e) {
            if (destroyed) {
                broadcastDebug("[tts] 服务已停止，保留压缩包");
                throw e;
            }
            int failures = ServiceState.getTtsFailures(this) + 1;
            ServiceState.setTtsFailures(this, failures);
            broadcastDebug("[tts] 解压失败(" + failures + "/2): " + e.getMessage());
            if (failures >= 2) {
                broadcastDebug("[tts] 压缩包可能已损坏，删除后下次重新下载");
                archive.delete();
                ServiceState.setTtsFailures(this, 0);
            } else {
                broadcastDebug("[tts] 已保留压缩包，重试时无需重新下载");
            }
            throw e;
        }
        ServiceState.setTtsFailures(this, 0);
    }

    /** 校验失败时把目录里的文件打出来，方便定位是哪个文件缺了。 */
    private void broadcastModelDir(File dir) {
        broadcastDebug("[tts] 模型目录: " + dir.getAbsolutePath());
        File[] files = dir.listFiles();
        if (files == null) {
            broadcastDebug("[tts] 目录不存在或无法读取");
            return;
        }
        int shown = 0;
        for (File f : files) {
            if (shown++ >= 12) {
                broadcastDebug("[tts] ...");
                break;
            }
            broadcastDebug("[tts] " + f.getName() + " " + f.length() + "B");
        }
    }

    /** 已解压到位的模型文件集合，缺关键文件时返回 null。 */
    private ModelFiles locateModelFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return null;

        String modelPath = findFile(dir, ".onnx");
        File tokens = findFileByName(dir, "tokens.txt");
        File lexicon = findFileByName(dir, "lexicon.txt");
        if (modelPath == null || tokens == null) return null;
        File model = new File(modelPath);
        if (model.length() < MIN_MODEL_FILE_SIZE || tokens.length() == 0) return null;

        File dictDir = findDir(dir, "dict");
        List<String> rules = new ArrayList<>();
        for (String ruleName : new String[]{"date.fst", "number.fst"}) {
            File rule = findFileByName(dir, ruleName);
            if (rule != null) rules.add(rule.getAbsolutePath());
        }

        return new ModelFiles(
                model.getAbsolutePath(),
                tokens.getAbsolutePath(),
                lexicon != null ? lexicon.getAbsolutePath() : null,
                dictDir != null ? dictDir.getAbsolutePath() : null,
                String.join(",", rules));
    }

    private void downloadArchiveIfNeeded(File archive) throws IOException {
        File parent = archive.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }

        long remoteSize = fetchRemoteSize();
        long localSize = archive.isFile() ? archive.length() : 0L;
        if (remoteSize > 0 && localSize == remoteSize) {
            broadcastDebug("[tts] 模型包已完整下载，跳过下载");
            return;
        }
        if (remoteSize > 0 && localSize > remoteSize) {
            archive.delete();
        }

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                downloadOnce(archive);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (destroyed) throw e;
                broadcastDebug("[tts] 下载失败(" + attempt + "/3): " + e.getMessage());
                setLoadingDetail("下载中断，正在重试(" + attempt + "/3)...");
                sleepQuietly(2000);
            }
        }
        throw new IOException("模型下载失败: "
                + (lastFailure != null ? lastFailure.getMessage() : "未知错误"), lastFailure);
    }

    private void downloadOnce(File archive) throws IOException {
        long startAt = archive.isFile() ? archive.length() : 0L;
        if (startAt < 0) startAt = 0;

        HttpURLConnection conn = openConnection(TTS_MODEL_URL, startAt, "GET");
        int responseCode;
        try {
            responseCode = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }

        boolean append;
        long expectedTotal;
        if (responseCode == HttpURLConnection.HTTP_PARTIAL && startAt > 0) {
            append = true;
            long len = conn.getContentLengthLong();
            expectedTotal = len > 0 ? startAt + len : -1;
        } else if (responseCode == HttpURLConnection.HTTP_OK) {
            append = false;
            expectedTotal = conn.getContentLengthLong();
            startAt = 0;
        } else if (responseCode == 416) {
            // 本地文件已经到末尾但服务器大小对不上，重新下载
            conn.disconnect();
            archive.delete();
            throw new IOException("HTTP 416，重新下载");
        } else {
            conn.disconnect();
            throw new IOException("HTTP " + responseCode);
        }

        broadcastDebug("[tts] 下载: HTTP " + responseCode + "，已下载 "
                + (startAt / 1024 / 1024) + "MB"
                + (expectedTotal > 0 ? " / 共 " + (expectedTotal / 1024 / 1024) + "MB" : ""));

        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(archive, append);
        long written = startAt;
        int lastPercent = -1;
        try {
            byte[] buffer = new byte[128 * 1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (destroyed) {
                    throw new IOException("服务已停止，下载中断");
                }
                out.write(buffer, 0, bytesRead);
                written += bytesRead;

                if (expectedTotal > 0) {
                    int percent = (int) (written * 100 / expectedTotal);
                    if (percent >= lastPercent + 2 || percent == 100) {
                        lastPercent = percent;
                        int safePercent = Math.min(percent, 100);
                        setLoadingDetail("正在下载语音模型 " + safePercent + "%（"
                                + (written / 1024 / 1024) + "/" + (expectedTotal / 1024 / 1024) + "MB）");
                    }
                }
            }
        } finally {
            try { out.flush(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
            try { in.close(); } catch (IOException ignored) { }
            conn.disconnect();
        }

        if (expectedTotal > 0 && archive.length() != expectedTotal) {
            long actual = archive.length();
            if (actual > expectedTotal) archive.delete(); // 续传位置错了，下次从头来
            throw new IOException("文件不完整: " + actual + "/" + expectedTotal);
        }
        broadcastDebug("[tts] 下载完成: " + (archive.length() / 1024 / 1024) + "MB");
    }

    private HttpURLConnection openConnection(String urlStr, long rangeStart, String method) throws IOException {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 5; hop++) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "VoiceClock/1.1");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(false);
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("重定向缺少 Location");
                currentUrl = new URL(url, location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    /** 通过 HEAD 请求拿到服务器上的文件大小，用来判断本地压缩包是否已经下载完整。 */
    private long fetchRemoteSize() {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(TTS_MODEL_URL, 0, "HEAD");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return conn.getContentLengthLong();
            }
        } catch (Exception e) {
            Log.w(TAG, "HEAD request failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return -1;
    }

    private void extractArchive(File archive, File finalDir) throws IOException {
        File tmpDir = new File(getFilesDir(), TTS_TMP_DIR_NAME);
        deleteRecursive(tmpDir);
        if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
            throw new IOException("无法创建临时目录");
        }

        long archiveSize = archive.length();

        // 解压后模型约 130MB，解压期间压缩包也还在，先确认空间够用
        long needed = archiveSize + 150L * 1024 * 1024;
        long available = -1;
        try {
            available = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            Log.w(TAG, "check free space failed", e);
        }
        if (available >= 0 && available < needed) {
            throw new IOException("存储空间不足：解压需要约 " + (needed / 1024 / 1024)
                    + "MB，当前可用 " + (available / 1024 / 1024) + "MB");
        }

        broadcastDebug("[tts] 解压中 (" + (archiveSize / 1024 / 1024) + "MB)...");
        setLoadingDetail("正在解压语音模型 0%");

        int fileCount = 0;
        int lastPercent = -1;
        long writtenBytes = 0;
        long lastProgressAt = 0;
        long lastLogAt = 0;
        CountingInputStream counting = null;
        BZip2CompressorInputStream bzIn = null;
        TarArchiveInputStream tarIn = null;

        try {
            counting = new CountingInputStream(new FileInputStream(archive));
            bzIn = new BZip2CompressorInputStream(counting, true);
            tarIn = new TarArchiveInputStream(bzIn);

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                if (entry.isDirectory() || entry.isLink() || entry.isSymbolicLink()) continue;

                String name = entry.getName().replace('\\', '/');
                int slashIndex = name.indexOf('/');
                if (slashIndex >= 0) name = name.substring(slashIndex + 1);
                if (name.isEmpty() || name.contains("..")) continue;

                File outputFile = new File(tmpDir, name);
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                FileOutputStream out = new FileOutputStream(outputFile);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int len;
                    // 进度必须按字节更新：最大的 onnx 是一个 117MB 的单独条目，
                    // 如果只在文件之间更新，解压期间进度会一直停在上一个文件的位置
                    while ((len = tarIn.read(buffer)) != -1) {
                        if (destroyed) {
                            throw new IOException("服务已停止，解压中断");
                        }
                        out.write(buffer, 0, len);
                        writtenBytes += len;

                        if (archiveSize > 0) {
                            int percent = (int) (counting.count * 100 / archiveSize);
                            long now = SystemClock.elapsedRealtime();
                            if (percent >= lastPercent + 1 || now - lastProgressAt >= 2000) {
                                lastPercent = percent;
                                lastProgressAt = now;
                                setLoadingDetail("正在解压语音模型 " + Math.min(percent, 100)
                                        + "%（已写入 " + (writtenBytes / 1024 / 1024) + "MB）");
                            }
                            if (now - lastLogAt >= 10000) {
                                lastLogAt = now;
                                broadcastDebug("[tts] 解压 " + Math.min(percent, 100) + "%（"
                                        + (writtenBytes / 1024 / 1024) + "MB）");
                            }
                        }
                    }
                } finally {
                    out.close();
                }

                // 对比 tar 头里声明的大小，文件写短了（空间不足/压缩包损坏）能立刻发现
                long declaredSize = entry.getSize();
                long actualSize = outputFile.length();
                if (declaredSize >= 0 && actualSize != declaredSize) {
                    throw new IOException("解压不完整: " + name + " ("
                            + actualSize + "/" + declaredSize + ")");
                }
                fileCount++;
            }
        } finally {
            closeQuietly(tarIn);
            closeQuietly(bzIn);
            closeQuietly(counting);
        }

        if (destroyed) {
            throw new IOException("服务已停止，解压中断");
        }
        broadcastDebug("[tts] 解压完成，共 " + fileCount + " 个文件");

        if (locateModelFiles(tmpDir) == null) {
            // 把实际解压出来的文件打出来，看看是哪一项缺失
            broadcastModelDir(tmpDir);
            deleteRecursive(tmpDir);
            throw new IOException("解压后缺少模型文件");
        }

        deleteRecursive(finalDir);
        if (!tmpDir.renameTo(finalDir)) {
            deleteRecursive(tmpDir);
            throw new IOException("无法写入模型目录");
        }
        broadcastDebug("[tts] 模型已就绪");
    }

    private String findFile(File dir, String suffix) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (!f.isDirectory() && f.getName().endsWith(suffix)) return f.getAbsolutePath();
        }
        for (File f : files) {
            if (f.isDirectory()) {
                String result = findFile(f, suffix);
                if (result != null) return result;
            }
        }
        return null;
    }

    private File findFileByName(File dir, String name) {
        String path = findFile(dir, name);
        return path != null ? new File(path) : null;
    }

    private File findDir(File dir, String name) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory() && f.getName().equals(name)) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File result = findDir(f, name);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static final class ModelFiles {
        final String model;
        final String tokens;
        final String lexicon;
        final String dictDir;
        final String ruleFsts;

        ModelFiles(String model, String tokens, String lexicon, String dictDir, String ruleFsts) {
            this.model = model;
            this.tokens = tokens;
            this.lexicon = lexicon;
            this.dictDir = dictDir;
            this.ruleFsts = ruleFsts;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceClock:model");
                wakeLock.setReferenceCounted(false);
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(15 * 60 * 1000L);
            }
        } catch (Exception e) {
            Log.w(TAG, "acquireWakeLock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            Log.w(TAG, "releaseWakeLock failed", e);
        }
    }

    // ========== Audio Playback ==========

    private void playAudio(float[] samples, int sampleRate) {
        if (samples == null || samples.length == 0) return;

        short[] shortSamples = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            shortSamples[i] = (short) (s * Short.MAX_VALUE);
        }

        byte[] audioBytes = new byte[shortSamples.length * 2];
        ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortSamples);

        int bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        AudioTrack audioTrack = new AudioTrack(
                attrs,
                format,
                Math.max(bufferSize, audioBytes.length),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        activeTrack = audioTrack;
        requestAudioFocus();
        try {
            audioTrack.play();
            audioTrack.write(audioBytes, 0, audioBytes.length);
            // 分小段等待，收到“接电话/挂断电话”时可以立刻收声
            long deadline = SystemClock.elapsedRealtime()
                    + (long) (samples.length * 1000.0 / sampleRate) + 150;
            while (!abortSpeech && SystemClock.elapsedRealtime() < deadline) {
                sleepQuietly(40);
            }
            if (abortSpeech) {
                try {
                    audioTrack.pause();
                    audioTrack.flush();
                } catch (Exception ignored) {
                    // 音轨已经结束
                }
                broadcastDebug("[tts] 已按指令停止播报");
            }
        } catch (Exception e) {
            Log.w(TAG, "playAudio interrupted", e);
        } finally {
            activeTrack = null;
            try { audioTrack.stop(); } catch (Exception ignored) { }
            try { audioTrack.release(); } catch (Exception ignored) { }
            abandonAudioFocus();
        }
    }

    /** 立即停止正在播放的播报（例如来电已经挂断）。 */
    private void stopPlayback() {
        abortSpeech = true;
        AudioTrack track = activeTrack;
        if (track == null) return;
        try {
            track.pause();
            track.flush();
        } catch (Exception e) {
            Log.w(TAG, "stopPlayback failed", e);
        }
    }

    // ========== Audio Focus ==========

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build();
            return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            @SuppressWarnings("deprecation")
            int result = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }

    // ========== Speech Recognition Loop ==========

    private boolean initAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) return false;

        int bufferSize = Math.max(minBufferSize, 16000);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        } catch (SecurityException e) {
            lastError = "缺少录音权限";
            return false;
        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            return false;
        }
        return true;
    }

    private void startRecognitionLoop() {
        isListening = true;
        broadcastStatus(STATUS_LISTENING);

        recognitionThread = new Thread(() -> {
            try {
                audioRecord.startRecording();
            } catch (Exception e) {
                lastError = "无法读取麦克风: " + e.getMessage();
                broadcastDebug("[error] " + lastError);
                broadcastStatus(STATUS_ERROR);
                return;
            }
            byte[] buffer = new byte[8192];

            while (isListening) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead < 0) break;

                if (bytesRead > 0 && recognizer != null && modelReady) {
                    try {
                        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                            processResult(recognizer.getResult());
                        } else {
                            processPartialResult(recognizer.getPartialResult());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Recognition error", e);
                    }
                }
            }
        });
        recognitionThread.start();
    }

    private void processResult(String resultJson) {
        try {
            String text = new JSONObject(resultJson).optString("text", "");
            broadcastDebug("[识别] " + text);
            handleRecognizedText(text);
        } catch (Exception e) {
            broadcastDebug("[error] 解析结果失败");
        }
    }

    private void processPartialResult(String partialJson) {
        try {
            String text = new JSONObject(partialJson).optString("partial", "");
            if (!text.isEmpty()) {
                broadcastDebug("[语音] " + text);
                handleRecognizedText(text);
            }
        } catch (Exception e) {
            Log.e(TAG, "Partial result error", e);
        }
    }

    /**
     * 所有识别结果都走这里：按界面上配置的口令判断该做什么。
     * 播报期间只放行“接电话 / 挂断电话”（用户经常在播报中途就喊），
     * 其它内容丢弃，避免把自己的声音当成指令。
     */
    private void handleRecognizedText(String text) {
        if (text == null || text.trim().isEmpty()) return;

        if (speaking && echoOfOwnSpeech(text)) return;

        VoiceCommands.Action action = VoiceCommands.match(this, text);
        if (action == VoiceCommands.Action.NONE) return;

        if (speaking) {
            if (action != VoiceCommands.Action.ANSWER && action != VoiceCommands.Action.HANGUP) {
                broadcastDebug("[命令] 播报中，忽略: " + text);
                return;
            }
            broadcastDebug("[命令] 播报中收到“" + text + "”，立即停止播报");
            abortSpeech = true;
            stopPlayback();
        }

        long now = SystemClock.elapsedRealtime();
        switch (action) {
            case TIME:
                if (now - lastCommandAt < 2000L) return;
                lastCommandAt = now;
                broadcastDebug("[命中] 时间查询");
                maybeSpeakTime();
                break;
            case ANSWER:
                // 播报刚结束用户就会喊“接电话”，这里只防同一条指令的重复结果
                if (now - lastAnswerAt < 2000L) return;
                lastAnswerAt = now;
                broadcastDebug("[命中] 接听指令");
                answerIncomingCall();
                break;
            case HANGUP:
                if (now - lastHangupAt < 2000L) return;
                lastHangupAt = now;
                broadcastDebug("[命中] 挂断指令");
                hangUpCall();
                break;
            default:
                break;
        }
    }

    /** 这段识别结果是不是把本机刚刚播报的内容又听了回来。 */
    private boolean echoOfOwnSpeech(String text) {
        String spoken = currentSpeechText;
        if (spoken == null || spoken.isEmpty()) return false;
        String heard = normalizeSpeech(text);
        if (heard.length() < 2) return false;
        return normalizeSpeech(spoken).contains(heard);
    }

    private static String normalizeSpeech(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /** 同一次提问会连续出现多个 partial 结果，加个冷却避免重复播报。 */
    private void maybeSpeakTime() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSpeakAt < 4000L) return;
        lastSpeakAt = now;
        handler.post(this::speakTime);
    }

    // ========== Speak Time ==========

    private void speakTime() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        String period;
        if (hour < 6) period = "凌晨";
        else if (hour < 12) period = "上午";
        else if (hour < 14) period = "中午";
        else if (hour < 18) period = "下午";
        else period = "晚上";

        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        String timeText = "现在是" + period + toChineseNumber(displayHour) + "点"
                + (minute == 0 ? "整" : toChineseNumber(minute) + "分");
        speak(timeText);
    }

    /** 统一的播报入口：同一时刻只播一句，播报期间不响应识别结果。 */
    private void speak(String text) {
        speak(text, false, null);
    }

    /**
     * @param onlyWhileRinging 只在这通电话仍处于响铃状态时播报，挂断后不再念号码
     * @param onDone           播完（或被跳过）后的回调，用来安排下一轮播报
     */
    private void speak(String text, boolean onlyWhileRinging, Runnable onDone) {
        final OfflineTts currentTts = tts;
        if (!ttsReady || currentTts == null) {
            broadcastDebug("[tts-error] TTS 未就绪，无法播报: " + text);
            if (onDone != null) onDone.run();
            return;
        }
        if (onlyWhileRinging && !isPhoneStillRinging()) {
            if (onDone != null) onDone.run();
            return;
        }
        broadcastDebug("[tts] 播报: " + text);
        final int epoch = speechEpoch;

        new Thread(() -> {
            boolean skipped = false;
            synchronized (ttsLock) {
                // 排队等待期间电话可能已经结束，这里再确认一次
                if ((onlyWhileRinging && !isPhoneStillRinging()) || epoch != speechEpoch) {
                    skipped = true;
                } else {
                    abortSpeech = false;
                    speaking = true;
                    try {
                        GeneratedAudio audio = currentTts.generate(text);
                        if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                            if (abortSpeech || epoch != speechEpoch || destroyed) {
                                broadcastDebug("[tts] 已收到指令，跳过本次播报");
                            } else {
                                currentSpeechText = text;
                                playAudio(audio.getSamples(), audio.getSampleRate());
                                broadcastDebug("[tts-ok] 播报完成");
                            }
                        } else {
                            broadcastDebug("[tts-error] 生成音频失败");
                        }
                    } catch (Exception e) {
                        broadcastDebug("[tts-error] " + e.getMessage());
                        Log.e(TAG, "TTS error", e);
                    } finally {
                        currentSpeechText = "";
                        // 留一点尾巴，避免喇叭余音被麦克风当成指令
                        sleepQuietly(300);
                        speaking = false;
                        lastCommandAt = SystemClock.elapsedRealtime();
                    }
                }
            }
            if (skipped) {
                broadcastDebug("[tts] 已挂断，跳过本轮播报");
            }
            if (onDone != null) onDone.run();
        }).start();
    }

    /** 把 0~59 转成中文数字，避免 TTS 把阿拉伯数字读错或直接跳过。 */
    private static String toChineseNumber(int value) {
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (value < 10) return digits[value];
        int tens = value / 10;
        int units = value % 10;
        StringBuilder sb = new StringBuilder();
        if (tens == 1) {
            sb.append("十");
        } else {
            sb.append(digits[tens]).append("十");
        }
        if (units > 0) sb.append(digits[units]);
        return sb.toString();
    }

    // ========== 来电处理 ==========

    private void registerPhoneStateListener() {
        if (!PhoneCallHelper.canReadPhoneState(this)) {
            broadcastDebug("[来电] 没有“电话状态”权限，来电报姓名不可用");
            return;
        }
        try {
            telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (telephonyManager == null) return;
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String incomingNumber) {
                    handleCallStateChanged(state, incomingNumber);
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            broadcastDebug("[来电] 通话状态监听已注册");
        } catch (SecurityException e) {
            broadcastDebug("[来电] 注册通话监听失败: " + e.getMessage());
        } catch (Exception e) {
            broadcastDebug("[来电] 注册通话监听异常: " + e.getMessage());
        }
    }

    private void unregisterPhoneStateListener() {
        if (telephonyManager != null && phoneStateListener != null) {
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception ignored) {
                // 注销失败不影响退出
            }
        }
        phoneStateListener = null;
    }

    private void handleCallStateChanged(int state, String incomingNumber) {
        if (destroyed) return;
        int previous = callState;
        callState = state;

        if (state == TelephonyManager.CALL_STATE_RINGING) {
            if (previous == TelephonyManager.CALL_STATE_RINGING) return;
            callerNumber = incomingNumber == null ? "" : incomingNumber.trim();
            if (callerNumber.isEmpty()) {
                String screened = IncomingCallScreeningService.recentNumber();
                if (screened != null) callerNumber = screened;
            }
            callerName = null;
            contactLookupDone = false;
            answerFailureReported = false;
            answerFailedAt = 0L;
            logCallPermissions();
            startRingAnnouncements();
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (previous == TelephonyManager.CALL_STATE_OFFHOOK) return;
            stopRingAnnouncements();
            broadcastDebug("[来电] 已接通，通话中");
            notifyCallState(CALL_STATE_OFFHOOK, "通话中");
            updateNotification(currentStatus);
        } else {
            if (previous == TelephonyManager.CALL_STATE_IDLE) return;
            stopRingAnnouncements();
            callerNumber = "";
            callerName = null;
            contactLookupDone = false;
            CallNotificationListener.clear();
            IncomingCallScreeningService.clear();
            speechEpoch++;
            stopPlayback();
            broadcastDebug("[来电] 通话结束");
            notifyCallState(CALL_STATE_IDLE, "空闲");
            updateNotification(currentStatus);
        }
    }

    /** 响铃期间反复播报：每轮播完再排下一轮，避免播报任务越积越多。 */
    private final Runnable ringAnnouncer = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (answering) {
                // 正在接听，等结果出来再决定要不要继续播报
                handler.postDelayed(this, 800L);
                return;
            }
            if (!isPhoneStillRinging()) {
                onRingingGone();
                return;
            }
            if (ringRound >= MAX_RING_ROUNDS) {
                broadcastDebug("[来电] 已达到最大播报次数，停止播报");
                stopRingAnnouncements();
                return;
            }
            ringRound++;
            final int round = ringRound;
            new Thread(() -> announceRingRound(round)).start();
        }
    };

    private void startRingAnnouncements() {
        handler.removeCallbacks(ringAnnouncer);
        ringRound = 0;
        handler.post(ringAnnouncer);
    }

    private void stopRingAnnouncements() {
        handler.removeCallbacks(ringAnnouncer);
    }

    /** 每一轮都重新确认号码和姓名，通知栏后补上的信息下一轮就能用上。 */
    private void announceRingRound(int round) {
        String number = resolveCallerNumber(round == 1);
        if (!number.isEmpty() && !number.equals(callerNumber)) {
            // 号码变了（例如通知栏后补上来），需要重新查一次联系人
            callerNumber = number;
            callerName = null;
            contactLookupDone = false;
        }
        // 有号码但还没查过就查一次：之前这里只在号码变化时才查，
        // 恰好漏掉了"回调里直接拿到号码"这种最常见的情况
        if (callerName == null && !callerNumber.isEmpty() && !contactLookupDone) {
            contactLookupDone = true;
            callerName = PhoneCallHelper.lookupContactName(this, callerNumber);
            if (callerName != null) {
                broadcastDebug("[来电] 匹配到联系人: " + callerName
                        + (PhoneCallHelper.lastSource.isEmpty() ? "" : "（来源: " + PhoneCallHelper.lastSource + "）"));
            } else if (PhoneCallHelper.hasPermission(this, Manifest.permission.READ_CONTACTS)) {
                broadcastDebug("[来电] 通讯录里没有该号码，按陌生号码处理（"
                        + ContactsIndex.diagnose(this, callerNumber) + "）");
            } else {
                broadcastDebug("[来电] 通讯录未授权，无法匹配姓名，请在应用里授予联系人权限");
            }
        }
        // 系统通讯录看不到时（例如联系人只在 SIM 卡里），通话通知里通常已经有姓名
        if (callerName == null) {
            String hint = CallNotificationListener.recentName();
            if (hint != null) {
                callerName = hint;
                broadcastDebug("[来电] 从通话通知里读到姓名: " + hint);
            }
        }

        if (destroyed) return;
        if (!isPhoneStillRinging()) {
            onRingingGone();
            return;
        }

        String text = buildCallerText();
        broadcastDebug("[来电] 第" + round + "次播报: " + text + "（号码 "
                + (callerNumber.isEmpty() ? "未知" : PhoneCallHelper.maskNumber(callerNumber)) + "）");
        notifyCallState(CALL_STATE_RINGING, text);
        updateNotification(currentStatus);

        if (AppSettings.isAnnounceCaller(this)) {
            // 陌生号码：先把整句合成起来，拼数字会丢掉连读和气息
            if ((callerName == null || callerName.isEmpty()) && !callerNumber.isEmpty()) {
                final String raw = callerNumber;
                if (cachedNumberAudio(raw.replaceAll("[^0-9]", "")) == null) {
                    new Thread(() -> prewarmNumberAudio(raw), "tts-number").start();
                }
            }
            Runnable nextRound = () -> {
                if (destroyed) return;
                if (isPhoneStillRinging()) {
                    handler.postDelayed(ringAnnouncer, RING_GAP_MS);
                } else {
                    onRingingGone();
                }
            };
            if (callerName != null && !callerName.isEmpty()) {
                speak(callerName + "来电话", true, nextRound);
            } else if (!callerNumber.isEmpty()) {
                speakNumber(callerNumber, true, nextRound);
            } else {
                speak("有来电", true, nextRound);
            }
        } else {
            stopRingAnnouncements();
        }
    }

    /** 响铃已经结束（对方挂断或超时），同步状态并立即收声。 */
    private void onRingingGone() {
        stopRingAnnouncements();
        if (callState != TelephonyManager.CALL_STATE_RINGING) return;

        int actual = queryCallState();
        if (actual == TelephonyManager.CALL_STATE_OFFHOOK) {
            callState = TelephonyManager.CALL_STATE_OFFHOOK;
            notifyCallState(CALL_STATE_OFFHOOK, "通话中");
        } else {
            callState = TelephonyManager.CALL_STATE_IDLE;
            callerNumber = "";
            callerName = null;
            contactLookupDone = false;
            CallNotificationListener.clear();
            IncomingCallScreeningService.clear();
            speechEpoch++;
            broadcastDebug("[来电] 检测到响铃已结束，停止播报");
            notifyCallState(CALL_STATE_IDLE, "空闲");
            stopPlayback();
        }
        updateNotification(currentStatus);
    }

    /** 直接问系统当前通话状态，不依赖回调，防止漏掉 STATE_IDLE。 */
    private int queryCallState() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null && PhoneCallHelper.canReadPhoneState(this)) {
                return tm.getCallState();
            }
        } catch (Exception e) {
            Log.w(TAG, "getCallState failed", e);
        }
        return callState;
    }

    /** 是否仍在响铃；系统状态拿不到时退回回调里记录的状态。 */
    private boolean isPhoneStillRinging() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm != null && PhoneCallHelper.canReadPhoneState(this)) {
                return tm.getCallState() == TelephonyManager.CALL_STATE_RINGING;
            }
        } catch (Exception e) {
            Log.w(TAG, "getCallState failed", e);
        }
        return callState == TelephonyManager.CALL_STATE_RINGING;
    }

    /** 号码优先用系统回调里的；为空时依次试来电筛选、通知栏。 */
    private String resolveCallerNumber(boolean waitForNotification) {
        if (!callerNumber.isEmpty()) return callerNumber;

        String screened = IncomingCallScreeningService.recentNumber();
        if (screened != null) return screened;

        String fromNotification = CallNotificationListener.recentNumber();
        if (fromNotification != null) return fromNotification;
        if (!waitForNotification || !CallNotificationListener.isEnabled(this)) return "";

        // 来电通知通常比铃声晚一点到，最多等 1.2 秒
        for (int i = 0; i < 12 && !destroyed
                && callState == TelephonyManager.CALL_STATE_RINGING; i++) {
            sleepQuietly(100);
            fromNotification = CallNotificationListener.recentNumber();
            if (fromNotification != null) return fromNotification;
        }
        return "";
    }

    /** 界面上显示 / 通知里用的文案（数字保持原样，便于阅读）。 */
    private String buildCallerText() {
        if (callerName != null && !callerName.isEmpty()) {
            return callerName + "来电话";
        }
        if (!callerNumber.isEmpty()) {
            return callerNumber + "来电话";
        }
        return "有来电";
    }

    /**
     * 号码播报：用提前缓存好的数字读音拼接，并插入 200ms 静音。
     * 缓存没准备好时退化成“整句一次合成 + 逗号停顿”，保证还能出声。
     */
    private void speakNumber(String number, boolean onlyWhileRinging, Runnable onDone) {
        final OfflineTts currentTts = tts;
        if (!ttsReady || currentTts == null) {
            broadcastDebug("[tts-error] TTS 未就绪，无法播报号码");
            if (onDone != null) onDone.run();
            return;
        }
        if (onlyWhileRinging && !isPhoneStillRinging()) {
            if (onDone != null) onDone.run();
            return;
        }
        final String digits = number.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            speak("有来电", onlyWhileRinging, onDone);
            return;
        }
        final int epoch = speechEpoch;
        boolean cached = cachedNumberAudio(digits) != null;
        broadcastDebug("[tts] 播报号码: " + PhoneCallHelper.maskNumber(digits)
                + (cached ? "（整句缓存）" : "（整句合成中）"));

        new Thread(() -> {
            boolean skipped = false;
            if (onlyWhileRinging && !isPhoneStillRinging()) {
                skipped = true;
            } else {
                abortSpeech = false;
                speaking = true;
                try {
                    // 在锁外等整句合成，prewarm 线程拿到锁才能干活
                    float[] audio = waitForNumberAudio(digits, NUMBER_WAIT_MS);
                    synchronized (ttsLock) {
                        if (audio == null) {
                            broadcastDebug("[tts] 整句未就绪，改用数字拼接");
                            audio = buildNumberAudio(digits, currentTts);
                        }
                        if (audio != null && audio.length > 0 && !destroyed
                                && epoch == speechEpoch && !abortSpeech) {
                            currentSpeechText = digits + "来电话";
                            playAudio(audio, ttsCacheRate);
                            broadcastDebug("[tts-ok] 号码播报完成");
                        } else if (abortSpeech) {
                            broadcastDebug("[tts] 已收到指令，跳过本次播报");
                        }
                    }
                } catch (Exception e) {
                    broadcastDebug("[tts-error] " + e.getMessage());
                    Log.e(TAG, "TTS number error", e);
                } finally {
                    currentSpeechText = "";
                    sleepQuietly(200);
                    speaking = false;
                    lastCommandAt = SystemClock.elapsedRealtime();
                }
            }
            if (skipped) broadcastDebug("[tts] 已挂断，跳过本轮播报");
            if (onDone != null) onDone.run();
        }).start();
    }

    /** 整句合成一个号码（“一，八，五，…，来电话”），并放进缓存。 */
    private void prewarmNumberAudio(String number) {
        final String digits = number == null ? "" : number.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return;
        if (cachedNumberAudio(digits) != null) return;
        OfflineTts currentTts = tts;
        if (!ttsReady || currentTts == null) return;
        if (destroyed) return;
        try {
            synchronized (ttsLock) {
                if (cachedNumberAudio(digits) != null || destroyed) return;
                GeneratedAudio audio = currentTts.generate(buildNumberSentence(digits), 0, DIGIT_SPEED);
                if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                    putNumberAudio(digits, audio.getSamples());
                    broadcastDebug("[tts] 号码整句合成完成（"
                            + String.format(java.util.Locale.US, "%.1f", audio.getSamples().length
                            / (float) audio.getSampleRate()) + "s）");
                }
            }
        } catch (Exception e) {
            broadcastDebug("[tts] 号码整句合成失败: " + e.getMessage());
            Log.w(TAG, "prewarmNumberAudio failed", e);
        }
    }

    /** 等整句音频出现在缓存里，超时返回 null。等待期间可以被打断。 */
    private float[] waitForNumberAudio(String digits, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline && !destroyed && !abortSpeech) {
            float[] audio = cachedNumberAudio(digits);
            if (audio != null) return audio;
            sleepQuietly(50);
        }
        return cachedNumberAudio(digits);
    }

    private float[] cachedNumberAudio(String digits) {
        synchronized (numberAudioCache) {
            return numberAudioCache.get(digits);
        }
    }

    private void putNumberAudio(String digits, float[] audio) {
        synchronized (numberAudioCache) {
            numberAudioCache.put(digits, audio);
        }
    }

    /** “一，八，五，六，零，一，八，一，一，四，五，来电话”，按 3-4-4 分组。 */
    private static String buildNumberSentence(String digits) {
        String[] table = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) sb.append('，');
            sb.append(table[digits.charAt(i) - '0']);
        }
        sb.append("，来电话");
        return sb.toString();
    }

    private float[] buildNumberAudio(String digits, OfflineTts currentTts) {
        String[] table = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

        if (digitCacheReady) {
            java.util.List<float[]> parts = new java.util.ArrayList<>();
            int gap = Math.max(1, ttsCacheRate * DIGIT_GAP_MS / 1000);
            int groupGap = Math.max(1, ttsCacheRate * DIGIT_GROUP_GAP_MS / 1000);
            int lead = Math.max(1, ttsCacheRate * 150 / 1000);
            int total = digits.length();
            for (int i = 0; i < total; i++) {
                float[] one = digitAudioCache.get(table[digits.charAt(i) - '0'].charAt(0));
                if (one == null) {
                    digitCacheReady = false;
                    break;
                }
                parts.add(one);
                if (i < total - 1) {
                    parts.add(new float[groupBreakAfter(i, total) ? groupGap : gap]);
                }
            }
            if (digitCacheReady && callTailAudio != null) {
                parts.add(new float[lead]);
                parts.add(callTailAudio);
                return concat(parts);
            }
            parts.clear();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) sb.append('，');
            sb.append(table[digits.charAt(i) - '0']);
        }
        sb.append("，来电话");
        GeneratedAudio audio = currentTts.generate(sb.toString(), 0, DIGIT_SPEED);
        return audio != null ? audio.getSamples() : null;
    }

    /** 11 位手机号按 3-4-4 分组，其余每 4 位一组。 */
    private static boolean groupBreakAfter(int index, int total) {
        if (total == 11) return index == 2 || index == 6;
        return (index + 1) % 4 == 0 && index < total - 1;
    }

    /** 服务启动后把 0-9 和“来电话”预先合成好，来电时拼接即播。 */
    private void buildDigitAudioCache() {
        OfflineTts currentTts = tts;
        if (currentTts == null || !ttsReady) return;

        String[] table = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        try {
            for (String digit : table) {
                if (destroyed || tts != currentTts) return;
                float[] samples = null;
                int rate = ttsCacheRate;
                synchronized (ttsLock) {
                    // 带一个逗号一起合成：模型会用完整的降调收尾，
                    // 单独念一个字会被截得很短，拼起来就像“喘不上气”
                    GeneratedAudio audio = currentTts.generate(digit + "，", 0, DIGIT_SPEED);
                    if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                        rate = audio.getSampleRate();
                        samples = prepareClip(audio.getSamples(), rate);
                    }
                }
                if (samples != null) {
                    digitAudioCache.put(digit.charAt(0), samples);
                    ttsCacheRate = rate;
                }
            }
            float[] tail = null;
            synchronized (ttsLock) {
                GeneratedAudio audio = currentTts.generate("来电话", 0, 1.0f);
                if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                    tail = prepareClip(audio.getSamples(), audio.getSampleRate());
                }
            }
            if (tail != null) callTailAudio = tail;
            digitCacheReady = digitAudioCache.size() >= 10 && callTailAudio != null;
            broadcastDebug("[tts] 号码读音缓存" + (digitCacheReady ? "就绪" : "不完整")
                    + "（" + digitAudioCache.size() + " 个数字）");
        } catch (Exception e) {
            broadcastDebug("[tts] 号码读音缓存失败: " + e.getMessage());
            Log.e(TAG, "buildDigitAudioCache failed", e);
        }
    }

    /** 裁掉首尾多余静音、加淡入淡出、统一响度，拼接时就不会一顿一顿的。 */
    private static float[] prepareClip(float[] samples, int rate) {
        float[] trimmed = trimSilence(samples, 0.03f, 30, 50, rate);
        if (trimmed == null || trimmed.length == 0) return samples;
        applyFade(trimmed, rate * 8 / 1000, rate * 12 / 1000);
        return normalizeLoudness(trimmed, 0.085f);
    }

    private static float[] trimSilence(float[] samples, float ratio, int headMs, int tailMs, int rate) {
        if (samples == null || samples.length == 0) return samples;
        float peak = 0f;
        for (float v : samples) peak = Math.max(peak, Math.abs(v));
        if (peak < 0.0001f) return samples;
        float threshold = peak * ratio;
        int start = 0;
        while (start < samples.length && Math.abs(samples[start]) < threshold) start++;
        int end = samples.length - 1;
        while (end > start && Math.abs(samples[end]) < threshold) end--;
        int from = Math.max(0, start - rate * headMs / 1000);
        int to = Math.min(samples.length, end + 1 + rate * tailMs / 1000);
        if (to <= from) return samples;
        return java.util.Arrays.copyOfRange(samples, from, to);
    }

    private static void applyFade(float[] samples, int fadeIn, int fadeOut) {
        int in = Math.min(fadeIn, samples.length / 2);
        for (int i = 0; i < in; i++) {
            samples[i] *= (float) i / in;
        }
        int out = Math.min(fadeOut, samples.length / 2);
        for (int i = 0; i < out; i++) {
            samples[samples.length - 1 - i] *= (float) i / out;
        }
    }

    private static float[] normalizeLoudness(float[] samples, float targetRms) {
        double sum = 0;
        float peak = 0f;
        for (float v : samples) {
            sum += (double) v * v;
            peak = Math.max(peak, Math.abs(v));
        }
        if (peak < 0.0001f) return samples;
        float rms = (float) Math.sqrt(sum / samples.length);
        float gain = rms > 0.0001f ? targetRms / rms : 1f;
        gain = Math.min(gain, 4f);
        if (peak * gain > 0.95f) gain = 0.95f / peak;
        float[] out = new float[samples.length];
        for (int i = 0; i < samples.length; i++) out[i] = samples[i] * gain;
        return out;
    }

    private static float[] concat(java.util.List<float[]> parts) {
        int total = 0;
        for (float[] part : parts) total += part.length;
        float[] merged = new float[total];
        int offset = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, merged, offset, part.length);
            offset += part.length;
        }
        return merged;
    }

    private void logCallPermissions() {
        broadcastDebug("[来电] 权限 联系人=" + granted(Manifest.permission.READ_CONTACTS)
                + " 通话记录=" + granted(Manifest.permission.READ_CALL_LOG)
                + " 接听=" + granted(Manifest.permission.ANSWER_PHONE_CALLS)
                + " 来电筛选=" + (IncomingCallScreeningService.isEnabled(this) ? "已开启" : "未开启")
                + " 通知使用权=" + (CallNotificationListener.isEnabled(this) ? "已开启" : "未开启"));
        if (PhoneCallHelper.hasPermission(this, Manifest.permission.READ_CONTACTS)) {
            broadcastDebug("[来电] " + ContactsIndex.describe(this));
        }
    }

    private String granted(String permission) {
        return PhoneCallHelper.hasPermission(this, permission) ? "已授权" : "未授权";
    }

    private void answerIncomingCall() {
        if (callState != TelephonyManager.CALL_STATE_RINGING) {
            broadcastDebug("[来电] 当前没有响铃的来电，忽略接听");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - answerFailedAt < 5000L) {
            broadcastDebug("[来电] 刚才接听失败了，请手动接听");
            return;
        }
        stopRingAnnouncements();
        answering = true;
        broadcastDebug("[来电] 执行接听...");
        new Thread(() -> {
            try {
                PhoneCallHelper.answerCall(this, (success, message) -> {
                    broadcastDebug("[来电] " + message);
                    if (success || !isPhoneStillRinging()) return;
                    answerFailedAt = SystemClock.elapsedRealtime();
                    // 只提示一次，避免一直重复；电话已经结束时不再出声
                    if (!answerFailureReported) {
                        answerFailureReported = true;
                        speak("无法接听，请手动接听");
                    }
                    handler.postDelayed(ringAnnouncer, 1500L);
                });
            } finally {
                answering = false;
            }
        }, "answer-call").start();
    }

    private void hangUpCall() {
        if (callState == TelephonyManager.CALL_STATE_IDLE) {
            broadcastDebug("[来电] 当前没有通话，忽略挂断");
            return;
        }
        stopRingAnnouncements();
        answering = true;
        broadcastDebug("[来电] 执行挂断...");
        new Thread(() -> {
            try {
                PhoneCallHelper.endCall(this, (success, message) -> {
                    broadcastDebug("[来电] " + message);
                    if (!success) speak("无法挂断，请手动挂断");
                });
            } finally {
                answering = false;
            }
        }, "hangup-call").start();
    }

    private void notifyCallState(String state, String text) {
        Intent intent = new Intent(ACTION_CALL);
        intent.putExtra(EXTRA_CALL_STATE, state);
        intent.putExtra(EXTRA_CALL_TEXT, text);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ========== File Operations ==========

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private void copyAssetFolder(String assetFolder, String destPath) throws IOException {
        new File(destPath).mkdirs();
        String[] files = getAssets().list(assetFolder);
        if (files == null) return;

        for (String fileName : files) {
            String assetPath = assetFolder + "/" + fileName;
            String destFilePath = destPath + "/" + fileName;
            String[] subFiles = getAssets().list(assetPath);
            if (subFiles != null && subFiles.length > 0) {
                copyAssetFolder(assetPath, destFilePath);
            } else {
                copyAssetFile(assetPath, destFilePath);
            }
        }
    }

    private void copyAssetFile(String assetPath, String destPath) throws IOException {
        InputStream in = getAssets().open(assetPath);
        FileOutputStream out = new FileOutputStream(destPath);
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.close();
    }

    // ========== Broadcast & Notification ==========

    private void broadcastStatus(String status) {
        currentStatus = status;
        if (!STATUS_LOADING.equals(status)) loadingDetail = "";
        ServiceState.save(this, true, status, lastError, loadingDetail);

        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_ERROR, lastError);
        intent.putExtra(EXTRA_DETAIL, loadingDetail);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        updateNotification(status);
    }

    private void setLoadingDetail(String detail) {
        loadingDetail = detail;
        ServiceState.save(this, true, currentStatus, lastError, detail);

        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, currentStatus);
        intent.putExtra(EXTRA_ERROR, lastError);
        intent.putExtra(EXTRA_DETAIL, detail);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        updateNotification(currentStatus);
    }

    private void broadcastDebug(String message) {
        LogStore.append(message);
        Intent intent = new Intent(ACTION_DEBUG);
        intent.putExtra(EXTRA_DEBUG, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, message);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏按钮发来的接听/挂断请求
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_ANSWER_CALL.equals(action)) {
                answerIncomingCall();
            } else if (ACTION_HANG_UP_CALL.equals(action)) {
                hangUpCall();
            }
        }
        // 前台通知已经在 onCreate 中启动，这里只需要保证进程被系统回收后能自动恢复
        return START_STICKY;
    }

    private void updateNotification(String status) {
        String text;
        switch (status) {
            case STATUS_LOADING:
                text = loadingDetail.isEmpty() ? "正在准备离线模型..." : loadingDetail;
                break;
            case STATUS_READY:
            case STATUS_LISTENING:
                text = "监听中... 可说“" + AppSettings.getTimeQuery(this) + "”查时间";
                break;
            case STATUS_ERROR:
                text = "错误: " + lastError;
                break;
            case STATUS_STOPPED:
                text = "已停止";
                break;
            default:
                text = "运行中";
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("语音助手运行中")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        // 语音指令万一没识别到，通知栏上也能接听/挂断
        if (callState == TelephonyManager.CALL_STATE_RINGING) {
            builder.addAction(0, "接听", callActionIntent(ACTION_ANSWER_CALL, 1));
            builder.addAction(0, "挂断", callActionIntent(ACTION_HANG_UP_CALL, 2));
        } else if (callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            builder.addAction(0, "挂断", callActionIntent(ACTION_HANG_UP_CALL, 2));
        }
        return builder.build();
    }

    private PendingIntent callActionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, VoiceService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "语音助手", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    // ========== Lifecycle ==========

    @Override
    public void onDestroy() {
        destroyed = true;
        isListening = false;
        stopRingAnnouncements();
        unregisterPhoneStateListener();
        modelReady = false;
        ttsReady = false;

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusRequestReceiver);
        } catch (Exception e) { /* ignore */ }

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception e) { /* ignore */ }
            audioRecord.release();
            audioRecord = null;
        }
        if (recognitionThread != null) {
            recognitionThread.interrupt();
            recognitionThread = null;
        }
        if (initThread != null) {
            initThread.interrupt();
            initThread = null;
        }
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        if (voskModel != null) { voskModel.close(); voskModel = null; }
        if (tts != null) { tts.release(); tts = null; }
        abandonAudioFocus();
        releaseWakeLock();
        handler.removeCallbacksAndMessages(null);

        if (instance == this) instance = null;
        ServiceState.save(this, false, STATUS_STOPPED, "", "");
        Intent stopped = new Intent(ACTION_STATUS);
        stopped.putExtra(EXTRA_STATUS, STATUS_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(stopped);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return new android.os.Binder(); }

    /** 服务实例是否真的存在于当前进程里。 */
    static boolean isAlive() {
        return instance != null;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }
}
