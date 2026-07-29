package com.kingzcheung.xime.service;

oneway interface IInferenceCallback {
    void onPartialResult(String modelId, String text);
    void onFinalResult(String modelId, String text);
    void onError(String modelId, String message);
}
