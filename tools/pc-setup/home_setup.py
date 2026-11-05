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
USE_REASONS = {'home', 'settings', 'remaps', 'learning'}
GENERATION = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z')


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
    install_generation: str | None = None
    remote_required: bool | None = None
    config_ready: bool = False
    service_use_reasons: frozenset[str] = frozenset()


def bridge(adb, user, state=None, *, operation='read'):
    args = ['am', 'broadcast', '--user', user, '--include-stopped-packages', '-n', RECEIVER,
            '-a', 'com.gathin.tvhome.HOME_SETUP', '--es', 'operation', operation if state is None else 'write']
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
    generation = value.get('install_generation')
    remote_required, ready, reasons = None, False, frozenset()
    if any(key in value for key in ('install_generation', 'remote_required', 'config_ready', 'service_use_reasons')):
        raw_reasons = value.get('service_use_reasons')
        if (not isinstance(generation, str) or not GENERATION.fullmatch(generation)
                or type(value.get('remote_required')) is not bool or type(value.get('config_ready')) is not bool
                or not isinstance(raw_reasons, list)
                or any(not isinstance(reason, str) or reason not in USE_REASONS for reason in raw_reasons)
                or len(set(raw_reasons)) != len(raw_reasons)):
            raise ValueError('Некорректное поколение или основания использования службы Home.')
        reasons = frozenset(raw_reasons)
        remote_required, ready = value['remote_required'], value['config_ready']
        if remote_required != bool(reasons - {'home'}):
            raise ValueError('Несогласованные основания использования службы Home.')
    return BridgeStatus(snapshot, value['bound'], value['applied_stock_package'], value['filter_keys_active'],
                        generation, remote_required, ready, reasons)


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


@dataclass(frozen=True)
class HomeVerification:
    intent: str = 'unverified'
    keyevent: str = 'unverified'
    physical: str = 'unverified'
    reason: str = ''

    def __bool__(self):
        return self.intent == self.keyevent == 'passed'

    def encode(self):
        return asdict(self)


def foreground_activity(adb, user):
    activities = adb.run('shell', 'dumpsys', 'activity', 'activities', timeout=5)
    names = re.findall(r'(?:topResumedActivity|mResumedActivity)\s*[=:]\s*ActivityRecord\{[^\n]*?\bu'
                       + str(user) + r'\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)', activities)
    name = next(iter(names), '')
    if name:
        package, activity = name.split('/')
        return package + '/' + (package + activity if activity.startswith('.') else activity)
    return ''


def home_roles(adb, user, sdk):
    if not sdk.isdecimal() or int(sdk) < 29:
        return None
    output = adb.run('shell', 'cmd', 'role', 'get-role-holders', '--user', str(user), 'android.app.role.HOME', check=False)
    if re.search(r'error|exception|unknown|not found|not supported', output, re.I):
        return None
    names = tuple(line.strip() for line in output.splitlines() if line.strip())
    if any(not PACKAGE_NAME.fullmatch(name) for name in names) or len(names) > 1:
        raise ValueError('Некорректный ответ роли Home; назначение не изменено.')
    return names


