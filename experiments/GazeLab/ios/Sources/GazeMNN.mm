#import "GazeMNN.h"
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>

@implementation GazeMNN {
    std::unique_ptr<MNN::Interpreter> _interpreter;
    MNN::Session *_session;
    std::unordered_map<std::string, std::unique_ptr<MNN::Tensor>> _inputHosts;
    std::unordered_map<std::string, std::unique_ptr<MNN::Tensor>> _outputHosts;
}

- (nullable instancetype)initWithModelPath:(NSString *)path {
    self = [super init];
    if (!self) { return nil; }
    _interpreter.reset(MNN::Interpreter::createFromFile(path.fileSystemRepresentation));
    if (!_interpreter) { return nil; }

    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 4;
    MNN::BackendConfig backendConfig;
    // 启用 ARMv8.2 FP16 SIMD 加速，显著降低五模型并发延迟。
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
    backendConfig.power = MNN::BackendConfig::Power_High;
    config.backendConfig = &backendConfig;

    _session = _interpreter->createSession(config);
    if (!_session) { return nil; }

    // 预分配并复用 host Tensor，避免每帧堆分配触发内存抖动。
    for (const auto &entry : _interpreter->getSessionInputAll(_session)) {
        auto *tensor = entry.second;
        if (tensor && tensor->getType() == halide_type_of<float>()) {
            _inputHosts[entry.first] = std::make_unique<MNN::Tensor>(tensor, tensor->getDimensionType());
        }
    }
    for (const auto &entry : _interpreter->getSessionOutputAll(_session)) {
        auto *tensor = entry.second;
        if (tensor && tensor->getType() == halide_type_of<float>()) {
            _outputHosts[entry.first] = std::make_unique<MNN::Tensor>(tensor, tensor->getDimensionType());
        }
    }
    return self;
}

- (void)dealloc {
    _inputHosts.clear();
    _outputHosts.clear();
    if (_session) { _interpreter->releaseSession(_session); }
}

- (nullable NSData *)predict:(NSDictionary<NSString *, NSData *> *)inputs output:(NSString *)output {
    const auto &modelInputs = _interpreter->getSessionInputAll(_session);
    if (inputs.count != modelInputs.size()) { return nil; }
    for (const auto &entry : modelInputs) {
        NSData *bytes = inputs[[NSString stringWithUTF8String:entry.first.c_str()]];
        auto *tensor = entry.second;
        auto it = _inputHosts.find(entry.first);
        if (!bytes || !tensor || it == _inputHosts.end() || !it->second ||
            !it->second->host<float>() || tensor->getType() != halide_type_of<float>() ||
            bytes.length != size_t(it->second->elementSize()) * sizeof(float)) {
            return nil;
        }
        std::memcpy(it->second->host<float>(), bytes.bytes, bytes.length);
        if (!tensor->copyFromHostTensor(it->second.get())) { return nil; }
    }
    if (_interpreter->runSession(_session) != MNN::NO_ERROR) { return nil; }
    return [self output:output];
}

- (nullable NSData *)output:(NSString *)name {
    const std::string key(name.UTF8String);
    auto *tensor = _interpreter->getSessionOutput(_session, key.c_str());
    if (!tensor || tensor->getType() != halide_type_of<float>()) { return nil; }
    auto it = _outputHosts.find(key);
    MNN::Tensor *host = nullptr;
    std::unique_ptr<MNN::Tensor> fallback;
    if (it != _outputHosts.end() && it->second && it->second->elementSize() == tensor->elementSize()) {
        host = it->second.get();
    } else {
        fallback = std::make_unique<MNN::Tensor>(tensor, tensor->getDimensionType());
        host = fallback.get();
    }
    if (!tensor->copyToHostTensor(host) || !host->host<float>()) { return nil; }
    return [NSData dataWithBytes:host->host<float>() length:size_t(host->elementSize()) * sizeof(float)];
}
@end
