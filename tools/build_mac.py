#!/usr/bin/env python3
"""生成单个 Mac 辅助 App；不修改已安装程序或系统授权。"""
from pathlib import Path
import plistlib
import shutil
import subprocess
import os

root = Path(__file__).resolve().parents[1]
app = root / "build/Pad Mac Helper.app"
if app.exists():
    shutil.rmtree(app)
contents = app / "Contents"
(contents / "MacOS").mkdir(parents=True)
info = {
    "CFBundleIdentifier": "local.pad.uu.ScrollBridge",
    "CFBundleName": "Pad Mac Helper",
    "CFBundleDisplayName": "Pad Mac Helper",
    "CFBundleExecutable": "PadMacHelper",
    "CFBundlePackageType": "APPL",
    "CFBundleVersion": "13",
    "CFBundleShortVersionString": "0.5.4",
    "LSUIElement": True,
    "NSHighResolutionCapable": True,
}
(contents / "Info.plist").write_bytes(plistlib.dumps(info))
binary = contents / "MacOS/PadMacHelper"
subprocess.run(["xcrun", "swiftc", "-O", "-o", str(binary),
                *map(str, sorted((root / "mac-helper/Sources").glob("*.swift")))], check=True)
subprocess.run([str(binary), "--self-test"], check=True)
identity_file = root / ".local/mac-signing-identity"
identity = os.environ.get("PAD_MAC_SIGN_IDENTITY") or (identity_file.read_text().strip() if identity_file.exists() else "-")
subprocess.run(["codesign", "--force", "--sign", identity, str(app)], check=True, timeout=30)
subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
print(app)
