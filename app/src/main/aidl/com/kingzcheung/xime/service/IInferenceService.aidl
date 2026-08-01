package com.kingzcheung.xime.service;

import com.kingzcheung.xime.service.IInferenceCallback;

interface IInferenceService {
    /** 加载模型 */
    boolean loadModel(String modelId, String modelPath, String extraPath);
    void unloadModel(String modelId);
    boolean isModelLoaded(String modelId);

    /** 智能联想预测 — 返回交替 [word, score, word, score, ...] */
    List<String> predict(String modelId, String text, int topK);

    /** ASR 流式语音识别 */
    boolean startAsr(String modelId, String modelDir, IInferenceCallback callback);
    oneway void pushAsrAudio(String modelId, in byte[] audioData);
    String stopAsr(String modelId);
    void cancelAsr(String modelId);

    /** 手写识别 — 返回交替 [index, score, index, score, ...] */
    List<String> recognizeHandwriting(String modelId, in float[] strokeData, in byte[] mask, int topK);

    /** 标点恢复 */
    String restorePunctuation(String modelId, String text);

    /** 语音前处理（AGC 等） */
    byte[] processAudioBytes(in byte[] input, int sampleRate);
}
