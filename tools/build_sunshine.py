#!/usr/bin/env python3
"""从固定版本构建本地光标适配的 Sunshine；只生成 App，不替换运行版本。"""
from pathlib import Path
import argparse
import json
import os
import plistlib
import shutil
import subprocess

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
env = dict(os.environ, BRANCH='pad-local-cursor', BUILD_VERSION='2026.914.233613')


def run(*cmd, cwd=source):
    subprocess.run(list(map(str, cmd)), cwd=cwd, env=env, check=True)


if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip() != commit:
    raise SystemExit('Sunshine 源码必须固定到 v2026.914.233613。')
patch = root / 'sunshine/local-cursor.patch'
if subprocess.run(['git', 'apply', '--reverse', '--check', str(patch)], cwd=source, capture_output=True).returncode:
    run('git', 'apply', '--check', patch)
    run('git', 'apply', patch)
run('cmake', '-S', source, '-B', build, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_DOCS=OFF', '-DBUILD_TESTS=OFF', '-DSUNSHINE_ENABLE_TRAY=OFF', '-DBOOST_USE_STATIC=ON',
    '-DOPUS_USE_STATIC=ON', '-DCMAKE_PREFIX_PATH=/opt/homebrew', '-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0',
    '-DSUNSHINE_PUBLISHER_NAME=MiPad Mac Keymap',
    '-DSUNSHINE_PUBLISHER_ISSUE_URL=https://github.com/yatotm/mipad-mac-keymap/issues')
run('xcrun', 'clang', '-fobjc-arc', '-fblocks', '-framework', 'Foundation',
    '-I', source / 'src/platform/macos', root / 'sunshine/tests/pad_cursor_test.m',
    '-o', build / 'pad-cursor-test')
run(build / 'pad-cursor-test')
run('cmake', '--build', build, '--target', 'sunshine', '-j', '8')
if stage.exists():
    shutil.rmtree(stage)
run('cmake', '--install', build, '--prefix', stage, '--component', 'Runtime')
# 视频捕获之外的网页界面直接沿用相同官方版本，避免重新下载整套前端依赖。
web = args.official_app / 'Contents/Resources/assets/web'
if not web.is_dir():
    raise SystemExit('缺少相同版本的官方网页资源。')
shutil.copytree(web, app / 'Contents/Resources/assets/web', dirs_exist_ok=True)
resources = app / 'Contents/Resources'
(resources / 'pad-local-cursor.json').write_text(json.dumps({'source': commit, 'revision': 1}) + '\n')
info_path = app / 'Contents/Info.plist'
info = plistlib.loads(info_path.read_bytes())
info['PadCursorRevision'] = '1'
info['LSMinimumSystemVersion'] = '15.0'
info_path.write_bytes(plistlib.dumps(info))
identity = (root / '.local/mac-signing-identity').read_text().strip()
for library in sorted(app.rglob('*.dylib')):
    run('codesign', '--force', '--options', 'runtime', '--sign', identity, library)
run('codesign', '--force', '--options', 'runtime', '--sign', identity,
    '--entitlements', source / 'src_assets/macos/entitlements.plist', app)
run('codesign', '--verify', '--deep', '--strict', app)
print(app)
