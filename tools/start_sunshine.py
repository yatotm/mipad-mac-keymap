#!/usr/bin/env python3
"""将已安装的 Sunshine 注册为当前用户的串流服务；不授予或修改系统隐私权限。"""
from pathlib import Path
import os
import plistlib
import signal
import subprocess
import time

home = Path.home()
binary = home / "Applications/Sunshine.app/Contents/MacOS/Sunshine"
config = home / ".config/sunshine/sunshine.conf"
if not binary.is_file() or not config.is_file():
    raise SystemExit("请先安装官方 Sunshine 并准备 ~/.config/sunshine/sunshine.conf。")
subprocess.run(["codesign", "--verify", "--deep", "--strict", str(binary.parents[2])], check=True)
label = "local.pad.sunshine"
domain = f"gui/{os.getuid()}"
subprocess.run(["launchctl", "bootout", domain + "/" + label], capture_output=True)
# 只收敛本任务安装路径中的同一个串流进程，不匹配命令行里的任意子串。
for line in subprocess.check_output(["ps", "-axo", "pid=,comm="], text=True).splitlines():
    item = line.strip().split(None, 1)
    if len(item) == 2 and item[1] == str(binary):
        try:
            os.kill(int(item[0]), signal.SIGTERM)
        except ProcessLookupError:
            pass
for _ in range(100):
    # bootout 返回时旧服务可能还在异步退出，立即 bootstrap 会得到误导性的错误 5。
    if subprocess.run(["launchctl", "print", domain + "/" + label], capture_output=True).returncode != 0:
        break
    time.sleep(0.1)
else:
    raise SystemExit("旧 Sunshine 服务尚未退出，停止重复启动。")
agent = home / "Library/LaunchAgents" / (label + ".plist")
agent.parent.mkdir(parents=True, exist_ok=True)
settings = {
    "Label": label,
    "ProgramArguments": [str(binary), str(config)],
    "RunAtLoad": True,
    "KeepAlive": {"SuccessfulExit": False},
    "ThrottleInterval": 30,
    "StandardOutPath": "/dev/null",
    "StandardErrorPath": "/dev/null",
    "ProcessType": "Interactive",
}
info = plistlib.loads((binary.parent.parent / "Info.plist").read_bytes())
if (binary.parent.parent / "Resources/pad-local-cursor.json").is_file() and not info.get("PadInputProtocol"):
    settings["EnvironmentVariables"] = {"PAD_LOCAL_CURSOR": "1"}
agent.write_bytes(plistlib.dumps(settings))
subprocess.run(["launchctl", "bootstrap", domain, str(agent)], check=True)
print("Sunshine 已注册；输入后端已内置。" if info.get("PadInputProtocol") else "Sunshine 已注册；这是回退用的旧输入链路。")
