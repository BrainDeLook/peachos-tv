"""Home compatibility boundary checks; every ADB reply is an isolated fixture."""
import base64
import json
from contextlib import contextmanager
from pathlib import Path
import tempfile
import tkinter as tk
from tkinter import ttk
import unittest
import zipfile
from unittest.mock import patch

import core
from home_setup import (ACTIVITY, PACKAGE, RECEIVER, SERVICE, BridgeState, HomeChange,
                        bridge, configure_compat, restore_compat, service_list, verify_home)


STOCK = 'org.example.factoryhome/org.example.factoryhome.HomeActivity'
OTHER_SERVICE = 'org.example.reader/org.example.reader.ReaderService'
LATER_SERVICE = 'org.example.later/org.example.later.LaterService'


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, delay):
        self.now += delay


@contextmanager
def virtual_time():
    clock = Clock()
    with patch('home_setup.time.monotonic', clock.monotonic), patch('home_setup.time.sleep', clock.sleep):
        yield clock


class HomeAdb:
    """Model the receiver, secure settings and a single recognized optional app-op."""
    def __init__(self, *, state=None, services=(), master='0', auto='unknown', bind_allowed=True,
                 actual_filter=None):
        self.state = state or BridgeState('', False)
        self.services = tuple(services)
        self.master = master
        self.auto = auto
        self.bind_allowed = bind_allowed
        self.actual_filter = actual_filter
        self.calls = []
        self.run_options = []
        self.foregrounds = [ACTIVITY]
        self.launch_output = 'Status: ok'
        self.activity_style = 'topResumedActivity'
        self.disabled = set()
        self.disconnected = False
        self.timeout_after = None
        self.before_mutation = lambda step: None
        self.read_payload = None
        self.ignored_mutations = set()

    def mutate(self, step, action):
        self.before_mutation(step)
        if step not in self.ignored_mutations:
            action()
        if self.timeout_after == step:
            self.disconnected = True
            raise TimeoutError('ADB disconnected after ' + step)

    def run(self, *args, **kwargs):
        self.run_options.append((args, kwargs))
        if args[0] == 'shell':
            return self.shell(*args[1:])
        raise AssertionError('Unexpected ADB command: ' + str(args))

    def shell(self, *args):
        args = tuple(map(str, args))
        self.calls.append(args)
        if self.disconnected:
            raise TimeoutError('ADB unavailable')
        if args[:2] == ('am', 'broadcast'):
            self.assert_receiver(args)
            write = args[args.index('operation') + 1] == 'write'
            if write:
                package = args[args.index('stock_package') + 1]
                filtering = args[args.index('filter_keys') + 1] == 'true'
                self.mutate('bridge', lambda: setattr(self, 'state', BridgeState(package, filtering)))
            bound = self.bind_allowed and SERVICE in self.services and self.master == '1'
            filtering = (self.actual_filter if self.actual_filter is not None else
                         self.state.filter_keys if self.state.stock_package else True)
            payload = {'version': 1, 'stock_package': self.state.stock_package,
                       'filter_keys': self.state.filter_keys, 'bound': bound,
                       'applied_stock_package': self.state.stock_package if bound else '',
                       'filter_keys_active': filtering if bound else False}
            if self.read_payload is not None:
                payload = self.read_payload
            encoded = base64.b64encode(json.dumps(payload).encode()).decode()
            return 'Broadcast completed: result=0, data="peach-home-v1:' + encoded + '"'
        if args[:3] == ('cmd', 'appops', 'get'):
            self.assert_appop(args)
            if self.auto == 'unknown':
                return 'Error: Unknown operation string: AUTO_START'
            if self.auto.startswith('default:'):
                return 'No operations.\nDefault mode: ' + self.auto.split(':', 1)[1]
            return 'AUTO_START: ' + self.auto
        if args[:3] == ('cmd', 'appops', 'set'):
            self.assert_appop(args[:-1])
            self.mutate('autostart', lambda: setattr(self, 'auto', args[-1]))
            return ''
        if args == ('cmd', 'appops', 'write-settings'):
            return ''
        if args[:1] == ('settings',):
            if args[:3] != ('settings', '--user', '0') or args[4] != 'secure':
                raise AssertionError('Unexpected settings boundary: ' + str(args))
            action, key = args[3], args[5]
            if action == 'get':
                if key == 'enabled_accessibility_services':
                    return ':'.join(self.services) or 'null'
                if key == 'accessibility_enabled':
                    return self.master
            if key == 'enabled_accessibility_services':
                updated = () if action == 'delete' else tuple(args[6].split(':'))
                self.mutate('services', lambda: setattr(self, 'services', updated))
                return ''
            if key == 'accessibility_enabled':
                updated = 'null' if action == 'delete' else args[6]
                self.mutate('master', lambda: setattr(self, 'master', updated))
                return ''
        if args[:2] == ('am', 'start'):
            return self.launch_output
        if args == ('input', 'keyevent', 'KEYCODE_WAKEUP'):
            return ''
        if args == ('dumpsys', 'activity', 'activities'):
            name = self.foregrounds.pop(0) if len(self.foregrounds) > 1 else self.foregrounds[0]
            return self.activity_style + '=ActivityRecord{abc u0 ' + name + ' t1}'
        if args[:3] == ('cmd', 'package', 'resolve-activity'):
            return STOCK
        if args[:3] == ('cmd', 'package', 'set-home-activity'):
            return 'Success'
        if args[:2] == ('dumpsys', 'package'):
            return 'User 0: installed=true enabled=0'
        if args[:4] == ('pm', 'list', 'packages', '-d'):
            return '\n'.join('package:' + p for p in self.disabled)
        if args[:3] == ('pm', 'list', 'packages'):
            return 'package:' + PACKAGE
        if args[:2] == ('pm', 'disable-user'):
            self.disabled.add(args[-1])
            return ''
        raise AssertionError('Unexpected shell command: ' + str(args))

    @staticmethod
    def assert_receiver(args):
        if args[args.index('-n') + 1] != RECEIVER:
            raise AssertionError('A different receiver was targeted')

    @staticmethod
    def assert_appop(args):
        if args[-2:] != (PACKAGE, 'AUTO_START'):
            raise AssertionError('An unrelated app-op was accessed')