def verify_home(adb, user, timeout=6):
    """Two causal ADB smoke tests. Neither is a claim about a physical remote."""
    adb.run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', timeout=8)
    # A known different foreground is mandatory. Pre-opening MainActivity lets an
    # asynchronously dispatched HOME falsely pass against its old window.
    origin = PACKAGE + '/com.gathin.tvhome.LauncherSettingsActivity'
    use_bridge = True
    try:
        bridge(adb, user, operation='verify_origin')
    except RuntimeError:
        # Older APKs do not expose verify_origin. A resolved standard Settings
        # screen is a safe fallback; never use an arbitrary app or send Back.
        use_bridge = False
        resolved = adb.run('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', str(user),
                           '-a', 'android.settings.SETTINGS', timeout=8, check=False)
        origin = next((line.strip() for line in reversed(resolved.splitlines())
                       if COMPONENT.fullmatch(line.strip())), '')
        if not origin or origin == ACTIVITY:
            return HomeVerification(reason='verification_origin_unavailable')
        package, activity = origin.split('/')
        origin = package + '/' + (package + activity if activity.startswith('.') else activity)

    results = {}
    for phase in ('intent', 'keyevent'):
        if not use_bridge:
            output = adb.run('shell', 'am', 'start', '--user', str(user), '-n', origin, timeout=8, check=False)
            if re.search(r'Error:|Exception|unable to resolve|device .*not found|unauthorized', output, re.I):
                return HomeVerification(**results, reason='verification_origin_unavailable')
        elif phase != 'intent':
            bridge(adb, user, operation='verify_origin')
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if foreground_activity(adb, user) == origin:
                break
            time.sleep(.15)
        else:
            return HomeVerification(**results, reason='verification_origin_not_observed')

        if phase == 'intent':
            output = adb.run('shell', 'am', 'start', '--user', str(user),
                             '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME', timeout=8, check=False)
        else:
            output = adb.run('shell', 'input', 'keyevent', 'KEYCODE_HOME', timeout=8, check=False)
        if re.search(r'Error:|Exception|unable to resolve|device .*not found|unauthorized', output, re.I):
            results[phase] = 'failed'
            return HomeVerification(**results, reason=phase + '_dispatch_failed')
        deadline, stable_since = time.monotonic() + timeout, None
        # Observe the whole bounded window. A transient Peach frame followed by
        # an OEM redirect must not be committed as success before that redirect.
        while time.monotonic() < deadline:
            if foreground_activity(adb, user) == ACTIVITY:
                if stable_since is None: stable_since = time.monotonic()
            else:
                stable_since = None
            time.sleep(.15)
        if stable_since is None or time.monotonic() - stable_since < .4:
            results[phase] = 'failed'
            return HomeVerification(**results, reason=phase + '_home_not_observed')
        results[phase] = 'passed'
    return HomeVerification(**results)


@dataclass
class HomeChange:
    before: BridgeState
    target: BridgeState
    services: tuple[str, ...]
    master: str
    auto_mode: str | None
    attempted: list[str] = field(default_factory=list)
    complete: bool = False
    install_generation: str | None = None

    def encode(self):
        return asdict(self)

    @classmethod
    def parse(cls, value):
        fields = {'before', 'target', 'services', 'master', 'auto_mode', 'attempted', 'complete'}
        if not isinstance(value, dict) or not fields.issubset(value) or set(value) - fields - {'install_generation'}:
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
        generation = value.get('install_generation')
        if generation is not None and (not isinstance(generation, str) or not GENERATION.fullmatch(generation)):
            raise ValueError('Некорректное поколение установки Home.')
        return cls(before, target, parsed_services, value['master'], value['auto_mode'], steps, value['complete'], generation)


class CompatibilityRestoreError(RuntimeError):
    def __init__(self, errors, *, preserve_native=False):
        super().__init__('\n'.join(errors))
        self.preserve_native = preserve_native


