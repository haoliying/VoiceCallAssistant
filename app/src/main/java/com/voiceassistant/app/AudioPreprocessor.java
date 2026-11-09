package com.voiceassistant.app;

/**
 * 送进识别引擎之前的轻量降噪/增益。
 *
 * 手机自带的 NoiseSuppressor / AEC 在国产 ROM 上经常不可用，所以这里再补一层纯软件的
 * 处理，全部在 16kHz 单声道 16bit 上做：
 *   1. 去直流 + 110Hz 高通，压掉空调、发动机、风噪这类低频轰鸣；
 *   2. 自适应噪声门：估计环境噪声底，比噪声底还小的帧只衰减不掐断，
 *      避免把气声、轻声的起头也削掉；
 *   3. 慢速 AGC：把说话声拉到固定电平，离手机远一点也能识别；
 *   4. 软限幅，防止增益过大产生削波。
 *
 * 全部是逐帧原地处理，不额外分配内存，可以放在录音线程里跑。
 */
final class AudioPreprocessor {

    /** 16bit 满幅是 32767，目标说话电平取约 0.07 满幅。 */
    private static final float TARGET_RMS = 2200f;
    private static final float MAX_GAIN = 5.0f;
    private static final float MIN_NOISE_FLOOR = 60f;
    /** 低于“噪声底 × 该倍数”的帧认为是噪声，只衰减不静音。 */
    private static final float GATE_RATIO = 2.0f;
    private static final float GATE_ATTENUATION = 0.35f;

    private final float hpB0;
    private final float hpB1;
    private final float hpB2;
    private final float hpA1;
    private final float hpA2;

    private float x1, x2, y1, y2;
    private float dcIn, dcOut;
    private float noiseFloor = 300f;
    private float gateGain = 1f;
    private float agcGain = 1f;
    /** 系统已经挂了自动增益时就不用再叠一层，避免双重放大造成忽大忽小。 */
    private final boolean softwareAgc;

    AudioPreprocessor(int sampleRate, boolean softwareAgc) {
        this.softwareAgc = softwareAgc;
        // RBJ 二阶高通，fc = 110Hz，Q = 0.707
        double fc = 110.0;
        double w0 = 2.0 * Math.PI * fc / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * 0.707);
        double a0 = 1.0 + alpha;
        hpB0 = (float) ((1.0 + cos) / 2.0 / a0);
        hpB1 = (float) (-(1.0 + cos) / a0);
        hpB2 = (float) ((1.0 + cos) / 2.0 / a0);
        hpA1 = (float) (-2.0 * cos / a0);
        hpA2 = (float) ((1.0 - alpha) / a0);
    }

    /** 原地处理 len 个采样点。 */
    void process(short[] data, int len) {
        if (len <= 0) return;

        double sumSquares = 0;
        for (int i = 0; i < len; i++) {
            float x = data[i];
            // 去直流
            dcOut = x - dcIn + 0.995f * dcOut;
            dcIn = x;
            // 高通
            float y = hpB0 * dcOut + hpB1 * x1 + hpB2 * x2 - hpA1 * y1 - hpA2 * y2;
            x2 = x1;
            x1 = dcOut;
            y2 = y1;
            y1 = y;
            data[i] = (short) clamp(y);
            sumSquares += (double) y * y;
        }

        float rms = (float) Math.sqrt(sumSquares / len);
        updateNoiseFloor(rms);
        updateGate(rms);
        updateAgc(rms * gateGain);

        float gain = gateGain * agcGain;
        for (int i = 0; i < len; i++) {
            data[i] = (short) clamp(data[i] * gain);
        }
    }

    /** 噪声底只在“安静帧”上更新，说话时会停住，避免把人声当噪声学进去。 */
    private void updateNoiseFloor(float rms) {
        if (rms < noiseFloor * 1.6f) {
            noiseFloor = 0.97f * noiseFloor + 0.03f * rms;
        } else {
            noiseFloor = 0.999f * noiseFloor + 0.001f * rms;
        }
        if (noiseFloor < MIN_NOISE_FLOOR) noiseFloor = MIN_NOISE_FLOOR;
    }

    private void updateGate(float rms) {
        float target = rms > noiseFloor * GATE_RATIO ? 1f : GATE_ATTENUATION;
        // 平滑，避免门一开一关出现“抽气”声
        gateGain += (target - gateGain) * 0.25f;
    }

    private void updateAgc(float rms) {
        if (!softwareAgc) {
            agcGain = 1f;
            return;
        }
        float target = 1f;
        if (rms > 200f) {
            target = TARGET_RMS / rms;
            if (target > MAX_GAIN) target = MAX_GAIN;
            if (target < 1f) target = 1f;
        }
        // 增益上升要快（一句话只有一两秒，慢了前半句还是小声），
        // 下降更快一些，避免突然的响声被放大
        float rate = target < agcGain ? 0.40f : 0.20f;
        agcGain += (target - agcGain) * rate;
    }

    private static float clamp(float value) {
        if (value > 32767f) return 32767f;
        if (value < -32768f) return -32768f;
        return value;
    }
}
