#!/usr/bin/env python3
"""安装已构建的统一 App；沿用一个 launchd 项，辅助功能授权由系统设置处理。"""
from pathlib import Path
import os
import json
import plistlib
import shutil
import signal
import subprocess
import time

root = Path(__file__).resolve().parents[1]
user = Path.home()
source = root / "build/Pad Mac Helper.app"
target = user / "Applications/Pad Mac Helper.app"
staged = target.with_name("Pad Mac Helper.staged.app")
target.parent.mkdir(parents=True, exist_ok=True)
backup = root / ".local/rollback/mac"
backup.mkdir(parents=True, exist_ok=True)
connection = root / ".local/connection.json"
if connection.exists():
    config = json.loads(connection.read_text())
    if not config.get("deviceSerial") or not str(config.get("adb", "")).startswith("/"):
        raise SystemExit("本地连接配置缺少设备身份或 ADB 路径")
    support = user / "Library/Application Support/Pad UU"
    support.mkdir(parents=True, exist_ok=True, mode=0o700)
    config_target = support / "connection.json"
    config_target.write_text(json.dumps(config, indent=2) + "\n")
    config_target.chmod(0o600)
subprocess.run(["codesign", "--verify", "--deep", "--strict", str(source)], check=True)
if staged.exists():
    shutil.rmtree(staged)
shutil.copytree(source, staged)
subprocess.run(["codesign", "--verify", "--deep", "--strict", str(staged)], check=True)
agent = user / "Library/LaunchAgents/local.pad.uu.ScrollBridge.plist"
agent.parent.mkdir(parents=True, exist_ok=True)
if agent.exists() and not (backup / agent.name).exists():
    shutil.copy2(agent, backup / agent.name)
for old in [user / "Applications/Pad UU Scroll.app",
            user / "Library/Application Support/Pad UU/Controls.app", target]:
    if old.exists() and not (backup / old.name).exists():
        shutil.copytree(old, backup / old.name)
domain = f"gui/{os.getuid()}"
subprocess.run(["launchctl", "bootout", domain + "/local.pad.uu.ScrollBridge"],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
executables = [str(target / "Contents/MacOS/PadMacHelper"),
               str(user / "Applications/Pad UU Scroll.app/Contents/MacOS/ScrollBridge")]
listing = subprocess.check_output(["ps", "-axo", "pid=,command="], text=True)
for line in listing.splitlines():
    parts = line.strip().split(None, 1)
    if len(parts) != 2:
        continue
    helper = any(parts[1] == p or parts[1].startswith(p + " ") for p in executables)
    # Foundation 的子进程可能在 Helper 被终止后继续存活，只结束本插件的读取命令。
    reader = ("/adb -s " in parts[1] and " exec-out su -c 'umask 077; exec 9>" in parts[1]
              and "/data/user/0/com.netease.uuremote/files/pad_uu_input.pipe 9>&9'" in parts[1])
    if helper or reader:
        try:
            os.kill(int(parts[0]), signal.SIGTERM)
        except ProcessLookupError:
            pass
time.sleep(.3)
if target.exists():
    shutil.rmtree(target)
staged.rename(target)
agent.write_bytes(plistlib.dumps({
    "Label": "local.pad.uu.ScrollBridge",
    "ProgramArguments": [str(target / "Contents/MacOS/PadMacHelper"), "--background"],
    "RunAtLoad": True,
    "KeepAlive": {"SuccessfulExit": False},
    "ProcessType": "Interactive",
}))
subprocess.run(["launchctl", "bootstrap", domain, str(agent)], check=True)
print("已安装：", target)
print("请核对系统设置中该版本的辅助功能授权；运行状态仅覆盖 helper-status.json。")
