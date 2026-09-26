#!/usr/bin/env python3
"""确认内置链路已握手后停用旧 Helper，保留一份完整回退包。"""
from pathlib import Path
import json
import os
import plistlib
import shutil
import signal
import subprocess
import time

root = Path(__file__).resolve().parents[1]
home = Path.home()
support = home / 'Library/Application Support/Pad UU'
status_path = support / 'sunshine-input-status.json'
status = json.loads(status_path.read_text())
if (time.time() - status_path.stat().st_mtime > 5 or not status.get('active')
        or not status.get('permission') or status.get('transport') != 'moonlight-control'):
    raise SystemExit('先连接新版客户端并确认内置后端就绪。')

app = home / 'Applications/Pad Mac Helper.app'
agent = home / 'Library/LaunchAgents/local.pad.uu.ScrollBridge.plist'
backup = root / '.local/rollback/pre-convergence'
backup.mkdir(parents=True, exist_ok=True)
if app.exists():
    info = plistlib.loads((app / 'Contents/Info.plist').read_bytes())
    if info.get('CFBundleIdentifier') != 'local.pad.uu.ScrollBridge':
        raise SystemExit('应用身份不匹配，停止清理。')
    if not (backup / app.name).exists():
        shutil.copytree(app, backup / app.name)
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(backup / app.name)], check=True)
if agent.exists() and not (backup / agent.name).exists():
    shutil.copy2(agent, backup / agent.name)

service = f'gui/{os.getuid()}/local.pad.uu.ScrollBridge'
subprocess.run(['launchctl', 'bootout', service], capture_output=True)
binary = str(app / 'Contents/MacOS/PadMacHelper')
for line in subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines():
    parts = line.strip().split(None, 1)
    if len(parts) != 2:
        continue
    command = parts[1]
    helper = command == binary or command.startswith(binary + ' ')
    reader = (command.split(' ', 1)[0].endswith('/adb') and "su -c 'umask 077;" in command
              and any(f'/data/user/0/{p}/files/pad_uu_input' in command
                      for p in ['com.netease.uuremote', 'com.limelight']))
    if helper or reader:
        try:
            os.kill(int(parts[0]), signal.SIGTERM)
        except ProcessLookupError:
            pass
for _ in range(100):
    if subprocess.run(['launchctl', 'print', service], capture_output=True).returncode:
        break
    time.sleep(.1)
else:
    raise SystemExit('旧服务仍在运行，未删除安装包。')
if app.exists():
    shutil.rmtree(app)
agent.unlink(missing_ok=True)
(support / 'helper-status.json').unlink(missing_ok=True)
print('旧 Helper 与自启动项已停用、移除；完整回退包保留在', backup)
