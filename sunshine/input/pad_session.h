#pragma once
#include "pad_input.h"
#include <array>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#ifdef __APPLE__
#include "pad_native.h"
#endif

namespace pad {
  /** @brief 判断输入消息是否属于 Pad 扩展。 */
  inline bool matches(std::span<const std::uint8_t> bytes) {
    return bytes.size() >= 8 &&
           (std::uint32_t(bytes[4]) | (std::uint32_t(bytes[5]) << 8) |
            (std::uint32_t(bytes[6]) << 16) | (std::uint32_t(bytes[7]) << 24)) == PAD_INPUT_MAGIC;
  }

  /** @brief 严格校验长度，不让扩展负载借用标准键盘消息入口。 */
  inline std::optional<std::span<const std::uint8_t>> payload(std::span<const std::uint8_t> bytes) {
    if (!matches(bytes) || bytes.size() <= 8 || bytes.size() > 8 + PAD_PAYLOAD_MAX) {
      return std::nullopt;
    }
    const auto size = (std::uint32_t(bytes[0]) << 24) | (std::uint32_t(bytes[1]) << 16) |
                      (std::uint32_t(bytes[2]) << 8) | std::uint32_t(bytes[3]);
    if (size != bytes.size() - 4) {
      return std::nullopt;
    }
    return bytes.subspan(8);
  }

  /** @brief 一次串流拥有一个后端；停止与接收共用互斥锁，避免销毁竞态。 */
  class session_t {
  public:
    /** @brief 会话结束时清理原生输入。 */
    ~session_t() { stop(); }

    /** @brief 执行经过加密验证的负载，返回 2 时释放普通键盘。 */
    int receive(std::span<const std::uint8_t> bytes) {
      const auto data = payload(bytes);
      if (!data) { return 0; }
      std::lock_guard lock(mutex_);
      if (stopped_) { return 0; }
#ifdef __APPLE__
      if (!handle_) { handle_ = pad_native_create(); }
      return pad_native_receive(handle_, data->data(), static_cast<std::int32_t>(data->size()));
#else
      return 0;
#endif
    }

    /** @brief 定期检查心跳；过期时调用方同时释放普通键盘。 */
    bool tick() {
      std::lock_guard lock(mutex_);
#ifdef __APPLE__
      return handle_ && pad_native_tick(handle_);
#else
      return false;
#endif
    }

    /** @brief 最多每 250 毫秒回传一次有界状态与实际显示区域。 */
    std::string state() {
      std::lock_guard lock(mutex_);
      const auto now = std::chrono::steady_clock::now();
      if (!handle_ || stopped_ || now < next_state_) { return {}; }
      next_state_ = now + std::chrono::milliseconds(250);
#ifdef __APPLE__
      std::array<std::uint8_t, PAD_STATE_MAX> bytes {};
      const auto length = pad_native_metadata(handle_, bytes.data(), bytes.size());
      if (length > 0 && length <= bytes.size()) {
        return std::string(reinterpret_cast<const char *>(bytes.data()), length);
      }
#endif
      return {};
    }

    /** @brief 幂等停止，不允许后续晚到帧重新启用输入。 */
    void stop() {
      std::lock_guard lock(mutex_);
      stopped_ = true;
#ifdef __APPLE__
      if (handle_) { pad_native_destroy(handle_); handle_ = 0; }
#endif
    }

  private:
    std::mutex mutex_;  ///< 保护接收与连接销毁。
    std::uint64_t handle_ = 0;  ///< Swift 后端句柄。
    bool stopped_ = false;  ///< 已结束的串流不可复活。
    std::chrono::steady_clock::time_point next_state_ {};  ///< 下一次状态回传时间。
  };
}
