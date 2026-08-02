// JNI bridge for the self-implemented streaming zipformer2 ASR.
//
// Reference algorithm: sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx),
// Apache-2.0. Feature extraction: kaldi-native-fbank (Apache-2.0).
#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <mutex>
#include <string>

#include "streaming_recognizer.h"

#define LOG_TAG "AsrJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using xime_asr::AsrModelPaths;
using xime_asr::StreamingRecognizer;

namespace {

struct RecognizerHolder {
  std::unique_ptr<StreamingRecognizer> rec;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeCreate(JNIEnv *env, jobject,
                                                        jstring jEncoder,
                                                        jstring jDecoder,
                                                        jstring jJoiner,
                                                        jstring jTokens) {
  const char *enc = env->GetStringUTFChars(jEncoder, nullptr);
  const char *dec = env->GetStringUTFChars(jDecoder, nullptr);
  const char *join = env->GetStringUTFChars(jJoiner, nullptr);
  const char *tok = env->GetStringUTFChars(jTokens, nullptr);

  AsrModelPaths paths;
  paths.encoder = enc;
  paths.decoder = dec;
  paths.joiner = join;
  std::string tokens = tok;

  env->ReleaseStringUTFChars(jEncoder, enc);
  env->ReleaseStringUTFChars(jDecoder, dec);
  env->ReleaseStringUTFChars(jJoiner, join);
  env->ReleaseStringUTFChars(jTokens, tok);

  auto *holder = new (std::nothrow) RecognizerHolder();
  if (!holder) return 0;
  holder->rec = std::make_unique<StreamingRecognizer>(paths, tokens);
  if (!holder->rec->LoadOk()) {
    LOGE("nativeCreate: failed to load ASR model");
    delete holder;
    return 0;
  }
  holder->rec->Reset();
  return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeReset(JNIEnv * /*env*/,
                                                       jobject, jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  if (holder && holder->rec) holder->rec->Reset();
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeAcceptPcm(JNIEnv *env,
                                                           jobject,
                                                           jlong handle,
                                                           jbyteArray pcm) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  if (!holder || !holder->rec) return;
  jsize n = env->GetArrayLength(pcm);
  if (n <= 0) return;
  jbyte *bytes = env->GetByteArrayElements(pcm, nullptr);
  constexpr float kInputGain = 3.0f;
  std::vector<float> samples(static_cast<size_t>(n / 2));
  for (jsize i = 0; i + 1 < n; i += 2) {
    int16_t s = static_cast<int16_t>(static_cast<uint8_t>(bytes[i]) |
                                     (static_cast<uint8_t>(bytes[i + 1]) << 8));
    float v = static_cast<float>(s) * kInputGain;
    if (v > 32767.0f) v = 32767.0f;
    else if (v < -32767.0f) v = -32767.0f;
    samples[static_cast<size_t>(i / 2)] = v;
  }
  env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
  holder->rec->AcceptPcm(samples.data(), static_cast<int32_t>(samples.size()));
}

JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeGetPartial(JNIEnv *env,
                                                            jobject,
                                                            jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  std::string text;
  if (holder && holder->rec) text = holder->rec->GetPartialText();
  return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeFinalize(JNIEnv *env, jobject,
                                                          jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  std::string text;
  if (holder && holder->rec) text = holder->rec->Finalize();
  return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_speech_AsrNative_nativeRelease(JNIEnv * /*env*/,
                                                         jobject, jlong handle) {
  auto *holder = reinterpret_cast<RecognizerHolder *>(handle);
  if (holder) delete holder;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /*vm*/, void * /*reserved*/) {
  return JNI_VERSION_1_6;
}

}  // extern "C"
