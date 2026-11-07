package com.k2fsa.sherpa.onnx;

public class OfflineTtsVitsModelConfig {
    public String model = "";
    public String lexicon = "";
    public String tokens = "";
    public String dataDir = "";
    public String dictDir = "";
    public float noiseScale = 0.667f;
    public float noiseScaleW = 0.8f;
    public float lengthScale = 1.0f;

    public OfflineTtsVitsModelConfig() {}

    public OfflineTtsVitsModelConfig(String model, String lexicon, String tokens,
                                      String dataDir, String dictDir,
                                      float noiseScale, float noiseScaleW, float lengthScale) {
        this.model = model;
        this.lexicon = lexicon;
        this.tokens = tokens;
        this.dataDir = dataDir;
        this.dictDir = dictDir;
        this.noiseScale = noiseScale;
        this.noiseScaleW = noiseScaleW;
        this.lengthScale = lengthScale;
    }
}