class HomeSetupTests(unittest.TestCase):
    def configure(self, adb):
        journal, saved = {}, []
        with virtual_time():
            configure_compat(adb, 0, STOCK, journal, lambda: saved.append(json.loads(json.dumps(journal))))
        return HomeChange.parse(saved[-1]['home_compat']), saved

    def test_generic_unknown_vendor_configures_without_disabling_stock_or_changing_appops(self):
        adb = HomeAdb(services=(OTHER_SERVICE,), master='1')
        change, saved = self.configure(adb)
        self.assertTrue(change.complete)
        self.assertEqual(BridgeState(STOCK.split('/')[0], False), adb.state)
        self.assertEqual((OTHER_SERVICE, SERVICE), adb.services)
        self.assertTrue(saved)
        self.assertFalse(any(call[:2] == ('pm', 'disable-user') for call in adb.calls))
        self.assertFalse(any(call[:3] == ('cmd', 'appops', 'set') for call in adb.calls))
        self.assertFalse(any('config.json' in str(call) for call in adb.calls))

    def test_recognized_autostart_default_is_allowed_then_restored_to_default(self):
        adb = HomeAdb(auto='default:ignore')
        change, _ = self.configure(adb)
        self.assertEqual('default', change.auto_mode)
        self.assertEqual('allow', adb.auto)
        restore_compat(adb, 0, change)
        self.assertEqual('default', adb.auto)
        self.assertEqual(2, adb.calls.count(('cmd', 'appops', 'write-settings')))

    def test_already_allowed_autostart_never_sets_or_resets_other_appops(self):
        adb = HomeAdb(auto='allow')
        change, _ = self.configure(adb)
        self.assertNotIn('autostart', change.attempted)
        restore_compat(adb, 0, change)
        self.assertFalse(any(call[:3] == ('cmd', 'appops', 'set') for call in adb.calls))

    def test_existing_remote_helper_keeps_filtering_and_is_not_removed_on_restore(self):
        adb = HomeAdb(services=(OTHER_SERVICE, SERVICE), master='1')
        change, _ = self.configure(adb)
        self.assertTrue(adb.state.filter_keys)
        self.assertNotIn('services', change.attempted)
        restore_compat(adb, 0, change)
        self.assertEqual((OTHER_SERVICE, SERVICE), adb.services)
        self.assertEqual(BridgeState('', False), adb.state)

    def test_existing_redirect_with_filtering_off_does_not_enable_key_filter(self):
        adb = HomeAdb(state=BridgeState(STOCK.split('/')[0], False), services=(SERVICE,), master='1')
        change, _ = self.configure(adb)
        self.assertEqual([], change.attempted)
        self.assertFalse(adb.state.filter_keys)

    def test_existing_helper_with_theme_filtering_off_keeps_it_off(self):
        adb = HomeAdb(services=(SERVICE,), master='1', actual_filter=False)
        change, _ = self.configure(adb)
        self.assertFalse(adb.state.filter_keys)
        self.assertNotIn('services', change.attempted)
        restore_compat(adb, 0, change)
        self.assertEqual((SERVICE,), adb.services)

    def test_unbound_existing_helper_does_not_infer_filtering_from_membership(self):
        adb = HomeAdb(services=(SERVICE,), master='0')
        change, _ = self.configure(adb)
        self.assertFalse(adb.state.filter_keys)
        self.assertNotIn('services', change.attempted)

    def test_enabled_but_unbound_service_fails_before_any_cleanup_and_rolls_back(self):
        adb = HomeAdb(bind_allowed=False)
        current = core.Device('fixture', 'a' * 64, 0, {'ro.product.manufacturer': 'Unknown vendor'},
                              {PACKAGE, STOCK.split('/')[0], 'com.google.android.feedback'}, set(), set(), STOCK)
        journal = None
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current), virtual_time():
            # Native HOME stays stock; this also checks cleanup is gated on the fallback verification.
            adb.foregrounds = [STOCK]
            with self.assertRaisesRegex(RuntimeError, 'службу пульта'):
                core.apply_plan(adb, current, ['com.google.android.feedback'], set(), None, True,
                                Path(folder), allow_home_redirect=True)
            journal = json.loads(next(Path(folder).glob('*/restore.json')).read_text(encoding='utf-8'))
        self.assertFalse(journal['complete'])
        self.assertFalse(journal['home_compat']['complete'])
        self.assertEqual([], journal['changes'])
        self.assertEqual(set(), adb.disabled)
        self.assertEqual(BridgeState('', False), adb.state)
        self.assertEqual((), adb.services)
        self.assertEqual('0', adb.master)

    def test_native_verification_exception_restores_original_home_before_stopping(self):
        adb = HomeAdb()
        current = core.Device('fixture', 'a' * 64, 0, {},
                              {PACKAGE, 'com.google.android.feedback'}, set(), set(), STOCK)
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current), \
                patch('core.verify_home', side_effect=TimeoutError('foreground query timed out')):
            with self.assertRaisesRegex((TimeoutError, RuntimeError), 'timed out'):
                core.apply_plan(adb, current, ['com.google.android.feedback'], set(), None, True,
                                Path(folder), allow_home_redirect=True)
            journal = json.loads(next(Path(folder).glob('*/restore.json')).read_text(encoding='utf-8'))
        self.assertIn(('cmd', 'package', 'set-home-activity', '--user', '0', STOCK), adb.calls)
        self.assertEqual(set(), adb.disabled)
        self.assertNotIn('home_compat', journal)
        self.assertFalse(journal['complete'])

    def test_native_home_success_never_enables_compatibility_even_when_allowed(self):
        adb = HomeAdb()
        current = core.Device('fixture', 'a' * 64, 0, {}, {PACKAGE}, set(), set(), STOCK)
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current), \
                patch('core.verify_home', return_value=True), patch('core.configure_compat') as configure:
            path = core.apply_plan(adb, current, [], set(), None, True, Path(folder), allow_home_redirect=True)
            journal = json.loads(path.read_text(encoding='utf-8'))
        configure.assert_not_called()
        self.assertEqual('existing_redirect', journal['home_method'])
        self.assertTrue(journal['complete'])
        self.assertFalse(any(call[:2] == ('am', 'broadcast') for call in adb.calls))
        self.assertFalse(any(call[:1] == ('settings',) for call in adb.calls))

    def test_failed_native_home_without_consent_never_configures_global_helper(self):
        adb = HomeAdb()
        current = core.Device('fixture', 'a' * 64, 0, {}, {PACKAGE}, set(), set(), STOCK)
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current), \
                patch('core.verify_home', return_value=False), patch('core.configure_compat') as configure:
            with self.assertRaisesRegex(RuntimeError, 'Совместимый Home'):
                core.apply_plan(adb, current, [], set(), None, True, Path(folder))
        configure.assert_not_called()
        self.assertEqual([], [call for call in adb.calls if call[:2] == ('am', 'broadcast')])
        self.assertIn(('cmd', 'package', 'set-home-activity', '--user', '0', STOCK), adb.calls)

    def test_update_only_rejects_compatibility_consent_before_mutation(self):
        adb = HomeAdb()
        current = core.Device('fixture', 'a' * 64, 0, {}, {PACKAGE}, set(), set(), STOCK)
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaisesRegex(ValueError, 'только APK лаунчера'):
                core.apply_plan(adb, current, [], set(), Path(folder) / 'not-read.apk', False,
                                Path(folder), update_only=True, allow_home_redirect=True)
        self.assertEqual([], adb.calls)

    def test_update_only_install_never_triggers_home_or_reads_helper_state(self):
        class InstallingAdb(HomeAdb):
            def run(self, *args, **kwargs):
                if args[0] == 'install':
                    self.calls.append(args)
                    return 'Success'
                return super().run(*args, **kwargs)
        adb = InstallingAdb(services=(SERVICE,), master='1')
        current = core.Device('fixture', 'a' * 64, 0, {}, {PACKAGE}, set(), set(), STOCK)
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            apk = Path(folder) / 'launcher.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'fixture')
            path = core.apply_plan(adb, current, [], set(), apk, False,
                                   Path(folder) / 'backups', update_only=True)
            journal = json.loads(path.read_text(encoding='utf-8'))
        self.assertEqual([('install', '-r', str(apk))], adb.calls)
        self.assertNotIn('home_compat', journal)
        self.assertFalse(journal['home_attempted'])

    def test_every_attempt_is_saved_before_action_and_timeout_retains_pending_recovery(self):
        for failing_step in ('bridge', 'autostart', 'services', 'master'):
            with self.subTest(step=failing_step):
                adb = HomeAdb(auto='ignore')
                journal, saved = {}, []
                def assert_durable(step):
                    self.assertTrue(saved, 'journal must exist before first mutation')
                    self.assertIn(step, saved[-1]['home_compat']['attempted'])
                adb.before_mutation = assert_durable
                adb.timeout_after = failing_step
                with virtual_time(), self.assertRaisesRegex(RuntimeError, 'Восстановление не завершено'):
                    configure_compat(adb, 0, STOCK, journal, lambda: saved.append(json.loads(json.dumps(journal))))
                pending = HomeChange.parse(saved[-1]['home_compat'])
                self.assertIn(failing_step, pending.attempted)
                self.assertFalse(pending.complete)
                self.assertTrue(adb.disconnected)
                # On reconnection the same durable snapshot can recover an action whose reply was lost.
                adb.disconnected, adb.timeout_after = False, None
                adb.before_mutation = lambda step: None
                restore_compat(adb, 0, pending)
                self.assertEqual(BridgeState('', False), adb.state)
                self.assertEqual((), adb.services)
                self.assertEqual('0', adb.master)
                self.assertEqual('ignore', adb.auto)

    def test_restore_preserves_services_added_later_and_keeps_accessibility_enabled(self):
        adb = HomeAdb(services=(OTHER_SERVICE,), master='0')
        change, _ = self.configure(adb)
        adb.services += (LATER_SERVICE,)
        restore_compat(adb, 0, change)
        self.assertEqual((OTHER_SERVICE, LATER_SERVICE), adb.services)
        self.assertEqual('1', adb.master)

    def test_apply_preserves_other_service_enabled_after_initial_snapshot(self):
        adb = HomeAdb(services=(OTHER_SERVICE,), master='1')
        def another_service_was_enabled(step):
            if step == 'bridge':
                adb.services += (LATER_SERVICE,)
        adb.before_mutation = another_service_was_enabled
        change, _ = self.configure(adb)
        self.assertEqual((OTHER_SERVICE, LATER_SERVICE, SERVICE), adb.services)
        restore_compat(adb, 0, change)
        self.assertEqual((OTHER_SERVICE, LATER_SERVICE), adb.services)

    def test_restore_retains_an_already_removed_helper_and_restores_absent_master(self):
        adb = HomeAdb(master='null')
        change, _ = self.configure(adb)
        adb.services = ()
        restore_compat(adb, 0, change)
        self.assertEqual((), adb.services)
        self.assertEqual('null', adb.master)

    def test_bridge_compare_and_restore_rejects_later_user_change(self):
        adb = HomeAdb()
        change, _ = self.configure(adb)
        adb.state = BridgeState('org.example.anotherhome', False)
        adb.calls.clear()
        with self.assertRaisesRegex(RuntimeError, 'изменена после установки'):
            restore_compat(adb, 0, change)
        self.assertEqual(BridgeState('org.example.anotherhome', False), adb.state)
        self.assertEqual(1, len(adb.calls))

    def test_changed_autostart_mode_is_preserved_on_restore(self):
        adb = HomeAdb(auto='ignore')
        change, _ = self.configure(adb)
        adb.auto = 'deny'
        with self.assertRaisesRegex(RuntimeError, 'изменено после установки'):
            restore_compat(adb, 0, change)
        self.assertEqual('deny', adb.auto)

    def test_restore_rejects_receiver_success_without_applying_original_state(self):
        adb = HomeAdb()
        change, _ = self.configure(adb)
        adb.ignored_mutations.add('bridge')
        with self.assertRaisesRegex(RuntimeError, 'не подтвердил восстановление'):
            restore_compat(adb, 0, change)
        self.assertEqual(change.target, adb.state)
        self.assertIn(SERVICE, adb.services)

    def test_restore_rejects_appop_success_without_restoring_default(self):
        adb = HomeAdb(auto='default:ignore')
        change, _ = self.configure(adb)
        adb.ignored_mutations.add('autostart')
        with self.assertRaisesRegex(RuntimeError, 'не подтвердила восстановление'):
            restore_compat(adb, 0, change)
        self.assertEqual('allow', adb.auto)

    def test_foreground_verification_requires_stable_peach_not_transient_frame(self):
        adb = HomeAdb()
        adb.foregrounds = [ACTIVITY, STOCK]
        with virtual_time() as clock:
            self.assertFalse(verify_home(adb, 0, timeout=1))
        self.assertGreaterEqual(clock.now, 1)

    def test_foreground_verification_waits_for_delayed_home_transition(self):
        adb = HomeAdb()
        adb.foregrounds = [STOCK, STOCK, ACTIVITY]
        with virtual_time() as clock:
            self.assertTrue(verify_home(adb, 0, timeout=2))
        self.assertGreaterEqual(clock.now, .7)
        self.assertLess(clock.now, 2)

    def test_foreground_verification_supports_older_resumed_activity_label(self):
        adb = HomeAdb()
        adb.activity_style = 'mResumedActivity'
        with virtual_time():
            self.assertTrue(verify_home(adb, 0))

    def test_verification_wakes_screen_and_bounds_launch_and_foreground_queries(self):
        adb = HomeAdb()
        with virtual_time():
            self.assertTrue(verify_home(adb, 0))
        wake, preflight, launch, *queries = adb.run_options
        self.assertEqual(('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'), wake[0])
        self.assertEqual(8, wake[1]['timeout'])
        self.assertEqual(('shell', 'am', 'start', '--user', '0', '-n', ACTIVITY), preflight[0])
        self.assertEqual(8, preflight[1]['timeout'])
        self.assertNotIn('-W', preflight[0])
        self.assertIn('android.intent.category.HOME', launch[0])
        self.assertNotIn('-W', launch[0])
        self.assertEqual(8, launch[1]['timeout'])
        self.assertTrue(queries)
        for args, options in queries:
            self.assertEqual(('shell', 'dumpsys', 'activity', 'activities'), args)
            self.assertEqual(5, options['timeout'])

    def test_foreground_verification_rejects_other_users_and_launch_failure(self):
        adb = HomeAdb()
        with virtual_time():
            self.assertFalse(verify_home(adb, 10, timeout=.5))
        adb.launch_output = 'Error: Activity not started, unable to resolve Intent'
        adb.calls.clear()
        with virtual_time():
            self.assertFalse(verify_home(adb, 0))
        self.assertFalse(any(call[:2] == ('dumpsys', 'activity') for call in adb.calls))

    def test_bridge_requires_protocol_marker_and_valid_typed_payload(self):
        class RawReply:
            def __init__(self, output): self.output = output
            def shell(self, *args): return self.output
        for output in ('Broadcast completed: result=0', 'peach-home-v1:////',
                       'peach-home-v1:' + base64.b64encode(b'not json').decode()):
            with self.subTest(output=output), self.assertRaises((ValueError, RuntimeError)):
                bridge(RawReply(output), 0)
        payloads = [[], {'version': True}, {'version': 2},
                    {'version': 1, 'error': 'HOME target not found'},
                    {'version': 1, 'stock_package': '', 'filter_keys': False, 'bound': 1,
                     'applied_stock_package': '', 'filter_keys_active': False},
                    {'version': 1, 'stock_package': '', 'filter_keys': False, 'bound': False,
                     'applied_stock_package': '', 'filter_keys_active': 1},
                    {'version': 1, 'stock_package': '', 'filter_keys': False, 'bound': False,
                     'applied_stock_package': ''}]
        for value in payloads:
            adb = HomeAdb(); adb.read_payload = value
            with self.subTest(value=value), self.assertRaises((ValueError, RuntimeError)):
                bridge(adb, 0)

    def test_bridge_state_rejects_self_android_invalid_types_and_shell_like_packages(self):
        self.assertEqual('org._vendor.TV_Home', BridgeState.parse({
            'stock_package': 'org._vendor.TV_Home', 'filter_keys': False}).stock_package)
        for package in (PACKAGE, 'android', 'bad;reboot', 'org.example/Activity', 'org.2vendor.home', [], 1, 'a' * 256):
            with self.subTest(package=package), self.assertRaises(ValueError):
                BridgeState.parse({'stock_package': package, 'filter_keys': False})
        for flag in (1, 'false', None, []):
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                BridgeState.parse({'stock_package': 'org.example.home', 'filter_keys': flag})
        with self.assertRaises(ValueError):
            BridgeState.parse({'stock_package': '', 'filter_keys': False, 'arbitrary': True})

    def test_service_list_normalizes_short_names_and_rejects_malformed_entries(self):
        self.assertEqual((), service_list('null'))
        self.assertEqual((OTHER_SERVICE,), service_list(OTHER_SERVICE + ':' + OTHER_SERVICE))
        self.assertEqual(('org.example/org.example.Service',), service_list('org.example/.Service'))
        for value in ('org.example/.Service:', 'bad;reboot/.Service', 'org.example', 'null:' + SERVICE):
            with self.subTest(value=value), self.assertRaises(ValueError):
                service_list(value)

    def test_journal_schema_rejects_arbitrary_steps_targets_and_untyped_values(self):
        valid = HomeChange(BridgeState('', False), BridgeState('org.example.home', False),
                           (OTHER_SERVICE,), '0', 'default', ['bridge'], False).encode()
        valid = json.loads(json.dumps(valid))
        self.assertEqual('org.example.home', HomeChange.parse(valid).target.stock_package)
        invalids = [dict(valid, injected='shell'), dict(valid, attempted=['shell']),
                    dict(valid, attempted=['bridge', 'bridge']), dict(valid, services=[SERVICE, SERVICE]),
                    dict(valid, services=['org.example/.Short']), dict(valid, master=True),
                    dict(valid, auto_mode='reset'), dict(valid, auto_mode=[]), dict(valid, auto_mode={}),
                    dict(valid, complete=1), dict(valid, target={'stock_package': '', 'filter_keys': False})]
        for value in invalids:
            with self.subTest(value=value), self.assertRaises(ValueError):
                HomeChange.parse(value)


