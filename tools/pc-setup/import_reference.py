"""Build a reproducible, read-only evidence index from CleanYourTV's example lists."""
import argparse
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
args = parser.parse_args()
revision = subprocess.check_output(['git', '-C', str(args.source), 'rev-parse', 'HEAD'], text=True).strip()
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
Path(__file__).with_name('reference-catalog.json').write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'{profiles} device lists; {len(entries)} package references; revision {revision}')
