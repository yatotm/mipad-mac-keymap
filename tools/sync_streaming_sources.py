#!/usr/bin/env python3
"""将 Git 中的完整适配代码与固定上游接入补丁同步到构建工作区。"""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MOONLIGHT_COMMIT = 'b48494cb96bff23d8886c4775cc4f39a1075495d'
CORE_COMMIT = '874ac9548f1bd6f095ef2b435c42cdde460e7821'
SUNSHINE_COMMIT = '63d35f702ee9e362e43263742981836ec0710384'


def apply(source, commit, patch):
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
    if actual != commit:
        raise RuntimeError('源码基线不匹配：' + str(source))
    patch = ROOT / patch
    if subprocess.run(['git', 'apply', '--reverse', '--check', str(patch)], cwd=source, capture_output=True).returncode:
        subprocess.run(['git', 'apply', '--check', str(patch)], cwd=source, check=True)
        subprocess.run(['git', 'apply', str(patch)], cwd=source, check=True)


def moonlight(source):
    core = source / 'app/src/main/jni/moonlight-core/moonlight-common-c'
    apply(source, MOONLIGHT_COMMIT, 'moonlight/patches/android-integration.patch')
    apply(core, CORE_COMMIT, 'moonlight/patches/common-input.patch')
    shutil.copy2(ROOT / 'protocol/pad_input.h', core / 'src/pad_input.h')
    java = source / 'app/src/main/java'
    shutil.copytree(ROOT / 'moonlight/client/src', java, dirs_exist_ok=True)
    for module, package, names in [
        ('moonlight-input', 'local/pad/moonlight', ['GestureEngine.java', 'PointerEngine.java', 'CursorGeometry.java', 'HeldKeys.java']),
        ('uu-input', 'local/pad/uu/touchpad', ['ScrollMomentum.java', 'FnCommand.java', 'RemoteFunctionReceiver.java']),
    ]:
        destination = java / package
        destination.mkdir(parents=True, exist_ok=True)
        for name in names:
            shutil.copy2(ROOT / 'android' / module / 'src' / package / name, destination / name)


def sunshine(source):
    apply(source, SUNSHINE_COMMIT, 'sunshine/converged.patch')
