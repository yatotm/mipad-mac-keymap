#!/usr/bin/env python3
"""完整安装模块，核对存储类型和 Vector 注册；系统作用域不允许增量安装。"""
from pathlib import Path
import argparse
import json
import shlex
import sqlite3
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True, help="已授权的 ADB 设备地址或序列号")
parser.add_argument("--module", choices=["all", "system-keyboard", "uu-input"], default="all")
parser.add_argument("--reboot", action="store_true", help="全部安装检查通过后重启平板")
args = parser.parse_args()
adb = ["adb", "-s", args.serial]
local = root / ".local"
local.mkdir(exist_ok=True)


def root_shell(command):
    return subprocess.check_output(adb + ["shell", "su -c " + shlex.quote(command)], text=True).strip()


def registered(package, apk_path):
    # 连同 WAL 读取快照，不能只读主数据库后误判为旧注册；绝不回写整个数据库。
    for attempt in range(6):
        with tempfile.TemporaryDirectory(prefix="vector-check-", dir=local) as tmp:
            for name in ["modules_config.db", "modules_config.db-wal"]:
                result = subprocess.run(adb + ["exec-out", "su", "-c", "cat /data/adb/lspd/config/" + name],
                                        capture_output=True, check=True)
                (Path(tmp) / name).write_bytes(result.stdout)
            with sqlite3.connect(str(Path(tmp) / "modules_config.db")) as db:
                row = db.execute("SELECT apk_path,enabled FROM modules WHERE module_pkg_name=?", (package,)).fetchone()
                if row == (apk_path, 1):
                    return True
        time.sleep(.3)
    return False


modules = ["system-keyboard", "uu-input"] if args.module == "all" else [args.module]
result = []
for name in modules:
    manifest = ET.parse(root / "android" / name / "AndroidManifest.xml").getroot()
    package = manifest.get("package")
    version = manifest.get("{http://schemas.android.com/apk/res/android}versionName")
    apk = root / "build" / name / f"{name}-{version}.apk"
    if not apk.is_file():
        raise SystemExit("先构建：" + str(apk))
    subprocess.run(adb + ["install", "--no-incremental", "--no-streaming", "-r", str(apk)], check=True)
    installed = subprocess.check_output(adb + ["shell", "pm", "path", package], text=True).strip()
    if not installed.startswith("package:") or "\n" in installed:
        raise RuntimeError("安装路径异常")
    installed = installed.removeprefix("package:")
    storage = root_shell("stat -f -c %t " + shlex.quote(installed)).lower().removeprefix("0x")
    if storage == "46434e49":
        raise RuntimeError("APK 仍在 incremental-fs，停止；不能重启验证系统模块")
    if not registered(package, installed):
        raise RuntimeError("Vector 尚未登记当前 APK 或模块未启用，停止重启：" + package)
    result.append({"package": package, "version": version, "filesystem_magic": storage, "registered": True})
(local / "deployment.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print("安装与注册检查通过。系统进程的实际加载仍需在重启后核对。")
if args.reboot:
    subprocess.run(adb + ["reboot"], check=True)
