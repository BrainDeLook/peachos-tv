"""Build a reproducible, read-only evidence index from CleanYourTV's example lists."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
operation = parser.add_mutually_exclusive_group(required=True)
operation.add_argument('source', type=Path, nargs='?')
operation.add_argument('--verify', action='store_true')
args = parser.parse_args()
catalog = Path(__file__).with_name('reference-catalog.json')
pin = catalog.with_name('reference-catalog.lock.json')
if args.verify:
    contents = catalog.read_text(encoding='utf-8')
    locked = json.loads(pin.read_text(encoding='utf-8'))
    if (json.loads(contents)['revision'] != locked['revision']
            or hashlib.sha256(contents.encode('utf-8')).hexdigest() != locked['sha256']):
        parser.error('Reference catalog does not match its pinned revision and SHA-256.')
    raise SystemExit(0)
revision = subprocess.check_output(['git', '-C', str(args.source), 'rev-parse', 'HEAD'], text=True).strip()
if subprocess.check_output(['git', '-C', str(args.source), 'status', '--porcelain', '--untracked-files=all', '--', 'examples'], text=True).strip():
    parser.error('Upstream examples must match the checked-out commit.')
entries = {}
profiles = 0
for file in sorted((args.source / 'examples').glob('*/debloat.txt')):
    profiles += 1
    for line in file.read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        optional = line.startswith('#')
        package, _, note = (line[1:].strip() if optional else line).partition('#')
        package = package.strip()
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+', package):
            continue
        entry = entries.setdefault(package, {'profiles': [], 'optional_profiles': [], 'notes': []})
        group = entry['optional_profiles' if optional else 'profiles']
        if file.parent.name not in group:
            group.append(file.parent.name)
        note = note.strip()[:240]
        if note and note not in entry['notes'] and len(entry['notes']) < 3:
            entry['notes'].append(note)
output = {'repository': 'https://github.com/Gathin23/cleanyourtv', 'revision': revision,
          'profile_count': profiles, 'packages': entries}
contents = (json.dumps(output, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
catalog.write_bytes(contents)
pin.write_text(json.dumps({'revision': revision, 'sha256': hashlib.sha256(contents).hexdigest()}, indent=2) + '\n',
               encoding='utf-8', newline='\n')
print(f'{profiles} device lists; {len(entries)} package references; revision {revision}')
