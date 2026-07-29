#include "onnx_env.h"
#include <android/log.h>
#include <mutex>
#include <dlfcn.h>

static OrtEnv* g_shared_env = nullptr;
static std::mutex g_env_mutex;
static bool g_env_created = false;

#define LOG_TAG "OnnxEnv"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const OrtApi* OnnxGetApi() {
    static const OrtApi* api = nullptr;
    if (!api) {
        api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        if (!api) {
            LOGE("Failed to get ONNX Runtime API");
        }
    }
    return api;
}

OrtEnv* OnnxGetSharedEnv() {
    std::lock_guard<std::mutex> lock(g_env_mutex);
    if (!g_shared_env) {
        const OrtApi* api = OnnxGetApi();
        if (api) {
            OrtStatus* status = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "xime_onnx", &g_shared_env);
            if (status) {
                LOGE("Failed to create shared env: %s", api->GetErrorMessage(status));
                api->ReleaseStatus(status);
            } else {
                g_env_created = true;
                LOGD("Onnx shared env created");
            }
        }
    }
    return g_shared_env;
}

void OnnxReleaseSharedEnv() {
    std::lock_guard<std::mutex> lock(g_env_mutex);
    if (g_shared_env) {
        const OrtApi* api = OnnxGetApi();
        if (api) {
            api->ReleaseEnv(g_shared_env);
            g_shared_env = nullptr;
            g_env_created = false;
            LOGD("Onnx shared env released");
        }
    }
}

bool OnnxTryEnableNnapi(OrtSessionOptions* options) {
    const OrtApi* api = OnnxGetApi();
    if (!api || !options) return false;

    typedef OrtStatus* (*NnapiProviderFn)(OrtSessionOptions*, uint32_t);
    NnapiProviderFn nnapi_fn = (NnapiProviderFn)dlsym(
        RTLD_DEFAULT, "OrtSessionOptionsAppendExecutionProvider_Nnapi");

    if (!nnapi_fn) {
        LOGD("NNAPI EP not available in this libonnxruntime.so (CPU-only build)");
        return false;
    }

    uint32_t nnapi_flags = 0;
    nnapi_flags |= 0x001;  // NNAPI_FLAG_USE_FP16

    OrtStatus* status = nnapi_fn(options, nnapi_flags);
    if (status) {
        LOGW("NNAPI EP initialization failed: %s", api->GetErrorMessage(status));
        api->ReleaseStatus(status);
        return false;
    }

    LOGD("NNAPI execution provider enabled successfully");
    return true;
}
