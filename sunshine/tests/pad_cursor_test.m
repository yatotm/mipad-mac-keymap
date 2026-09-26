#import "pad_cursor.h"
#include <unistd.h>

/** @brief 验证光标状态异常时恢复视频光标；不采集屏幕、不发送输入。 */
int main(void) {
  @autoreleasepool {
    NSString *directory = [NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString];
    NSFileManager *files = NSFileManager.defaultManager;
    [files createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:NULL];
    NSString *path = [directory stringByAppendingPathComponent:@"status.json"];
    NSDate *now = [NSDate date];
    NSCAssert(!pad_local_cursor_active(path, now), @"不存在时回退");
    NSMutableDictionary *status = [@{@"local_cursor": @YES, @"moonlight_connection": @"connected", @"pid": @(getpid())} mutableCopy];
    void (^save)(void) = ^{
      [[NSJSONSerialization dataWithJSONObject:status options:0 error:NULL] writeToFile:path atomically:YES];
    };
    save();
    NSCAssert(pad_local_cursor_active(path, now), @"只接受活跃客户端");
    NSCAssert(!pad_local_cursor_active(path, [now dateByAddingTimeInterval:4]), @"旧文件不能永远隐藏光标");
    NSCAssert(!pad_local_cursor_active(path, [now dateByAddingTimeInterval:-2]), @"未来时间回退");
    status[@"local_cursor"] = @NO; save();
    NSCAssert(!pad_local_cursor_active(path, now), @"本地箭头关闭时回退");
    status[@"local_cursor"] = @YES; status[@"moonlight_connection"] = @"disconnected"; save();
    NSCAssert(!pad_local_cursor_active(path, now), @"断线回退");
    status[@"moonlight_connection"] = @"connected"; status[@"pid"] = @INT_MAX; save();
    NSCAssert(!pad_local_cursor_active(path, now), @"Helper 消失时回退");
    status[@"pid"] = @0; save();
    NSCAssert(!pad_local_cursor_active(path, now), @"无效 PID 回退");
    status[@"pid"] = @(1LL << 40); save();
    NSCAssert(!pad_local_cursor_active(path, now), @"PID 溢出不能匹配其他进程");
    status[@"pid"] = @"bad"; save();
    NSCAssert(!pad_local_cursor_active(path, now), @"类型错误回退");
    [@"[]" writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:NULL];
    NSCAssert(!pad_local_cursor_active(path, now), @"根类型错误回退");
    [@"{" writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:NULL];
    NSCAssert(!pad_local_cursor_active(path, now), @"截断文件回退");
    [[NSMutableData dataWithLength:4097] writeToFile:path atomically:YES];
    NSCAssert(!pad_local_cursor_active(path, now), @"过大文件回退");
    [files removeItemAtPath:directory error:NULL];
    puts("本地光标在线、失联、过期、异常和 PID 边界检查通过；未采集屏幕或发送输入。");
  }
  return 0;
}
