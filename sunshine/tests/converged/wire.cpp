#include "pad_session.h"
#include <cassert>
#include <vector>

/** @brief 验证扩展包长度和边界；不创建原生输入后端。 */
int main() {
  std::vector<std::uint8_t> packet {0, 0, 0, 6, 0x31, 0x44, 0x41, 0x50, '{', '}'};
  assert(pad::matches(packet));
  assert(pad::payload(packet)->size() == 2);
  packet[3] = 5;
  assert(!pad::payload(packet));
  packet[3] = 6; packet[4] = 0;
  assert(!pad::matches(packet) && !pad::payload(packet));
  packet.resize(7);
  assert(!pad::matches(packet));
  packet.assign(8 + PAD_PAYLOAD_MAX + 1, 0);
  packet[4] = 0x31; packet[5] = 0x44; packet[6] = 0x41; packet[7] = 0x50;
  assert(pad::matches(packet) && !pad::payload(packet));
}
