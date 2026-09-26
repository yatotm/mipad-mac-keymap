#!/usr/bin/env python3
"""从固定版本构建带原生托盘和内置输入的 Sunshine；只生成 App。"""
from pathlib import Path
import argparse
import json
import os
import plistlib
import shutil
import subprocess
import sys
import sync_streaming_sources as sources

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--official-app', type=Path, default=Path.home() / 'Applications/Sunshine.app')
args = parser.parse_args()
source = args.source.resolve()
commit = '63d35f702ee9e362e43263742981836ec0710384'
build = root / '.cache/cmake-build-sunshine'
stage = root / 'build/sunshine-stage'
app = stage / 'Sunshine.app'
env = dict(os.environ, BRANCH='pad-converged-input', BUILD_VERSION='2026.914.233613')


def run(*cmd, cwd=source):
    subprocess.run(list(map(str, cmd)), cwd=cwd, env=env, check=True)


if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip() != commit:
    raise SystemExit('Sunshine 源码必须固定到 v2026.914.233613。')
sources.sunshine(source)
run(sys.executable, root / 'tools/build_pad_native.py', cwd=root)
swift = json.loads(subprocess.check_output(['xcrun', 'swiftc', '-print-target-info'], text=True))
swift_runtime = swift['paths']['runtimeLibraryImportPaths'][0]
swift_sdk = Path(subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip()) / 'usr/lib/swift'
run('cmake', '-S', source, '-B', build, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release',
    '-DPAD_INPUT_ARCHIVE=' + str(root / '.cache/mac-native/libPadInput.a'),
    '-DPAD_INPUT_ROOT=' + str(root / 'sunshine/input'), '-DPAD_PROTOCOL_ROOT=' + str(root / 'protocol'),
    '-DPAD_SWIFT_RUNTIME=' + swift_runtime, '-DPAD_SWIFT_SDK=' + str(swift_sdk),
    '-DBUILD_DOCS=OFF', '-DBUILD_TESTS=OFF', '-DSUNSHINE_ENABLE_TRAY=ON', '-DBOOST_USE_STATIC=ON',
    '-DOPUS_USE_STATIC=ON', '-DCMAKE_PREFIX_PATH=/opt/homebrew', '-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0',
    '-DSUNSHINE_PUBLISHER_NAME=MiPad Mac Keymap',
    '-DSUNSHINE_PUBLISHER_ISSUE_URL=https://github.com/yatotm/mipad-mac-keymap/issues')
run('xcrun', 'clang', '-fobjc-arc', '-fblocks', '-framework', 'Foundation',
    '-I', source / 'src/platform/macos', root / 'sunshine/tests/pad_cursor_test.m',
    '-o', build / 'pad-cursor-test')
run(build / 'pad-cursor-test')
run('cmake', '--build', build, '--target', 'sunshine', '-j', '8')
tray_test = root / '.cache/cmake-build-tray-test'
run('cmake', '-S', root / 'sunshine/tests/tray', '-B', tray_test, '-G', 'Ninja',
    '-DPAD_TRAY_SOURCE=' + str(source / 'third-party/tray'),
    '-DPAD_TRAY_LIBRARY=' + str(build / 'third-party/tray/libtray.a'),
    '-DCMAKE_PREFIX_PATH=/opt/homebrew')
run('cmake', '--build', tray_test)
run(tray_test / 'pad-tray-test')
if stage.exists():
    shutil.rmtree(stage)
run('cmake', '--install', build, '--prefix', stage, '--component', 'Runtime')
# 视频捕获之外的网页界面直接沿用相同官方版本，避免重新下载整套前端依赖。
web = args.official_app / 'Contents/Resources/assets/web'
if not web.is_dir():
    raise SystemExit('缺少相同版本的官方网页资源。')
shutil.copytree(web, app / 'Contents/Resources/assets/web', dirs_exist_ok=True)
resources = app / 'Contents/Resources'
(resources / 'pad-local-cursor.json').write_text(json.dumps({'source': commit, 'revision': 2}) + '\n')
info_path = app / 'Contents/Info.plist'
info = plistlib.loads(info_path.read_bytes())
info['PadCursorRevision'] = '2'
info['PadInputProtocol'] = '1'
info['PadTrayRevision'] = '1'
info['LSMinimumSystemVersion'] = '15.0'
info_path.write_bytes(plistlib.dumps(info))
identity = (root / '.local/mac-signing-identity').read_text().strip()
for library in sorted(app.rglob('*.dylib')):
    run('codesign', '--force', '--options', 'runtime', '--sign', identity, library)
# 原生托盘依赖 Qt；先签内嵌 Framework，再签最外层 App。
for framework in sorted(app.rglob('*.framework'), key=lambda p: len(p.parts), reverse=True):
    run('codesign', '--force', '--options', 'runtime', '--sign', identity, framework)
run('codesign', '--force', '--options', 'runtime', '--sign', identity,
    '--entitlements', source / 'src_assets/macos/entitlements.plist', app)
run('codesign', '--verify', '--deep', '--strict', app)
print(app)
