#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif
/** @brief 是否支持当前 macOS 的完整原生输入后端。 */
bool pad_native_supported(void);
/** @brief 创建一个独立的已配对会话输入状态。 */
uint64_t pad_native_create(void);
/** @brief 释放会话，并结束所有尚未完成的输入。 */
void pad_native_destroy(uint64_t id);
/** @brief 清空会话输入状态。 */
void pad_native_reset(uint64_t id);
/** @brief 由视频捕获对象发布实际显示器。 */
void pad_native_capture_display(uint32_t display);
/** @brief 当前是否应隐藏视频内的重复光标。 */
bool pad_native_cursor_active(void);
/** @brief 验证并执行会话输入；返回 2 时调用方还应释放普通键盘。 */
int32_t pad_native_receive(uint64_t id, const uint8_t *bytes, int32_t length);
/** @brief 检查输入心跳，过期时返回 true。 */
bool pad_native_tick(uint64_t id);
/** @brief 将有界显示元数据复制到调用方缓冲区。 */
int32_t pad_native_metadata(uint64_t id, uint8_t *output, int32_t capacity);
#ifdef __cplusplus
}
#endif
