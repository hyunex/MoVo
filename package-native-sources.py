#!/usr/bin/env python3
"""Deliver pinned corresponding sources, prepare offline upstream trees, or record native hashes.

Python 3.11+ with tarfile's data extraction filter is required. No compilation is
performed. Use --cache-dir repeatedly to search offline archives; downloads occur
only with --download. Existing output destinations are never replaced.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent
LOCK_PATH = ROOT / 'mpv-player/native/sources.lock.json'
EXCLUDED_DIRS = {'.git', '.gradle', 'build', 'jniLibs', '__pycache__', '.idea', '.cxx', 'node_modules'}
EXCLUDED_SUFFIXES = {'.so', '.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.log', '.pyc', '.class'}


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def lock_data() -> dict:
    lock = json.loads(LOCK_PATH.read_text())
    if lock['schemaVersion'] != 1 or len(lock['sources']) != 22:
        raise ValueError('expected the complete version 1 lock with 22 sources')
    names = set()
    for pin in lock['sources']:
        if pin['name'] in names or Path(pin['filename']).name != pin['filename']:
            raise ValueError('duplicate source or unsafe archive filename')
        names.add(pin['name'])
        if len(pin['sha256']) != 64 or any(c not in '0123456789abcdef' for c in pin['sha256']):
            raise ValueError('invalid SHA256 pin')
    return lock


def check_archive(path: Path, pin: dict) -> None:
    if not path.is_file() or path.stat().st_size != pin['bytes'] or digest(path) != pin['sha256']:
        raise ValueError(f"archive size/SHA256 mismatch: {path} ({pin['name']})")


def archives(lock: dict, caches: list[Path], download: bool) -> dict[str, Path]:
    result = {}
    for pin in lock['sources']:
        found = next((cache / pin['filename'] for cache in caches if (cache / pin['filename']).exists()), None)
        if found is None:
            if not download:
                raise FileNotFoundError(f"missing {pin['filename']}; supply --cache-dir or --download")
            cache = caches[0]
            cache.mkdir(parents=True, exist_ok=True)
            found = cache / pin['filename']
            errors = []
            for endpoint in [{'url': pin['url']}, *pin.get('alternateDownloads', [])]:
                temporary = None
                try:
                    request = urllib.request.Request(endpoint['url'], headers={'User-Agent': 'MoVo-source-delivery/1', **endpoint.get('headers', {})})
                    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as out:
                        temporary = Path(out.name)
                        with urllib.request.urlopen(request, timeout=120) as response:
                            shutil.copyfileobj(response, out)
                    check_archive(temporary, pin)
                    if found.exists():
                        raise FileExistsError(found)
                    temporary.rename(found)
                    break
                except Exception as exc:
                    errors.append(str(exc))
                finally:
                    if temporary is not None and temporary.exists():
                        temporary.unlink()
            else:
                raise RuntimeError(f"failed fetching {pin['name']}: {'; '.join(errors)}")
        check_archive(found, pin)
        result[pin['name']] = found
    return result


def safe_relative(value: str) -> Path:
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts:
        raise ValueError(f'unsafe relative path: {value}')
    return Path(*path.parts)


def extract_root(archive: Path, dest: Path) -> None:
    # Extract to a private fresh directory with the data filter before moving
    # the single archive root. Submodule placeholders may only be empty dirs.
    with tempfile.TemporaryDirectory(dir=dest.parent) as temporary:
        staging = Path(temporary)
        with tarfile.open(archive) as source:
            members = source.getmembers()
            roots = set()
            for member in members:
                relative = safe_relative(member.name)
                if relative.parts:
                    roots.add(relative.parts[0])
                if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                    raise ValueError(f'unsupported archive entry: {member.name}')
            if len(roots) != 1:
                raise ValueError(f'expected one archive root: {archive}')
            source.extractall(staging, members=members, filter='data')
        root = staging / next(iter(roots))
        if root.is_symlink() or not root.is_dir():
            raise ValueError(f'invalid archive root: {archive}')
        if dest.exists() or dest.is_symlink():
            if dest.is_symlink() or not dest.is_dir() or any(dest.iterdir()):
                raise FileExistsError(f'refusing to replace source tree: {dest}')
            dest.rmdir()
        root.rename(dest)


def write_json_new(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x') as out:
        json.dump(data, out, indent=2)
        out.write('\n')


def record_binaries(lock: dict, library_dir: Path) -> dict:
    original = lock['binaryOrigins']['originalLibraries']
    expected = set(original)
    actual = {p.relative_to(library_dir).as_posix() for p in library_dir.rglob('*.so')}
    if actual != expected:
        raise ValueError(f'native library set mismatch; missing={sorted(expected-actual)}, extra={sorted(actual-expected)}')
    libraries = {}
    for name, old_hash in sorted(original.items()):
        path = library_dir / safe_relative(name)
        if path.is_symlink():
            raise ValueError(f'native library must not be a symlink: {path}')
        current = digest(path)
        bridge = Path(name).name == 'libplayer.so'
        if not bridge and current != old_hash:
            raise ValueError(f'retained engine library changed: {name}')
        if bridge and current == old_hash:
            raise ValueError(f'bridge still matches original binary; build modified JNI first: {name}')
        libraries[name] = {'sha256': current, 'bytes': path.stat().st_size, 'originalSha256': old_hash,
                           'provenance': 'modified-native-bridge' if bridge else 'unmodified-official-release'}
    bridge_sources = {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted((ROOT / 'mpv-player/native/bridge').rglob('*')) if p.is_file()}
    return {'schemaVersion': 1, 'sourceLockSha256': digest(LOCK_PATH), 'sourceApk': lock['binaryOrigins']['sourceApk'],
            'toolchain': lock['toolchain'], 'bridgeSources': bridge_sources, 'libraries': libraries}


def prepare(lock: dict, paths: dict[str, Path], destination: Path) -> None:
    if destination.exists() or destination.is_symlink():
        raise FileExistsError(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        tree = Path(temporary) / 'upstream'
        pins = sorted(lock['sources'], key=lambda p: (p['name'] != 'jni', 'submoduleOf' in p))
        for pin in pins:
            dest = tree / safe_relative(pin['preparePath'])
            if not dest.resolve().is_relative_to(tree.resolve()):
                raise ValueError(f"source destination escapes prepared tree: {pin['name']}")
            dest.parent.mkdir(parents=True, exist_ok=True)
            # Recheck immediately before extraction, including all submodules.
            check_archive(paths[pin['name']], pin)
            extract_root(paths[pin['name']], dest)
        shutil.copy2(LOCK_PATH, tree / 'movo-sources.lock.json')
        if destination.exists() or destination.is_symlink():
            raise FileExistsError(destination)
        tree.rename(destination)
    print(destination)


def source_files() -> list[Path]:
    selected = ['mpv-player/app/src', 'mpv-player/native', 'mpv-player/gradle',
                'mpv-player/app/build.gradle.kts', 'mpv-player/app/buildNative.py',
                'mpv-player/build.gradle.kts', 'mpv-player/settings.gradle.kts',
                'mpv-player/gradle.properties', 'mpv-player/gradlew', 'mpv-player/gradlew.bat',
                'README.md', 'VERSION', 'LICENSE', 'THIRD-PARTY-NOTICES.md',
                'package-native-sources.py', 'package-release.py']
    files = set()
    for name in selected:
        root = ROOT / name
        if not root.exists():
            raise FileNotFoundError(f'required source input: {root}')
        candidates = root.rglob('*') if root.is_dir() else [root]
        for path in candidates:
            rel = path.relative_to(ROOT)
            lower = path.name.lower()
            if any(part in EXCLUDED_DIRS for part in rel.parts) or path.suffix.lower() in EXCLUDED_SUFFIXES:
                continue
            if 'signing' in lower or 'password' in lower or lower in {'local.properties', '.env', 'key.properties', 'keystore.properties'}:
                continue
            if path.is_symlink():
                raise ValueError(f'source symlink is not allowed: {rel}')
            if path.is_file():
                files.add(path)
    return sorted(files)


def tar_bytes(tf: tarfile.TarFile, name: str, content: bytes) -> None:
    info = tarfile.TarInfo(name)
    info.size = len(content)
    info.mode = 0o644
    info.mtime = 0
    tf.addfile(info, io.BytesIO(content))


def bundle(lock: dict, paths: dict[str, Path], binary_manifest: Path, destination: Path) -> None:
    if destination.exists() or destination.is_symlink():
        raise FileExistsError(destination)
    checksum_path = destination.with_name(destination.name + '.sha256')
    if checksum_path.exists() or checksum_path.is_symlink():
        raise FileExistsError(checksum_path)
    manifest = json.loads(binary_manifest.read_text())
    current = record_binaries(lock, ROOT / 'mpv-player/app/src/main/jniLibs')
    if manifest != current:
        raise ValueError('binary manifest is stale or not generated from current native/source inputs')
    files = source_files()
    public = {'schemaVersion': 1, 'sourceLockSha256': digest(LOCK_PATH),
              'files': {p.relative_to(ROOT).as_posix(): {'sha256': digest(p), 'bytes': p.stat().st_size} for p in files},
              'archives': {p['filename']: {'sha256': p['sha256'], 'bytes': p['bytes']} for p in lock['sources']}}
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as out:
            temporary = Path(out.name)
            with gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode='w') as tf:
                    for path in files:
                        info = tf.gettarinfo(str(path), arcname='movo-source/' + path.relative_to(ROOT).as_posix())
                        info.mtime = 0
                        info.uid = info.gid = 0
                        info.uname = info.gname = ''
                        with path.open('rb') as stream:
                            tf.addfile(info, stream)
                    for pin in lock['sources']:
                        path = paths[pin['name']]
                        check_archive(path, pin)
                        info = tarfile.TarInfo('movo-source/source-archives/' + pin['filename'])
                        info.size = pin['bytes']
                        info.mode = 0o644
                        with path.open('rb') as stream:
                            tf.addfile(info, stream)
                    tar_bytes(tf, 'movo-source/source-manifest.json', (json.dumps(public, indent=2)+'\n').encode())
                    tar_bytes(tf, 'movo-source/native-binaries.json', (json.dumps(manifest, indent=2)+'\n').encode())
        if destination.exists():
            raise FileExistsError(destination)
        temporary.rename(destination)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()
    checksum = digest(destination)
    with checksum_path.open('x') as out:
        out.write(f'{checksum}  {destination.name}\n')
    print(f'{destination}\n{checksum}  {destination.name}')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('prepare', 'bundle'):
        p = sub.add_parser(command)
        p.add_argument('--cache-dir', action='append', type=Path, help='repeatable offline cache search path; first is download destination')
        p.add_argument('--download', action='store_true', help='fetch missing archives only; every archive must match the lock')
        p.add_argument('--output', type=Path, required=True, help='new destination tree (prepare) or .tar.gz file (bundle)')
        if command == 'bundle':
            p.add_argument('--binary-manifest', type=Path, required=True)
    p = sub.add_parser('record-binaries')
    p.add_argument('--library-dir', type=Path, default=ROOT / 'mpv-player/app/src/main/jniLibs')
    p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lock = lock_data()
    if args.command == 'record-binaries':
        write_json_new(args.output, record_binaries(lock, args.library_dir))
        print(args.output)
        return
    caches = args.cache_dir or [ROOT / 'source-archives']
    paths = archives(lock, caches, args.download)
    if args.command == 'prepare':
        prepare(lock, paths, args.output)
    else:
        bundle(lock, paths, args.binary_manifest, args.output)


if __name__ == '__main__':
    main()
