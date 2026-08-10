// Streaming zipformer2 ASR recognizer (greedy search).
#include "streaming_recognizer.h"

#include <android/log.h>

#include <algorithm>
#include <fstream>
#include <sstream>

#define LOG_TAG "StreamingRecognizer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace xime_asr {

namespace {

// Load id -> symbol map from a sherpa/icefall tokens.txt. Each line is
// "<symbol> <id>".
std::vector<std::string> LoadSymbolTable(const std::string &path) {
  std::vector<std::string> id2sym;
  std::ifstream in(path);
  if (!in) {
    LOGE("Failed to open tokens file: %s", path.c_str());
    return id2sym;
  }
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty()) continue;
    std::istringstream ss(line);
    std::string sym;
    int64_t id = -1;
    if (ss >> sym >> id && id >= 0) {
      if (static_cast<size_t>(id) >= id2sym.size()) id2sym.resize(id + 1);
      id2sym[id] = sym;
    }
  }
  return id2sym;
}

}  // namespace

StreamingRecognizer::StreamingRecognizer(const AsrModelPaths &paths,
                                         const std::string &tokens_path,
                                         int32_t num_threads)
    : decoder_out_(nullptr) {
  try {
    model_ = std::make_unique<Zipformer2Model>(paths, num_threads);
    id2sym_ = LoadSymbolTable(tokens_path);
    chunk_size_ = model_->ChunkSize();
    chunk_shift_ = model_->ChunkShift();
    context_size_ = model_->ContextSize();
    feature_dim_ = 80;
    use_whisper_feature_ = model_->UseWhisperFeature();
    loaded_ = true;
  } catch (const std::exception &e) {
    LOGE("StreamingRecognizer init failed: %s", e.what());
    loaded_ = false;
  }
}

void StreamingRecognizer::Reset() {
  feat_ = std::make_unique<FeatureExtractor>(use_whisper_feature_
                                                 ? FeatureExtractor::Type::kWhisper
                                                 : FeatureExtractor::Type::kFbank);
  feat_->AcceptWaveform(nullptr, 0);  // no-op, ensure init
  model_->SetFeatureDim(feature_dim_);
  states_ = model_->GetEncoderInitStates();

  tokens_.assign(context_size_, 0);  // initial context: all blank (icefall)
  decoder_out_ = Ort::Value{nullptr};
  has_decoder_out_ = false;
  processed_frames_ = 0;
}

int64_t StreamingRecognizer::UnkId() const {
  for (size_t i = 0; i < id2sym_.size(); ++i) {
    if (id2sym_[i] == "<unk>") return static_cast<int64_t>(i);
  }
  return 1;
}

std::string StreamingRecognizer::AcceptPcm(const float *samples, int32_t n) {
  if (!loaded_ || !feat_) return std::string();
  feat_->AcceptWaveform(samples, n);
  DecodeAvailableChunks();
  return ConvertTokens();
}

std::string StreamingRecognizer::Finalize() {
  if (!loaded_ || !feat_) return std::string();
  feat_->InputFinished();
  DecodeAvailableChunks();
  return ConvertTokens();
}

std::string StreamingRecognizer::GetPartialText() const {
  return ConvertTokens();
}

void StreamingRecognizer::DecodeAvailableChunks() {
  if (!model_ || !feat_) return;
  while (processed_frames_ + chunk_size_ <= feat_->NumFramesReady()) {
    DecodeOneChunk();
    processed_frames_ += chunk_shift_;
  }
}

