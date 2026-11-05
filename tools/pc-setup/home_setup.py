"""Capability-based Home setup. No vendor package disabling or theme rewriting."""
import base64
import json
import re
import time
from dataclasses import dataclass, asdict, field

PACKAGE = 'com.gathin.tvhome.backdrops'
ACTIVITY = PACKAGE + '/com.gathin.tvhome.MainActivity'
SERVICE = PACKAGE + '/com.gathin.tvhome.HomeKeyService'
RECEIVER = PACKAGE + '/com.gathin.tvhome.HomeSetupReceiver'
PACKAGE_NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+\Z')
COMPONENT = re.compile(r'([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\Z')
APP_MODES = {'allow', 'ignore', 'deny', 'default', 'foreground'}
STEPS = {'bridge', 'autostart', 'services', 'master'}


def service_list(value):
    if value in ('', 'null'):
        return ()
    result = []
    for entry in value.split(':'):
        match = COMPONENT.fullmatch(entry)
        if not match:
            raise ValueError('Android вернул некорректный список служб доступности; изменения остановлены.')
        package, name = match.groups()
        name = package + name if name.startswith('.') else name
        result.append(package + '/' + name)
    return tuple(dict.fromkeys(result))


@dataclass(frozen=True)
class BridgeState:
    stock_package: str
    filter_keys: bool

    @classmethod
    def parse(cls, value):
        if not isinstance(value, dict) or set(value) != {'stock_package', 'filter_keys'}:
            raise ValueError('Некорректный снимок совместимого Home.')
        package = value['stock_package']
        if not isinstance(package, str) or len(package) > 255 or package and (
                not PACKAGE_NAME.fullmatch(package) or package in (PACKAGE, 'android')):
            raise ValueError('Некорректный пакет перенаправления Home.')
        if type(value['filter_keys']) is not bool:
            raise ValueError('Некорректный режим кнопок Home.')
        return cls(package, value['filter_keys'])


@dataclass(frozen=True)
class BridgeStatus:
    state: BridgeState
    bound: bool
    applied_stock_package: str
    filter_keys_active: bool


def bridge(adb, user, state=None):
    args = ['am', 'broadcast', '--user', user, '--include-stopped-packages', '-n', RECEIVER,
            '-a', 'com.gathin.tvhome.HOME_SETUP', '--es', 'operation', 'read' if state is None else 'write']
    if state is not None:
        args += ['--es', 'stock_package', state.stock_package, '--ez', 'filter_keys', str(state.filter_keys).lower()]
    output = adb.shell(*args)
    match = re.search(r'peach-home-v1:([A-Za-z0-9+/=]+)', output)
    if not match:
        raise RuntimeError('Установленный APK не подтвердил настройку Home. Нужна версия с поддержкой совместимого Home.')
    if len(match[1]) > 8192:
        raise ValueError('Ответ службы настройки Home слишком большой.')
    try:
        value = json.loads(base64.b64decode(match[1], validate=True).decode('utf-8'))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ValueError('Некорректный ответ службы настройки Home.') from error
    if not isinstance(value, dict) or type(value.get('version')) is not int or value['version'] != 1:
        raise ValueError('Неизвестный протокол настройки Home.')
    if 'error' in value:
        if not isinstance(value['error'], str):
            raise ValueError('Некорректный ответ ошибки Home.')
        raise RuntimeError('Лаунчер отклонил настройку Home: ' + value['error'][:300])
    if type(value.get('bound')) is not bool or type(value.get('filter_keys_active')) is not bool or not isinstance(value.get('applied_stock_package'), str):
        raise ValueError('Некорректный статус службы Home.')
    snapshot = BridgeState.parse({key: value.get(key) for key in ('stock_package', 'filter_keys')})
    BridgeState.parse({'stock_package': value['applied_stock_package'], 'filter_keys': False})
    return BridgeStatus(snapshot, value['bound'], value['applied_stock_package'], value['filter_keys_active'])


@dataclass(frozen=True)
class AutoStart:
    restore_mode: str
    effective_mode: str


def autostart(adb, user):
    output = adb.run('shell', 'cmd', 'appops', 'get', '--user', str(user), PACKAGE, 'AUTO_START', check=False)
    explicit = re.search(r'^AUTO_START:\s*(\w+)', output, re.M)
    default = re.search(r'^Default mode:\s*(\w+)', output, re.M)
    if explicit and explicit[1] in APP_MODES:
        return AutoStart(explicit[1], explicit[1])
    if default and default[1] in APP_MODES:
        return AutoStart('default', default[1])
    # AOSP and other firmware may have no such operation. Do not invent numeric IDs.
    return None


