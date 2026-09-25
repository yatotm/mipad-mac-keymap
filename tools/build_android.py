#!/usr/bin/env python3
"""构建两个独立的 Vector 模块，签名材料仅从本地目录读取。"""
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("module", choices=["system-keyboard", "uu-input"])
args = parser.parse_args()
base = ROOT / "android" / args.module
sdk = Path(os.environ.get("PAD_ANDROID_SDK", ROOT / ".cache/sdk"))
tools = sdk / "android-15"
android = sdk / "android-35/android.jar"
api = ROOT / ".cache/xposed-api-82.jar"
java = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk")) / "bin"
keystore = ROOT / ".local/signing/pad-uu-local.p12"
if not all(p.exists() for p in [android, tools / "aapt2", api, keystore]):
    raise SystemExit("缺少 SDK、Xposed API 或本地签名；先按构建说明准备，不能用新密钥覆盖已有安装。")
build = ROOT / "build" / args.module
if build.exists():
    shutil.rmtree(build)
for p in [build / "classes", build / "dex", build / "tests"]:
    p.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(java.parent), PATH=str(java) + os.pathsep + os.environ["PATH"])
env.setdefault("PAD_KEYSTORE_PASSWORD", (ROOT / ".local/signing/store-password").read_text().strip())


def run(*command):
    subprocess.run([str(x) for x in command], cwd=ROOT, env=env, check=True)


sources = sorted((base / "src").rglob("*.java"))
entry = "SystemKeyboard.java" if args.module == "system-keyboard" else "PadTouchpad.java"
tests = sorted((base / "tests").glob("*.java"))
run(java / "javac", "--release", "11", "-encoding", "UTF-8", "-classpath", android,
    "-d", build / "tests", *[p for p in sources if p.name != entry], *tests)
for test in tests:
    run(java / "java", "-cp", build / "tests", test.stem)
run(java / "javac", "--release", "11", "-encoding", "UTF-8", "-classpath",
    str(android) + os.pathsep + str(api), "-d", build / "classes", *sources)
run(tools / "d8", "--min-api", "34", "--lib", android, "--classpath", api,
    "--output", build / "dex", *sorted((build / "classes").rglob("*.class")))
run(tools / "aapt2", "compile", "--dir", base / "res", "-o", build / "resources.zip")
run(tools / "aapt2", "link", "-I", android, "--manifest", base / "AndroidManifest.xml",
    "-A", base / "assets", "-o", build / "unsigned.apk", build / "resources.zip")
with zipfile.ZipFile(build / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write(build / "dex/classes.dex", "classes.dex")
run(tools / "zipalign", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
version = ET.parse(base / "AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName")
output = build / f"{args.module}-{version}.apk"
run(tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", "pad-uu",
    "--ks-pass", "env:PAD_KEYSTORE_PASSWORD", "--out", output, build / "aligned.apk")
run(tools / "apksigner", "verify", "--verbose", output)
print(output)
print("SHA-256:", hashlib.sha256(output.read_bytes()).hexdigest())
