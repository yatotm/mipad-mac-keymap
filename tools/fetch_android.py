#!/usr/bin/env python3
"""下载并校验最小 Android 构建工具集；压缩包解出所需文件后即删除。"""
from pathlib import Path
import hashlib
import json
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
cache = root / ".cache"
sdk = cache / "sdk"
sdk.mkdir(parents=True, exist_ok=True)
needed = {"aapt2", "d8", "zipalign", "apksigner", "lib/d8.jar", "lib/apksigner.jar",
          "lib64/libc++.dylib", "lib64/libc++.1.dylib", "lib64/libc++abi.1.dylib"}
for item in json.loads((root / "tools/android-downloads.json").read_text()):
    archive = cache / item["url"].rsplit("/", 1)[-1]
    try:
        urllib.request.urlretrieve(item["url"], archive)
        if hashlib.sha1(archive.read_bytes()).hexdigest() != item["sha1"]:
            raise RuntimeError("SDK 校验失败：" + item["package"])
        with zipfile.ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                name = entry.filename
                if name == "android-35/android.jar" or name in {"android-15/" + p for p in needed}:
                    bundle.extract(entry, sdk)
                    if name in {"android-15/" + p for p in ["aapt2", "d8", "zipalign", "apksigner"]}:
                        (sdk / name).chmod(0o755)
    finally:
        archive.unlink(missing_ok=True)
    print("已准备：", item["package"])

# 原 Xposed Maven 入口已返回 404，镜像字节与既有 API 82 依赖一致，按固定摘要校验。
url = "https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar"
data = urllib.request.urlopen(url, timeout=30).read()
if hashlib.sha256(data).hexdigest() != "f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25":
    raise RuntimeError("Xposed API 校验失败")
(cache / "xposed-api-82.jar").write_bytes(data)
print("已准备：Xposed API 82")
