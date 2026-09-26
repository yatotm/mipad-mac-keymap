#!/usr/bin/env python3
"""构建 Sunshine 静态输入库并运行无输入副作用的协议检查。"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
build = root / '.cache/mac-native'
build.mkdir(parents=True, exist_ok=True)
shared = sorted((root / 'mac-input/Sources').glob('*.swift'))
native = sorted((root / 'sunshine/input').glob('*.swift'))

def run(*args):
    subprocess.run(list(map(str, args)), check=True, cwd=root)

run('xcrun', 'swiftc', '-swift-version', '5', '-O', '-parse-as-library', '-emit-library', '-static',
    '-module-name', 'PadSunshineInput', *shared, *native, '-o', build / 'libPadInput.a')
run('xcrun', 'swiftc', '-swift-version', '5', '-O', root / 'mac-input/Sources/InputProtocol.swift',
    root / 'sunshine/input/PadGate.swift', root / 'sunshine/tests/converged/main.swift', '-o', build / 'pad-gate-test')
run(build / 'pad-gate-test')
run('xcrun', 'clang++', '-std=c++20', '-I', root / 'sunshine/input', '-I', root / 'protocol',
    root / 'sunshine/tests/converged/wire.cpp', '-o', build / 'pad-wire-test')
run(build / 'pad-wire-test')
print(build / 'libPadInput.a')