def restore_compat(adb, user, change, *, installed=True):
    """Restore owned changes, preserving later dependencies and independent steps."""
    errors, protect_service, preserve_native = [], False, False
    status = None
    if installed:
        try:
            status = bridge(adb, user)
            if change.install_generation is not None and status.install_generation != change.install_generation:
                errors.append('PeachOS переустановлен или его данные сброшены; настройки новой установки сохранены.')
                protect_service = preserve_native = True
            elif status.state not in (change.target, change.before):
                errors.append('Настройка Home изменена после установки; её новое значение сохранено.')
                protect_service = preserve_native = True
            else:
                # The service can now be required by Settings/remaps even if its
                # effective filter is temporarily off. Old receivers cannot prove
                # the absence of such dependencies, so retain that shared service.
                protect_service = status.remote_required is not False or not status.config_ready and change.complete
                if 'bridge' in change.attempted and status.state == change.target:
                    if bridge(adb, user, change.before).state != change.before:
                        errors.append('Лаунчер не подтвердил восстановление исходной настройки Home.')
                        protect_service = preserve_native = True
        except (RuntimeError, ValueError, TimeoutError, OSError) as failure:
            errors.append('Настройка совместимого Home: ' + str(failure))
            protect_service = preserve_native = True
    # If the APK is absent, its private state and AppOps no longer belong to a
    # live installation. Clear only our stale membership; package/Home recovery
    # must never depend on a receiver in an uninstalled app.
    try:
        live = service_list(adb.shell('settings', '--user', user, 'get', 'secure', 'enabled_accessibility_services'))
        remove_service = 'services' in change.attempted and SERVICE not in change.services and SERVICE in live
        if remove_service and not protect_service:
            updated = tuple(s for s in live if s != SERVICE)
            if updated:
                adb.shell('settings', '--user', user, 'put', 'secure', 'enabled_accessibility_services', ':'.join(updated))
            else:
                adb.shell('settings', '--user', user, 'delete', 'secure', 'enabled_accessibility_services')
            live = service_list(adb.shell('settings', '--user', user, 'get', 'secure', 'enabled_accessibility_services'))
            if SERVICE in live:
                errors.append('Прошивка не подтвердила отключение добавленной службы Home.')
        elif remove_service:
            errors.append('Служба пульта сохранена: она нужна другим функциям или их состояние не подтверждено.')
        if ('master' in change.attempted and change.master != '1' and not (protect_service and SERVICE in live)
                and not (set(live) - set(change.services))):
            if change.master == 'null':
                adb.shell('settings', '--user', user, 'delete', 'secure', 'accessibility_enabled')
            else:
                adb.shell('settings', '--user', user, 'put', 'secure', 'accessibility_enabled', change.master)
    except (RuntimeError, ValueError, TimeoutError, OSError) as failure:
        errors.append('Служба доступности: ' + str(failure))
    if 'autostart' in change.attempted and installed and not protect_service:
        try:
            mode = autostart(adb, user)
            if mode is None:
                errors.append('Операция автозапуска больше не поддерживается; проверьте разрешение PeachOS вручную.')
            elif mode.effective_mode == 'allow':
                adb.shell('cmd', 'appops', 'set', '--user', user, PACKAGE, 'AUTO_START', change.auto_mode)
                adb.shell('cmd', 'appops', 'write-settings')
                restored = autostart(adb, user)
                if restored is None or restored.restore_mode != change.auto_mode:
                    errors.append('Прошивка не подтвердила восстановление исходного автозапуска.')
            elif mode.restore_mode != change.auto_mode:
                errors.append('Разрешение автозапуска изменено после установки; новое значение сохранено.')
        except (RuntimeError, ValueError, TimeoutError, OSError) as failure:
            errors.append('Автозапуск: ' + str(failure))
    if errors:
        raise CompatibilityRestoreError(errors, preserve_native=preserve_native)


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
    change = HomeChange(status.state, target, services, master, None if mode is None else mode.restore_mode,
                        install_generation=status.install_generation)
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
        verification = verify_home(adb, user)
        journal['home_verification'] = verification.encode()
        save()
        if not verification:
            raise RuntimeError('Служба подключена, но автоматическая проверка перехода Home не пройдена. Очистка остановлена.')
        change.complete = True
        journal['home_compat'] = change.encode(); save()
    except Exception as failure:
        try:
            restore_compat(adb, user, change)
        except Exception as rollback_failure:
            raise RuntimeError(str(failure) + '\nВосстановление не завершено: ' + str(rollback_failure)
                               + '\nИспользуйте сохранённую копию после восстановления ADB.') from failure
        raise
