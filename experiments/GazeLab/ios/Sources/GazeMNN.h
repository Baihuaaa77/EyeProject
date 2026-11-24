#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN
@interface GazeMNN : NSObject
- (nullable instancetype)initWithModelPath:(NSString *)path NS_SWIFT_NAME(init(modelPath:));
- (nullable NSData *)predict:(NSDictionary<NSString *, NSData *> *)inputs
                      output:(NSString *)output NS_SWIFT_NAME(predict(_:output:));
- (nullable NSData *)output:(NSString *)name;
@end
NS_ASSUME_NONNULL_END
