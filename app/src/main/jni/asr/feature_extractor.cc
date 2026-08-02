// Streaming fbank feature extractor (kaldi-native-fbank).
#include "feature_extractor.h"

namespace xime_asr {

FeatureExtractor::FeatureExtractor() {
  knf::FbankOptions opts;
  opts.frame_opts.samp_freq = 16000;
  opts.frame_opts.frame_shift_ms = 10;
  opts.frame_opts.frame_length_ms = 25;
  opts.frame_opts.dither = 0;
  opts.frame_opts.snip_edges = false;
  opts.frame_opts.window_type = "povey";
  opts.mel_opts.num_bins = 80;
  opts.mel_opts.low_freq = 20;
  // Negative high_freq = offset from Nyquist (KNF), matching sherpa/icefall.
  opts.mel_opts.high_freq = -400;
  fbank_ = std::make_unique<knf::OnlineFbank>(opts);
}

void FeatureExtractor::AcceptWaveform(const float *samples, int32_t n) {
  fbank_->AcceptWaveform(16000, samples, n);
}

void FeatureExtractor::InputFinished() { fbank_->InputFinished(); }

int32_t FeatureExtractor::NumFramesReady() const {
  return fbank_->NumFramesReady();
}

const float *FeatureExtractor::GetFrame(int32_t frame) const {
  return fbank_->GetFrame(frame);
}

void FeatureExtractor::Pop(int32_t n) { fbank_->Pop(n); }

int32_t FeatureExtractor::FeatureDim() const { return fbank_->Dim(); }

}  // namespace xime_asr
