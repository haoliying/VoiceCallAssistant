package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class OfflineTts {
    private long ptr = 0;

    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    public OfflineTts(OfflineTtsConfig config) {
        ptr = newFromFile(config);
        if (ptr == 0) {
            throw new IllegalArgumentException("Invalid OfflineTtsConfig: failed to create native OfflineTts");
        }
    }

    public OfflineTts(AssetManager assetManager, OfflineTtsConfig config) {
        ptr = newFromAsset(assetManager, config);
        if (ptr == 0) {
            throw new IllegalArgumentException("Invalid OfflineTtsConfig: failed to create native OfflineTts from asset");
        }
    }

    public int getSampleRate() {
        return getSampleRate(ptr);
    }

    public int getNumSpeakers() {
        return getNumSpeakers(ptr);
    }

    public String getLang() {
        return getLang(ptr);
    }

    public GeneratedAudio generate(String text) {
        return generate(text, 0, 1.0f);
    }

    public GeneratedAudio generate(String text, int sid) {
        return generate(text, sid, 1.0f);
    }

    public GeneratedAudio generate(String text, int sid, float speed) {
        return generateImpl(ptr, text, sid, speed);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    private native long newFromAsset(AssetManager assetManager, OfflineTtsConfig config);
    private native long newFromFile(OfflineTtsConfig config);
    private native void delete(long ptr);
    private native int getSampleRate(long ptr);
    private native int getNumSpeakers(long ptr);
    private native String getLang(long ptr);
    private native GeneratedAudio generateImpl(long ptr, String text, int sid, float speed);
}
