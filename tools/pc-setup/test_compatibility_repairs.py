"""Recovery must remain useful after uninstall, later user choices and partial failures."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import core
from home_setup import BridgeState, HomeChange, PACKAGE, SERVICE, restore_compat, verify_home, home_roles
from test_home_setup import HomeAdb, STOCK, ACTIVITY, GENERATION, virtual_time
from test_setup import FakeAdb, device


class CompatibilityRepairsTests(unittest.TestCase):
    def journal(self, path, *, compat=True, native=False):
        record = {'version': 1, 'identity': device().identity, 'user': 0,
                  'original_home': STOCK, 'home_target': core.PEACH,
                  'home_attempted': native, 'changes': [{'package': 'com.miui.tv.analytics', 'original': 0}]}
        if compat:
            record['home_compat'] = HomeChange(BridgeState('', False), BridgeState(STOCK.split('/')[0], False),
                    (), '0', None, ['bridge', 'services', 'master'], True, GENERATION).encode()
        core.save_json(path, record)

    def test_uninstalled_launcher_does_not_block_return_of_disabled_packages(self):
        adb = FakeAdb(); adb.disabled.add('com.miui.tv.analytics'); current = device(); current.home = STOCK
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            path = Path(folder) / 'restore.json'; self.journal(path)
            core.restore(adb, current, path)
            report = json.loads(path.with_name('restore-result.json').read_text(encoding='utf-8'))
            self.assertEqual([], report['errors'])
        self.assertNotIn('com.miui.tv.analytics', adb.disabled)
        self.assertFalse(any(call[:2] == ('am', 'broadcast') for call in adb.calls))

    def test_unavailable_receiver_reports_failure_after_restoring_independent_packages(self):
        adb = FakeAdb(); adb.disabled.add('com.miui.tv.analytics'); current = device(); current.installed.add(PACKAGE)
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            path = Path(folder) / 'restore.json'; self.journal(path)
            with self.assertRaisesRegex(RuntimeError, 'независимые шаги'): core.restore(adb, current, path)
            report = json.loads(path.with_name('restore-result.json').read_text(encoding='utf-8'))
            self.assertIn('com.miui.tv.analytics', report['restored']); self.assertTrue(report['errors'])
        self.assertFalse(adb.disabled)

    def test_later_home_selection_is_preserved_while_package_recovery_continues(self):
        adb = FakeAdb(); current = device(); current.home = 'org.other.launcher/.Home'
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            path = Path(folder) / 'restore.json'; self.journal(path, compat=False, native=True)
            with self.assertRaisesRegex(RuntimeError, 'новое назначение сохранено'): core.restore(adb, current, path)
        self.assertTrue(any(call[:2] == ('pm', 'default-state') for call in adb.calls))
        self.assertFalse(any(call[:3] == ('cmd', 'package', 'set-home-activity') for call in adb.calls))

    def test_one_package_failure_does_not_skip_the_remaining_packages(self):
        class FailOne(FakeAdb):
            def shell(self, *args):
                if args[:2] == ('pm', 'default-state') and args[-1] == 'org.example.fail': raise RuntimeError('denied')
                return super().shell(*args)
        adb = FailOne(); current = device()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            path = Path(folder) / 'restore.json'; self.journal(path, compat=False)
            record = json.loads(path.read_text(encoding='utf-8'))
            record['changes'].append({'package': 'org.example.fail', 'original': 0}); core.save_json(path, record)
            with self.assertRaisesRegex(RuntimeError, 'denied'): core.restore(adb, current, path)
        self.assertIn(('pm', 'default-state', '--user', 0, 'com.miui.tv.analytics'), adb.calls)

    def test_late_settings_dependency_survives_home_rollback_even_with_filter_off(self):
        adb = HomeAdb(state=BridgeState(STOCK.split('/')[0], False), services=(SERVICE,), master='1', actual_filter=False)
        adb.remote_required = True; adb.reasons = ['settings']
        change = HomeChange(BridgeState('', False), adb.state, (), '0', None, ['bridge', 'services', 'master'], True, GENERATION)
        with self.assertRaisesRegex(RuntimeError, 'Служба пульта сохранена'): restore_compat(adb, 0, change)
        self.assertIn(SERVICE, adb.services); self.assertEqual('1', adb.master)
        self.assertEqual(BridgeState('', False), adb.state)

    def test_new_install_generation_is_never_overwritten_by_old_copy(self):
        adb = HomeAdb(state=BridgeState(STOCK.split('/')[0], False), services=(SERVICE,), master='1')
        adb.generation = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
        original = adb.state
        change = HomeChange(BridgeState('', False), original, (), '0', None, ['bridge', 'services'], True, GENERATION)
        with self.assertRaisesRegex(RuntimeError, 'переустановлен'): restore_compat(adb, 0, change)
        self.assertEqual(original, adb.state); self.assertIn(SERVICE, adb.services)

    def test_transient_peach_cannot_pass_before_late_stock_home(self):
        adb = HomeAdb(); adb.home_foregrounds = [ACTIVITY, ACTIVITY, ACTIVITY, ACTIVITY, STOCK]
        with virtual_time(): result = verify_home(adb, 0, timeout=1)
        self.assertFalse(result); self.assertEqual('failed', result.intent)

    def test_both_causal_software_checks_do_not_claim_a_physical_remote_test(self):
        with virtual_time(): result = verify_home(HomeAdb(), 0, timeout=1)
        self.assertTrue(result); self.assertEqual('passed', result.intent); self.assertEqual('passed', result.keyevent)
        self.assertEqual('unverified', result.physical)

    def test_role_boundary_distinguishes_unsupported_and_empty_role(self):
        class Reply:
            def __init__(self, value): self.value = value
            def run(self, *args, **kwargs): return self.value
        self.assertIsNone(home_roles(Reply('Unknown command: role'), 0, '34'))
        self.assertEqual((), home_roles(Reply(''), 0, '34'))
        self.assertEqual((PACKAGE,), home_roles(Reply(PACKAGE), 0, '34'))
        with self.assertRaises(ValueError): home_roles(Reply('org.bad;reboot'), 0, '34')

    def test_oem_stock_resolver_does_not_hide_a_later_user_home_role(self):
        class RoleAdb(FakeAdb):
            def run(self, *args, **kwargs):
                if args[:3] == ('shell', 'cmd', 'role'): return 'org.new.launcher'
                return super().run(*args, **kwargs)
        current = device(); current.props = dict(current.props, **{'ro.build.version.sdk': '34'}); current.home = STOCK
        for modern in (False, True):
            adb = RoleAdb()
            with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
                path = Path(folder) / 'restore.json'; self.journal(path, compat=False, native=True)
                if modern:
                    record = json.loads(path.read_text(encoding='utf-8'))
                    record.update(home_roles_before=[STOCK.split('/')[0]], home_roles_target=[PACKAGE]); core.save_json(path, record)
                with self.assertRaisesRegex(RuntimeError, 'новое назначение сохранено'): core.restore(adb, current, path)
            self.assertFalse(any(call[:3] == ('cmd', 'package', 'set-home-activity') for call in adb.calls))


if __name__ == '__main__': unittest.main()
