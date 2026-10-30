"""ADB boundary and journalled, reversible package changes. No uninstall or data clearing."""
import hashlib
import json
import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from datetime import datetime

from catalog import candidates, protected, FEATURES

PACKAGE = re.compile(r'(?:android|[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\Z')
COMPONENT = re.compile(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+\Z')
PEACH = 'com.gathin.tvhome.backdrops/com.gathin.tvhome.MainActivity'


def packages(output):
    result = set()
    for line in output.splitlines():
        if line.startswith('package:'):
            package = line[8:].strip()
            if not PACKAGE.fullmatch(package):
                raise ValueError('ADB вернул некорректное имя пакета')
            result.add(package)
    return result


def component(output):
    return next((s.strip() for s in reversed(output.splitlines()) if COMPONENT.fullmatch(s.strip())), '')


@dataclass
class Device:
    serial: str
    identity: str
    user: int
    props: dict[str, str]
    installed: set[str]
    disabled: set[str]
    user_apps: set[str]
    home: str
    protected_packages: set[str] = field(default_factory=set)


class Adb:
    def __init__(self, executable, serial='', log=None):
        self.executable, self.serial, self.log = str(executable), serial, log

    def run(self, *args, target=True, timeout=40):
        command = [self.executable] + (['-s', self.serial] if target and self.serial else []) + list(args)
        result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace',
                                timeout=timeout, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        output = result.stdout.strip()
        if self.log:
            self.log.parent.mkdir(parents=True, exist_ok=True)
            with self.log.open('a', encoding='utf-8') as stream:
                stream.write(json.dumps({'time': datetime.now().isoformat(), 'args': args,
                                         'code': result.returncode, 'stdout': output, 'stderr': result.stderr}, ensure_ascii=False) + '\n')
        if result.returncode or re.search(r'(^|\n)(Error:|Security exception:|Failure\b)', output):
            raise RuntimeError(output + '\n' + result.stderr.strip())
        return output

    def shell(self, *args):
        return self.run('shell', *map(str, args))


def discover(adb, target):
    if target:
        if not re.fullmatch(r'[A-Za-z0-9_.:\[\]-]+', target):
            raise ValueError('Укажите IP:порт или серийный номер USB без пробелов')
        if '.' in target or ':' in target:
            target = target if ':' in target else target + ':5555'
            adb.run('connect', target, target=False)
    else:
        devices = [line.split()[0] for line in adb.run('devices', target=False).splitlines()
                   if len(line.split()) == 2 and line.split()[1] == 'device']
        if len(devices) != 1:
            raise ValueError('Укажите IP или USB-серийный номер. Подтвердите отладку на ТВ; при нескольких устройствах выберите одно явно.')
        target = devices[0]
    adb.serial = target
    if adb.run('get-state') != 'device':
        raise RuntimeError('Подтвердите разрешение отладки на телевизоре')
    props = dict(re.findall(r'^\[([^\]]+)\]: \[(.*)\]$', adb.shell('getprop'), re.M))
    user_text = adb.shell('am', 'get-current-user').strip()
    if not user_text.isdecimal():
        raise ValueError('Не удалось определить пользователя Android')
    user = int(user_text)
    identity = device_identity(adb, user, props)
    active_services = set()
    for key in ('enabled_input_methods', 'default_input_method', 'enabled_accessibility_services'):
        value = adb.shell('settings', '--user', user, 'get', 'secure', key)
        for entry in value.split(':'):
            name = entry.split(';')[0].strip()
            if COMPONENT.fullmatch(name): active_services.add(name.split('/')[0])
    return Device(target, identity, user, props,
                  packages(adb.shell('pm', 'list', 'packages', '--user', user)),
                  packages(adb.shell('pm', 'list', 'packages', '-d', '--user', user)),
                  packages(adb.shell('pm', 'list', 'packages', '-3', '--user', user)),
                  component(adb.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', user,
                                      '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')),
                  active_services)


def device_identity(adb, user, props):
    android_id = adb.shell('settings', '--user', user, 'get', 'secure', 'android_id').strip()
    if android_id in ('', 'null', '0'):
        raise ValueError('Не удалось прочитать идентификатор устройства; изменения запрещены')
    text = '\n'.join([android_id, str(user), props.get('ro.serialno', ''), props.get('ro.product.model', '')])
    return hashlib.sha256(text.encode()).hexdigest()


