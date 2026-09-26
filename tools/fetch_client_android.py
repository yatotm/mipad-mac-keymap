#!/usr/bin/env python3
"""下载固定摘要的客户端 SDK/NDK；只写项目缓存，下载包验证后删除。"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import shutil
import subprocess
import urllib.request

root = Path(__file__).resolve().parents[1]
sdk = root / '.cache/client-sdk'
sdk.mkdir(parents=True, exist_ok=True)


def prepare(item):
    target = sdk.joinpath(*item['package'].split(';'))
    if (target / 'source.properties').is_file():
        shutil.copy2(root / 'tools/android-packages' / (item['package'].replace(';', '-') + '.xml'), target / 'package.xml')
        print('已存在：', item['package'], flush=True)
        return
    folder = root / '.cache/client-downloads' / item['package'].replace(';', '-')
    folder.mkdir(parents=True, exist_ok=True)
    archive = folder / 'package.zip'
    print('下载：', item['package'], flush=True)
    urllib.request.urlretrieve(item['url'], archive)
    digest = hashlib.sha1()
    with archive.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if archive.stat().st_size != item['size'] or digest.hexdigest() != item['sha1']:
        raise RuntimeError('SDK 摘要不匹配：' + item['package'])
    unpack = folder / 'unpack'
    unpack.mkdir(exist_ok=True)
    subprocess.run(['unzip', '-q', str(archive), '-d', str(unpack)], check=True)
    children = list(unpack.iterdir())
    if len(children) != 1 or not children[0].is_dir():
        raise RuntimeError('SDK 压缩包布局异常')
    target.parent.mkdir(parents=True, exist_ok=True)
    children[0].rename(target)
    shutil.copy2(root / 'tools/android-packages' / (item['package'].replace(';', '-') + '.xml'), target / 'package.xml')
    shutil.rmtree(folder)
    print('已准备：', item['package'], flush=True)


with ThreadPoolExecutor(max_workers=2) as pool:
    list(pool.map(prepare, json.loads((root / 'tools/client-android-downloads.json').read_text())))
