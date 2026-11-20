"""Host assets and persistent ADB cache, independent of the UI toolkit."""
import hashlib
import shutil
import sys

def cache_adb(assets, data, adb_name):
    # ADB's server can outlive the GUI. Run it from the persistent portable cache,
    # so it does not lock the one-file runtime's temporary extraction directory.
    target = data / 'platform-tools'
    target.mkdir(parents=True, exist_ok=True)
    for source in sorted((assets / 'platform-tools').rglob('*')):
        if not source.is_file():
            continue
        destination = target / source.relative_to(assets / 'platform-tools')
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            with source.open('rb') as original, destination.open('rb') as cached:
                if hashlib.file_digest(original, 'sha256').digest() == hashlib.file_digest(cached, 'sha256').digest():
                    continue
        shutil.copyfile(source, destination)
    executable = target / adb_name
    if sys.platform != 'win32':
        executable.chmod(executable.stat().st_mode | 0o100)
    return executable

