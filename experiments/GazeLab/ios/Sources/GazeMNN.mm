#import "GazeMNN.h"
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <cstring>
#include <memory>

@implementation GazeMNN {
    std::unique_ptr<MNN::Interpreter> _interpreter;
    MNN::Session *_session;
}

- (nullable instancetype)initWithModelPath:(NSString *)path {
    self = [super init];
    if (!self) { return nil; }
    _interpreter.reset(MNN::Interpreter::createFromFile(path.fileSystemRepresentation));
    if (!_interpreter) { return nil; }

    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 2;
    _session = _interpreter->createSession(config);
    return _session ? self : nil;
}

- (void)dealloc {
    if (_session) { _interpreter->releaseSession(_session); }
}

- (nullable NSData *)predict:(NSDictionary<NSString *, NSData *> *)inputs output:(NSString *)output {
    const auto &modelInputs = _interpreter->getSessionInputAll(_session);
    if (inputs.count != modelInputs.size()) { return nil; }
    for (const auto &entry : modelInputs) {
        NSData *bytes = inputs[[NSString stringWithUTF8String:entry.first.c_str()]];
        auto *tensor = entry.second;
        MNN::Tensor host(tensor, tensor->getDimensionType());
        if (!bytes || !host.host<float>() || host.getType() != halide_type_of<float>() ||
            bytes.length != size_t(host.elementSize()) * sizeof(float)) {
            return nil;
        }
        std::memcpy(host.host<float>(), bytes.bytes, bytes.length);
        if (!tensor->copyFromHostTensor(&host)) { return nil; }
    }
    if (_interpreter->runSession(_session) != MNN::NO_ERROR) { return nil; }
    return [self output:output];
}

- (nullable NSData *)output:(NSString *)name {
    auto *tensor = _interpreter->getSessionOutput(_session, name.UTF8String);
    if (!tensor || tensor->getType() != halide_type_of<float>()) { return nil; }
    MNN::Tensor host(tensor, tensor->getDimensionType());
    if (!tensor->copyToHostTensor(&host) || !host.host<float>()) { return nil; }
    return [NSData dataWithBytes:host.host<float>() length:size_t(host.elementSize()) * sizeof(float)];
}
@end
