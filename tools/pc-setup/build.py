"""Build on the target OS. Python, Tk, ADB and APKs are bundled for end users."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--adb-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--airplay-apk', type=Path)
    parser.add_argument('--airplay-source', type=Path)
    parser.add_argument('--airplay-license', type=Path)
    args = parser.parse_args()
    if sys.platform not in ('darwin', 'linux'):
        parser.error('Build on macOS or Linux; use build.ps1 on Windows.')
    companions = (args.airplay_apk, args.airplay_source, args.airplay_license)
    if any(companions) and not all(companions):
        parser.error('AirPlay requires APK, complete corresponding source ZIP and license.')
    here = Path(__file__).resolve().parent
    repo = here.parent.parent
    output = args.output.resolve()
    stage = output / 'build' / 'assets'
    stage.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.apk) as archive:
        if 'AndroidManifest.xml' not in archive.namelist():
            parser.error('Launcher APK is missing AndroidManifest.xml.')
    resources = [(args.apk, 'PeachOS-TV.apk')]
    if all(companions):
        with zipfile.ZipFile(args.airplay_apk) as archive:
            names = archive.namelist()
            if 'AndroidManifest.xml' not in names or not any(n.startswith('lib/') and n.endswith('.so') for n in names):
                parser.error('AirPlay APK must contain AndroidManifest.xml and native libraries.')
        with zipfile.ZipFile(args.airplay_source) as archive:
            if not archive.namelist():
                parser.error('AirPlay corresponding source archive is empty.')
        resources += list(zip(companions, ('PeachOS-AirPlay.apk', 'PeachOS-AirPlay-source.zip', 'LICENSE-AirPlay.txt')))
    command = [sys.executable, '-m', 'PyInstaller', '--noconfirm', '--clean', '--onedir',
               '--name', 'PeachOS-TV-Setup', '--distpath', str(output),
               '--workpath', str(output / 'build' / 'pyinstaller'), '--specpath', str(output / 'build')]
    for source, name in resources:
        destination = stage / name
        shutil.copyfile(source, destination)
        command += ['--add-data', f'{destination}{os.pathsep}.']
    for file in (here / 'README.ru.md', here / 'peachos.png', here / 'reference-catalog.json', repo / 'LICENSE', repo / 'LICENSE-CleanYourTV'):
        command += ['--add-data', f'{file}{os.pathsep}.']
    adb = args.adb_directory.resolve()
    # Keep the official directory, including shared libraries required by newer ADBs.
    subprocess.run([str(adb / 'adb'), 'version'], check=True)
    if not (adb / 'NOTICE.txt').is_file():
        parser.error('Official platform-tools NOTICE.txt is required.')
    command += ['--add-data', f'{adb}{os.pathsep}platform-tools']
    if sys.platform == 'darwin':
        iconset = stage / 'peachos.iconset'
        iconset.mkdir(exist_ok=True)
        mac_icon = stage / 'peachos-mac.png'
        subprocess.run(['swift', str(here / 'mac_icon.swift'), str(mac_icon)], check=True)
        for size in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                name = f'icon_{size}x{size}' + ('@2x' if scale == 2 else '') + '.png'
                subprocess.run(['sips', '-z', str(size * scale), str(size * scale), str(mac_icon), '--out', str(iconset / name)], check=True, stdout=subprocess.DEVNULL)
        icon = stage / 'peachos.icns'
        subprocess.run(['iconutil', '-c', 'icns', str(iconset), '-o', str(icon)], check=True)
        command += ['--windowed', '--icon', str(icon), '--osx-bundle-identifier', 'tv.peachos.setup']
    command.append(str(here / 'wizard.py'))
    subprocess.run(command, check=True)
    machine = os.uname().machine
    if sys.platform == 'darwin':
        app = output / 'PeachOS-TV-Setup.app'
        image_root = output / 'build' / 'dmg'
        image_root.mkdir(exist_ok=True)
        shutil.copytree(app, image_root / app.name, dirs_exist_ok=True, symlinks=True)
        (image_root / 'Applications').symlink_to('/Applications', target_is_directory=True)
        artifact = output / f'PeachOS-TV-Setup-macOS-{machine}.dmg'
        subprocess.run(['hdiutil', 'create', '-ov', '-volname', 'PeachOS TV Setup', '-srcfolder', str(image_root), '-format', 'UDZO', str(artifact)], check=True)
    else:
        bundle = output / 'PeachOS-TV-Setup'
        launcher = bundle / 'Launch-PeachOS.sh'
        launcher.write_text('#!/bin/sh\ncd -- "$(dirname -- "$0")" || exit 1\nexec ./PeachOS-TV-Setup "$@"\n', encoding='utf-8')
        launcher.chmod(0o755)
        artifact = output / f'PeachOS-TV-Setup-Linux-{machine}.tar.gz'
        with tarfile.open(artifact, 'w:gz') as archive:
            archive.add(bundle, arcname=bundle.name)
    with artifact.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    artifact.with_suffix(artifact.suffix + '.sha256').write_text(digest + '  ' + artifact.name + '\n', encoding='utf-8')
    print('Ready:', artifact)


if __name__ == '__main__':
    main()
