#pragma once
#include <onnxruntime_c_api.h>
#include <stdint.h>

const OrtApi* OnnxGetApi();
OrtEnv* OnnxGetSharedEnv();
void OnnxReleaseSharedEnv();

// Try to enable NNAPI execution provider via dlsym.
// Returns true if NNAPI was enabled, false if not available (CPU fallback).
bool OnnxTryEnableNnapi(OrtSessionOptions* options);
