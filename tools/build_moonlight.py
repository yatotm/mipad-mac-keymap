#!/usr/bin/env python3
"""构建独立包名的 MiPad Moonlight；保留官方应用与已有数据。"""
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import subprocess
import sync_streaming_sources as sources

root = sources.ROOT
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, required=True)
args = parser.parse_args()
source = args.source.resolve()
sources.moonlight(source)
sdk = root / '.cache/client-sdk'
key = root / '.local/signing/pad-uu-local.p12'
if not (sdk / 'ndk/29.0.14206865/ndk-build').exists() or not key.exists():
    raise SystemExit('缺少固定 SDK/NDK 或本地签名，先准备构建环境。')
env = dict(os.environ, JAVA_HOME='/opt/homebrew/opt/openjdk', ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
           GRADLE_USER_HOME=str(root / '.cache/gradle'), PAD_KEYSTORE=str(key),
           PAD_KEYSTORE_PASSWORD=(root / '.local/signing/store-password').read_text().strip())
subprocess.run(['./gradlew', '--no-daemon', '--console=plain', ':app:assembleNonRootRelease'],
               cwd=source, env=env, check=True)
outputs = list((source / 'app/build/outputs/apk/nonRoot/release').glob('*.apk'))
if len(outputs) != 1:
    raise SystemExit('APK 产物数量异常。')
folder = root / 'build/moonlight-client'
folder.mkdir(parents=True, exist_ok=True)
target = folder / 'mipad-moonlight-12.2-pad.1.apk'
shutil.copy2(outputs[0], target)
subprocess.run([str(sdk / 'build-tools/37.0.0/apksigner'), 'verify', '--verbose', str(target)], env=env, check=True)
print(target)
print('SHA-256:', hashlib.sha256(target.read_bytes()).hexdigest())