def verify_home(adb, user, timeout=6):
    # Starting an activity does not wake a TV from its dream/standby surface.
    # Do not use am's -W: some OEM Home activities never report launch completion.
    adb.run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', timeout=8)
    # Test a transition, not a re-delivery to an already visible stock Home:
    # accessibility redirects correctly receive a new window event only then.
    adb.run('shell', 'am', 'start', '--user', str(user), '-n', ACTIVITY, timeout=8)
    output = adb.run('shell', 'am', 'start', '--user', str(user),
                     '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', timeout=8, check=False)
    if re.search(r'Error:|Exception|unable to resolve|device .*not found|unauthorized', output, re.I):
        return False
    deadline, stable_since = time.monotonic() + timeout, None
    while time.monotonic() < deadline:
        activities = adb.run('shell', 'dumpsys', 'activity', 'activities', timeout=5)
        names = re.findall(r'(?:topResumedActivity|mResumedActivity)\s*[=:]\s*ActivityRecord\{[^\n]*?\bu'
                           + str(user) + r'\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)', activities)
        foreground = next(iter(names), '')
        if foreground == ACTIVITY:
            if stable_since is None:
                stable_since = time.monotonic()
            elif time.monotonic() - stable_since >= .4:
                return True
        else:
            stable_since = None
        time.sleep(.15)
    return False


@dataclass
class HomeChange:
    before: BridgeState
    target: BridgeState
    services: tuple[str, ...]
    master: str
    auto_mode: str | None
    attempted: list[str] = field(default_factory=list)
    complete: bool = False

    def encode(self):
        return asdict(self)

    @classmethod
    def parse(cls, value):
        if not isinstance(value, dict) or set(value) != {
                'before', 'target', 'services', 'master', 'auto_mode', 'attempted', 'complete'}:
            raise ValueError('Некорректный журнал совместимого Home.')
        before, target = BridgeState.parse(value['before']), BridgeState.parse(value['target'])
        services = value['services']
        if not isinstance(services, list) or any(not isinstance(s, str) for s in services):
            raise ValueError('Некорректный журнал служб пульта.')
        parsed_services = service_list(':'.join(services))
        if tuple(services) != parsed_services or value['master'] not in ('0', '1', 'null'):
            raise ValueError('Некорректное исходное состояние доступности.')
        if value['auto_mode'] is not None and (not isinstance(value['auto_mode'], str) or value['auto_mode'] not in APP_MODES):
            raise ValueError('Некорректный исходный режим автозапуска.')
        steps = value['attempted']
        if not isinstance(steps, list) or any(not isinstance(s, str) or s not in STEPS for s in steps) or len(set(steps)) != len(steps):
            raise ValueError('Некорректный список операций Home.')
        if type(value['complete']) is not bool or not target.stock_package:
            raise ValueError('Некорректный результат совместимого Home.')
        if 'autostart' in steps and value['auto_mode'] is None:
            raise ValueError('Не сохранено исходное разрешение автозапуска.')
        return cls(before, target, parsed_services, value['master'], value['auto_mode'], steps, value['complete'])


