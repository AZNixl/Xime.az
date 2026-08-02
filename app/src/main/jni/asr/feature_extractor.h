// Streaming fbank feature extractor using kaldi-native-fbank (Apache-2.0).
//
// Matches the feature configuration used by the zipformer2 streaming model:
// 16 kHz, 80 mel bins, 10 ms frame shift, 25 ms frame length, povey window,
// snip_edges=false, no dither.
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "kaldi-native-fbank/csrc/online-feature.h"

namespace xime_asr {

class FeatureExtractor {
 public:
  FeatureExtractor();

  // Feed normalized float samples (in [-1, 1]) sampled at 16 kHz.
  void AcceptWaveform(const float *samples, int32_t n);
  void InputFinished();

  int32_t NumFramesReady() const;
  // Pointer to the feature frame at index `frame` (FeatureDim() floats).
  const float *GetFrame(int32_t frame) const;
  // Discard the first `n` frames.
  void Pop(int32_t n);

  int32_t FeatureDim() const;
  float FrameShiftInSeconds() const { return fbank_->FrameShiftInSeconds(); }

 private:
  std::unique_ptr<knf::OnlineFbank> fbank_;
};

}  // namespace xime_asr