def save_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def apply_plan(adb, device, selected, keep, apk, set_home, backup_root, progress=lambda text: None):
    # Re-read at the mutation boundary: an IP can now belong to a different TV.
    current = discover(adb, device.serial)
    if current.identity != device.identity:
        raise ValueError('Устройство изменилось. Подключитесь заново и проверьте план.')
    _, available = candidates(current.props, current.installed, current.user_apps, current.home.split('/')[0])
    for package in selected:
        if package not in available or protected(package, current.home.split('/')[0]) or package in current.protected_packages:
            raise ValueError('Пакет не разрешён для этого устройства: ' + package)
        if set(available[package].features).intersection(keep):
            raise ValueError('Пакет нужен сохраняемой функции: ' + package)
    if set_home and not (apk or PEACH.split('/')[0] in current.installed):
        raise ValueError('Сначала установите PeachOS TV')
    if set_home and not current.home:
        raise ValueError('Исходный Home не определён. Назначьте лаунчер вручную в настройках Android.')
    if apk and (not apk.is_file() or apk.suffix.lower() != '.apk'):
        raise ValueError('Выберите существующий APK лаунчера')
    folder = backup_root / (datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    folder.mkdir(parents=True)
    journal = {'version': 1, 'identity': current.identity, 'serial': current.serial, 'user': current.user,
               'props': current.props, 'installed': sorted(current.installed), 'disabled': sorted(current.disabled),
               'original_home': current.home, 'home_attempted': False, 'changes': [], 'complete': False}
    path = folder / 'restore.json'
    save_json(path, journal)
    progress('Резервная копия: ' + str(path))
    # Capture every original state BEFORE the first package change.
    states = {}
    for package in selected:
        if package in current.disabled:
            continue
        dump = adb.shell('dumpsys', 'package', package)
        match = re.search(r'User ' + str(current.user) + r':[^\n]*\benabled=(\d+)', dump)
        if not match or int(match[1]) not in (0, 1):
            raise ValueError('Не определено исходное состояние ' + package + '; ничего не отключено. Копия: ' + str(path))
        states[package] = int(match[1])
    if apk:
        progress('Установка APK без удаления данных…')
        journal['apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        save_json(path, journal)
        adb.run('install', '-r', str(apk), timeout=180)
    if set_home:
        progress('Назначение PeachOS главным экраном…')
        journal['home_attempted'] = True
        save_json(path, journal)
        adb.shell('cmd', 'package', 'set-home-activity', '--user', current.user, PEACH)
        resolved = component(adb.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', current.user,
                                       '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))
        if resolved != PEACH:
            adb.shell('cmd', 'package', 'set-home-activity', '--user', current.user, current.home)
            raise RuntimeError('Прошивка не подтвердила новый Home. Исходный Home возвращён; очистка не выполнялась.')
    for index, (package, state) in enumerate(states.items(), 1):
        progress(f'Отключение {index}/{len(states)}: {package}')
        # Intent is durable before shell runs: even a timeout/disconnect can be restored.
        journal['changes'].append({'package': package, 'original': state, 'confirmed': False})
        save_json(path, journal)
        adb.shell('pm', 'disable-user', '--user', current.user, package)
        disabled = packages(adb.shell('pm', 'list', 'packages', '-d', '--user', current.user))
        if package not in disabled:
            raise RuntimeError('Отключение не подтверждено: ' + package + '. Восстановление: ' + str(path))
        journal['changes'][-1]['confirmed'] = True
        save_json(path, journal)
    journal['complete'] = True
    save_json(path, journal)
    return path


def read_restore(path):
    raw = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(raw, dict) or raw.get('version') != 1:
        raise ValueError('Неизвестный формат резервной копии')
    if not isinstance(raw.get('identity'), str) or not re.fullmatch(r'[0-9a-f]{64}', raw['identity']):
        raise ValueError('Некорректный идентификатор копии')
    if type(raw.get('user')) is not int or raw['user'] < 0 or not isinstance(raw.get('changes'), list):
        raise ValueError('Некорректный пользователь или журнал')
    for change in raw['changes']:
        if not isinstance(change, dict) or not isinstance(change.get('package'), str) or not PACKAGE.fullmatch(change['package']):
            raise ValueError('Некорректное имя пакета в журнале')
        if type(change.get('original')) is not int or change['original'] not in (0, 1):
            raise ValueError('Некорректное исходное состояние')
    home = raw.get('original_home', '')
    if not isinstance(home, str) or home and not COMPONENT.fullmatch(home):
        raise ValueError('Некорректный исходный Home')
    if type(raw.get('home_attempted')) is not bool:
        raise ValueError('Некорректная запись Home')
    return raw


def read_preset(path):
    if path.stat().st_size > 65536:
        raise ValueError('Файл выбора слишком большой')
    raw = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(raw, dict) or raw.get('version') != 1 or not isinstance(raw.get('packages'), list) or not isinstance(raw.get('keep'), dict):
        raise ValueError('Неизвестный формат выбора')
    if any(not isinstance(p, str) or not PACKAGE.fullmatch(p) for p in raw['packages']):
        raise ValueError('Некорректное имя пакета в выборе')
    if any(k not in FEATURES or type(v) is not bool for k, v in raw['keep'].items()):
        raise ValueError('Некорректные функции в выборе')
    return raw


def restore(adb, device, path, progress=lambda text: None):
    journal = read_restore(path)
    current = discover(adb, device.serial)
    if current.identity != journal['identity'] or current.user != journal['user']:
        raise ValueError('Копия относится к другому устройству или пользователю')
    for entry in reversed(journal['changes']):
        progress('Восстановление: ' + entry['package'])
        adb.shell('pm', 'default-state' if entry['original'] == 0 else 'enable',
                  '--user', current.user, entry['package'])
    if journal['home_attempted'] and journal['original_home']:
        adb.shell('cmd', 'package', 'set-home-activity', '--user', current.user, journal['original_home'])
    disabled = packages(adb.shell('pm', 'list', 'packages', '-d', '--user', current.user))
    if any(entry['package'] in disabled for entry in journal['changes']):
        raise RuntimeError('Некоторые пакеты не восстановлены; смотрите журнал и повторите после подключения')
    return path
