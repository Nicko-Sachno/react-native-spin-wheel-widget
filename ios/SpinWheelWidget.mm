#import "SpinWheelWidget.h"

// This library is an ANDROID home-screen widget; there is no iOS widget. The module exists on iOS
// only so the JS import resolves — every method is a safe no-op (rather than the template's
// `multiply:`), so calling the API on iOS degrades gracefully instead of crashing with an
// unrecognized selector. See the README ("Android only").
@implementation SpinWheelWidget

- (void)configure:(NSString *)configUrl assetsHost:(NSString *)assetsHost {}

- (void)setConfigJson:(NSString *)json {}

- (void)refresh:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    resolve(@NO); // no widget can be placed on iOS
}

- (NSDictionary *)getLastResult
{
    return @{
        @"restingAngle": @0,
        @"lastFetchTime": @0,
        @"configUrl": @"",
        @"segmentIndex": @(-1),
    };
}

// NativeEventEmitter plumbing — nothing is ever emitted on iOS.
- (void)addListener:(NSString *)eventName {}
- (void)removeListeners:(double)count {}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeSpinWheelWidgetSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"SpinWheelWidget";
}

@end
