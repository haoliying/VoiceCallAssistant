package com.k2fsa.sherpa.onnx;

public class GeneratedAudio {
    public float[] samples;
    public int sampleRate;

    public GeneratedAudio(float[] samples, int sampleRate) {
        this.samples = samples;
        this.sampleRate = sampleRate;
    }

    public float[] getSamples() {
        return samples;
    }

    public int getSampleRate() {
        return sampleRate;
    }
}
