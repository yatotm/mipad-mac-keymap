#pragma once

// 仅在双方协商功能位后启用；复用已有加密控制连接，不另开端口。
#define PAD_INPUT_MAGIC 0x50414431u
#define PAD_STATE_TYPE 0x55f0u
#define PAD_FEATURE_FLAG 0x00010000u
#define PAD_PAYLOAD_MAX 1024u
#define PAD_STATE_MAX 512u
#define PAD_PROTOCOL_VERSION 1