class WizardHomeConsentTests(unittest.TestCase):
    """The plan handed to the core must match the owner's explicit Home choices."""
    def setUp(self):
        from wizard import Wizard
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        for target, value in (('wizard.ASSETS', Path(self.folder.name)),
                              ('wizard.DATA', Path(self.folder.name) / 'data')):
            replacement = patch(target, value)
            replacement.start()
            self.addCleanup(replacement.stop)
        self.root = tk.Tk()
        self.root.withdraw()
        self.addCleanup(self.destroy_ui)
        self.app = Wizard(self.root)
        self.adb = HomeAdb(services=(SERVICE,), master='1')
        self.device = core.Device('fixture', 'a' * 64, 0, {}, {PACKAGE}, set(), set(), STOCK)
        self.app.connected((self.adb, self.device))

    def destroy_ui(self):
        self.root.update_idletasks()
        for callback in self.root.tk.call('after', 'info'):
            self.root.after_cancel(callback)
        self.root.destroy()

    def approve_plan(self, *, update_only=False):
        self.app.review(update_only=update_only)
        plan = next(widget for widget in self.root.winfo_children() if isinstance(widget, tk.Toplevel))
        pending = [plan]
        while pending:
            widget = pending.pop()
            if isinstance(widget, ttk.Button) and widget.cget('text') == 'Применить план':
                apply_button = widget
                break
            pending.extend(widget.winfo_children())
        else:
            self.fail('The review has no explicit apply button')
        self.app.job = lambda action, callback: action()
        with patch('wizard.apply_plan') as apply:
            apply_button.invoke()
        return apply.call_args

    def test_new_connection_resets_consent_and_existing_install_defaults_to_update(self):
        self.app.simple_home.set(True)
        self.app.compat_home.set(True)
        self.device = core.Device('other-fixture', 'b' * 64, 0, {}, {PACKAGE}, set(), set(), STOCK)
        self.app.connected((self.adb, self.device))
        self.assertFalse(self.app.simple_home.get())
        self.assertFalse(self.app.compat_home.get())
        self.assertTrue(self.app.simple_home_choice.instate(('!disabled',)))
        args, options = self.approve_plan()
        self.assertFalse(args[5])
        self.assertFalse(options['allow_home_redirect'])
        self.assertTrue(options['update_only'])

    def test_existing_install_can_explicitly_opt_in_to_home_compatibility(self):
        self.assertFalse(self.app.simple_home.get())
        self.app.compat_home.set(True)
        args, options = self.approve_plan()
        self.assertTrue(args[5])
        self.assertTrue(options['allow_home_redirect'])
        self.assertFalse(options['update_only'])

    def test_advanced_compatibility_alone_requests_home_in_the_reviewed_plan(self):
        self.app.switch_mode()
        self.assertTrue(self.app.advanced)
        self.assertFalse(self.app.home.get())
        self.app.compat_home.set(True)
        args, options = self.approve_plan()
        self.assertTrue(args[5])
        self.assertTrue(options['allow_home_redirect'])
        self.assertFalse(options['update_only'])

    def test_clicking_compatibility_marks_home_and_clearing_home_cancels_it(self):
        pending = [self.root]
        while pending:
            widget = pending.pop()
            if isinstance(widget, ttk.Checkbutton) and widget.cget('text') == 'Совместимый Home через службу пульта':
                widget.invoke()
                break
            pending.extend(widget.winfo_children())
        else:
            self.fail('Compatibility checkbox missing')
        self.assertTrue(self.app.simple_home.get())
        self.assertTrue(self.app.home.get())
        self.app.simple_home_choice.invoke()
        self.assertFalse(self.app.simple_home.get())
        self.assertFalse(self.app.compat_home.get())

    def test_explicit_update_only_ignores_checked_home_and_compatibility_options(self):
        self.app.simple_home.set(True)
        self.app.home.set(True)
        self.app.compat_home.set(True)
        args, options = self.approve_plan(update_only=True)
        self.assertFalse(args[5])
        self.assertFalse(options['allow_home_redirect'])
        self.assertTrue(options['update_only'])


if __name__ == '__main__':
    unittest.main()
