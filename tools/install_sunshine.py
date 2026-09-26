#!/usr/bin/env python3
"""安装内置输入版，或恢复已备份的旧包；保留配对和用户配置。"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
mode = parser.add_mutually_exclusive_group()
mode.add_argument('--restore-official', action='store_true')
mode.add_argument('--restore-previous', action='store_true')
args = parser.parse_args()
backup = root / '.local/rollback/sunshine-official/Sunshine.app'
previous = root / '.local/rollback/pre-convergence/Sunshine.app'
source = backup if args.restore_official else previous if args.restore_previous else root / 'build/sunshine-stage/Sunshine.app'
target = Path.home() / 'Applications/Sunshine.app'
staged = target.with_name('Sunshine.staged.app')
if not source.is_dir():
    raise SystemExit('缺少构建或回退包：' + str(source))
subprocess.run(['codesign', '--verify', '--deep', '--strict', str(source)], check=True)
if staged.exists():
    shutil.rmtree(staged)
shutil.copytree(source, staged)
subprocess.run(['codesign', '--verify', '--deep', '--strict', str(staged)], check=True)
if target.exists() and not backup.exists():
    if (target / 'Contents/Resources/pad-local-cursor.json').exists():
        raise SystemExit('当前包已经定制，不能将它误存成官方回退包。')
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(target, backup)
if target.exists() and not previous.exists() and not (args.restore_previous or args.restore_official):
    previous.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(target, previous)
service = f'gui/{os.getuid()}/local.pad.sunshine'
subprocess.run(['launchctl', 'bootout', service], capture_output=True)
for _ in range(100):
    if subprocess.run(['launchctl', 'print', service], capture_output=True).returncode:
        break
    time.sleep(.1)
else:
    raise SystemExit('旧服务没有退出，暂不替换。')
if target.exists():
    shutil.rmtree(target)
staged.rename(target)
subprocess.run([sys.executable, str(root / 'tools/start_sunshine.py')], check=True)
print('已恢复 Sunshine 备份。' if args.restore_official or args.restore_previous else '已安装 Sunshine 内置输入版；需要核对现有系统授权。')
