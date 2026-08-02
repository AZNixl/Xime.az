#include <rime/common.h>
#include <rime/registry.h>
#include <rime_api.h>
#include "t9_processor.h"

using namespace rime;

namespace {

static void rime_t9_initialize() {
    LOG(INFO) << "registering components from module 't9'.";
    Registry& r = Registry::instance();
    r.Register("t9_processor", new Component<T9Processor>);
}

static void rime_t9_finalize() {
}

}  // namespace

RIME_REGISTER_MODULE(t9)