def restore_compat(adb, user, change):
    # The parsed journal contains only fixed operations and the fixed PeachOS receiver.
    # Preserve unrelated services added after installation and reject theme/user conflicts.
    if 'bridge' in change.attempted:
        current = bridge(adb, user).state
        if current == change.target:
            if bridge(adb, user, change.before).state != change.before:
                raise RuntimeError('Лаунчер не подтвердил восстановление исходной настройки Home.')
        elif current != change.before:
            raise RuntimeError('Настройка Home изменена после установки; её новое значение сохранено. Проверьте восстановление вручную.')
    live = service_list(adb.shell('settings', '--user', user, 'get', 'secure', 'enabled_accessibility_services'))
    if 'services' in change.attempted and SERVICE not in change.services and SERVICE in live:
        live = tuple(s for s in live if s != SERVICE)
        if live:
            adb.shell('settings', '--user', user, 'put', 'secure', 'enabled_accessibility_services', ':'.join(live))
        else:
            adb.shell('settings', '--user', user, 'delete', 'secure', 'enabled_accessibility_services')
    if 'master' in change.attempted and change.master != '1' and not (set(live) - set(change.services)):
        if change.master == 'null':
            adb.shell('settings', '--user', user, 'delete', 'secure', 'accessibility_enabled')
        else:
            adb.shell('settings', '--user', user, 'put', 'secure', 'accessibility_enabled', change.master)
    if 'autostart' in change.attempted:
        mode = autostart(adb, user)
        if mode is None:
            raise RuntimeError('Операция автозапуска больше не поддерживается; проверьте разрешение PeachOS вручную.')
        if mode.effective_mode == 'allow':
            adb.shell('cmd', 'appops', 'set', '--user', user, PACKAGE, 'AUTO_START', change.auto_mode)
            adb.shell('cmd', 'appops', 'write-settings')
            restored = autostart(adb, user)
            if restored is None or restored.restore_mode != change.auto_mode:
                raise RuntimeError('Прошивка не подтвердила восстановление исходного автозапуска.')
        elif mode.restore_mode != change.auto_mode:
            raise RuntimeError('Разрешение автозапуска изменено после установки; новое значение сохранено.')


def configure_compat(adb, user, stock_component, journal, save, progress=lambda text: None):
    stock = stock_component.split('/')[0]
    target = BridgeState.parse({'stock_package': stock, 'filter_keys': False})
    status = bridge(adb, user)
    services = service_list(adb.shell('settings', '--user', user, 'get', 'secure', 'enabled_accessibility_services'))
    master = adb.shell('settings', '--user', user, 'get', 'secure', 'accessibility_enabled')
    if master not in ('0', '1', 'null'):
        raise ValueError('Не определено состояние служб доступности; совместимый Home не менялся.')
    mode = autostart(adb, user)
    # A helper already used for remote Settings retains its existing filtering policy.
    if SERVICE in services:
        target = BridgeState(stock, status.filter_keys_active if status.bound else status.state.filter_keys)
    change = HomeChange(status.state, target, services, master, None if mode is None else mode.restore_mode)
    journal['home_compat'] = change.encode()
    save()
    try:
        if status.state != target:
            change.attempted.append('bridge')
            journal['home_compat'] = change.encode(); save()
            if bridge(adb, user, target).state != target:
                raise RuntimeError('Лаунчер не сохранил совместимый Home.')
        if mode is not None and mode.effective_mode != 'allow':
            change.attempted.append('autostart')
            journal['home_compat'] = change.encode(); save()
            adb.shell('cmd', 'appops', 'set', '--user', user, PACKAGE, 'AUTO_START', 'allow')
            adb.shell('cmd', 'appops', 'write-settings')
            confirmed = autostart(adb, user)
            if confirmed is None or confirmed.effective_mode != 'allow':
                raise RuntimeError('Прошивка не разрешила автозапуск PeachOS.')
        adb.run('shell', 'am', 'start', '--user', str(user), '-n', ACTIVITY, timeout=8)
        live_services = service_list(adb.shell('settings', '--user', user, 'get', 'secure', 'enabled_accessibility_services'))
        if SERVICE not in live_services:
            change.attempted.append('services')
            journal['home_compat'] = change.encode(); save()
            adb.shell('settings', '--user', user, 'put', 'secure', 'enabled_accessibility_services', ':'.join((*live_services, SERVICE)))
        if master != '1':
            change.attempted.append('master')
            journal['home_compat'] = change.encode(); save()
            adb.shell('settings', '--user', user, 'put', 'secure', 'accessibility_enabled', '1')
        progress('Проверка службы пульта и фактического перехода Home…')
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            status = bridge(adb, user)
            if status.bound and status.applied_stock_package == target.stock_package:
                break
            time.sleep(.25)
        else:
            raise RuntimeError('Прошивка не запустила службу пульта. Очистка остановлена; проверьте разрешение автозапуска на ТВ.')
        if not verify_home(adb, user):
            raise RuntimeError('Служба запущена, но Home не открыл PeachOS. Очистка остановлена.')
        change.complete = True
        journal['home_compat'] = change.encode(); save()
    except Exception as failure:
        try:
            restore_compat(adb, user, change)
        except Exception as rollback_failure:
            raise RuntimeError(str(failure) + '\nВосстановление не завершено: ' + str(rollback_failure)
                               + '\nИспользуйте сохранённую копию после восстановления ADB.') from failure
        raise