void StreamingRecognizer::DecodeOneChunk() {
  // Build (1, chunk_size, feature_dim) input from the feature buffer at the
  // current processed frame offset.
  const int32_t dim = feature_dim_;
  const std::vector<float>::size_type x_size =
      static_cast<std::vector<float>::size_type>(chunk_size_) *
      static_cast<std::vector<float>::size_type>(dim);
  std::vector<float> x(x_size);
  if (use_whisper_feature_) {
    // whisper features are linear mel power; first compute log10(clip(v,
    // 1e-10)) across the whole chunk to get the dynamic-range floor, then
    // normalize each frame: (log10 + 4) / 4, clamped to (max - 8).
    float max_log = -1e30f;
    for (int32_t j = 0; j < chunk_size_; ++j) {
      const float *f = feat_->GetFrame(processed_frames_ + j);
      for (int32_t k = 0; k < dim; ++k) {
        float v = FeatureExtractor::WhisperLog(f[k]);
        x[j * dim + k] = v;
        if (v > max_log) max_log = v;
      }
    }
    const float floor_log = max_log - 8.0f;
    for (int32_t j = 0; j < chunk_size_; ++j) {
      float *dst = x.data() + j * dim;
      for (int32_t k = 0; k < dim; ++k) {
        if (dst[k] < floor_log) dst[k] = floor_log;
        dst[k] = (dst[k] + 4.0f) / 4.0f;
      }
    }
  } else {
    for (int32_t j = 0; j < chunk_size_; ++j) {
      const float *f = feat_->GetFrame(processed_frames_ + j);
      std::copy(f, f + dim, x.data() + j * dim);
    }
  }
  std::array<int64_t, 3> x_shape{1, chunk_size_, dim};
  auto mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
  Ort::Value x_ort = Ort::Value::CreateTensor(
      mem, x.data(), x.size(), x_shape.data(), x_shape.size());

  // sherpa passes a dummy value here; zipformer2 ignores processed_frames.
  std::array<int64_t, 1> pf_shape{1};
  int64_t pf = 0;
  Ort::Value pf_ort =
      Ort::Value::CreateTensor(mem, &pf, 1, pf_shape.data(), pf_shape.size());

  auto pair = model_->RunEncoder(std::move(x_ort), std::move(states_),
                                 std::move(pf_ort));
  states_ = std::move(pair.second);
  Ort::Value encoder_out = std::move(pair.first);

  // Greedy decode.
  if (!has_decoder_out_) {
    decoder_out_ = model_->RunDecoder(model_->BuildDecoderInput({tokens_}));
    has_decoder_out_ = true;
  }

  auto shape = encoder_out.GetTensorTypeAndShapeInfo().GetShape();
  const int32_t num_frames = static_cast<int32_t>(shape[1]);
  const int32_t joiner_dim = static_cast<int32_t>(shape[2]);
  const int64_t vocab_size = model_->VocabSize();
  const int64_t unk_id = UnkId();

  auto dec_shape = decoder_out_.GetTensorTypeAndShapeInfo().GetShape();
  const int64_t dec_dim = dec_shape[1];

  const float *enc_data = encoder_out.GetTensorData<float>();
  auto mem2 = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
  for (int32_t t = 0; t < num_frames; ++t) {
    std::array<int64_t, 2> f_shape{1, joiner_dim};
    Ort::Value cur_enc = Ort::Value::CreateTensor<float>(
        mem2, const_cast<float *>(enc_data + t * joiner_dim), joiner_dim,
        f_shape.data(), f_shape.size());

    // Re-fetch decoder_out each frame: it is replaced whenever a token is
    // emitted, so caching the pointer would leave a dangling reference.
    const float *dec_data = decoder_out_.GetTensorData<float>();
    std::array<int64_t, 2> d_shape{1, dec_dim};
    Ort::Value dec_view = Ort::Value::CreateTensor<float>(
        mem2, const_cast<float *>(dec_data), dec_dim, d_shape.data(),
        d_shape.size());

    Ort::Value logit =
        model_->RunJoiner(std::move(cur_enc), std::move(dec_view));
    const float *p = logit.GetTensorData<float>();
    int64_t y = 0;
    float best = p[0];
    for (int64_t i = 1; i < vocab_size; ++i) {
      if (p[i] > best) {
        best = p[i];
        y = i;
      }
    }
    if (y != 0 && y != unk_id) {
      tokens_.push_back(y);
      decoder_out_ = model_->RunDecoder(model_->BuildDecoderInput({tokens_}));
    }
  }
}

std::string StreamingRecognizer::ConvertTokens() const {
  std::string text;
  size_t start = static_cast<size_t>(context_size_);
  if (tokens_.size() <= start) return text;
  for (size_t i = start; i < tokens_.size(); ++i) {
    int64_t id = tokens_[i];
    if (id < 0 || static_cast<size_t>(id) >= id2sym_.size()) continue;
    const std::string &s = id2sym_[static_cast<size_t>(id)];
    if (s.empty() || s == "<blk>" || s == "<unk>") continue;
    std::string out = s;
    // Strip BPE word-boundary marker '▁' (U+2581, 3 UTF-8 bytes).
    for (size_t p = out.find("\xE2\x96\x81"); p != std::string::npos;
         p = out.find("\xE2\x96\x81")) {
      out.erase(p, 3);
    }
    text += out;
  }
  return text;
}

}  // namespace xime_asr
