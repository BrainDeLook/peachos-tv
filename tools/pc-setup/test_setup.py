import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from catalog import candidates, protected, matching_profiles
from core import Device, apply_plan, restore, read_restore, read_preset, packages

XIAOMI = {'ro.product.manufacturer': 'Xiaomi', 'ro.product.model': 'MiTV-AESP0', 'ro.product.device': 'aquaman', 'ro.build.version.sdk': '28'}


class FakeAdb:
    def __init__(self, fail=False):
        self.calls, self.disabled, self.fail = [], set(), fail

    def shell(self, *args):
        self.calls.append(args)
        if args[:2] == ('dumpsys', 'package'): return 'User 0: installed=true enabled=0'
        if args[:2] == ('pm', 'disable-user'):
            self.disabled.add(args[-1])
            if self.fail: raise TimeoutError('Connection lost after command')
        if args[:2] in (('pm', 'enable'), ('pm', 'default-state')): self.disabled.discard(args[-1])
        if args[:4] == ('pm', 'list', 'packages', '-d'): return '\n'.join('package:' + p for p in self.disabled)
        return ''


def device(identity='a' * 64):
    return Device('test', identity, 0, XIAOMI,
                  {'com.miui.tv.analytics', 'com.android.systemui'}, set(), set(), 'com.google.android.tvlauncher/.Main')


class SetupTests(unittest.TestCase):
    def test_android_framework_is_a_valid_inventory_package_but_protected(self):
        self.assertEqual({'android', 'com.google.android.gms'}, packages('package:android\npackage:com.google.android.gms\n'))
        self.assertTrue(protected('android'))

    def test_profile_requires_model_and_android_version(self):
        self.assertEqual(('xiaomi-aesp0',), matching_profiles(XIAOMI))
        self.assertEqual((), matching_profiles(dict(XIAOMI, **{'ro.build.version.sdk': '31'})))
        self.assertEqual((), matching_profiles(dict(XIAOMI, **{'ro.product.model': 'Different stick'})))

    def test_unknown_system_packages_are_never_actions(self):
        _, rows = candidates({}, {'com.vendor.unknown', 'com.google.android.feedback'}, set(), '')
        self.assertNotIn('com.vendor.unknown', rows)
        self.assertIn('com.google.android.feedback', rows)

    def test_current_home_and_android_core_cannot_be_selected_even_as_user_apps(self):
        installed = {'android', 'com.android.systemui', 'com.android.providers.tv', 'com.vendor.home',
                     'com.google.android.tungsten.setupwraith', 'com.google.android.tv.remote.service'}
        _, rows = candidates(XIAOMI, installed, installed, 'com.vendor.home')
        self.assertEqual({}, rows)
        self.assertTrue(protected('com.android.permissioncontroller'))

    def test_xiaomi_optional_core_packages_do_not_leak_to_other_devices(self):
        installed = {'com.android.providers.contacts', 'com.android.se'}
        self.assertEqual({}, candidates({}, installed, set(), '')[1])
        self.assertEqual(installed, set(candidates(XIAOMI, installed, set(), '')[1]))

    def test_mixed_analytics_updater_has_updates_dependency(self):
        props = {'ro.product.manufacturer': 'TCL', 'ro.product.device': 'G10', 'ro.product.name': 'G10_4K_GB', 'ro.build.version.sdk': '31'}
        rule = candidates(props, {'com.tcl.ttvs'}, set(), '')[1]['com.tcl.ttvs']
        self.assertIn('updates', rule.features)
        self.assertFalse(rule.suggested)

    def test_changed_device_rejected_before_any_mutation(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device('b' * 64)):
            with self.assertRaises(ValueError): apply_plan(adb, device(), ['com.miui.tv.analytics'], set(), None, False, Path(folder))
        self.assertEqual([], adb.calls)

    def test_protected_package_rejected_before_backup_or_mutation(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            with self.assertRaises(ValueError): apply_plan(adb, device(), ['com.android.systemui'], set(), None, False, Path(folder))
        self.assertEqual([], adb.calls)

    def test_active_custom_keyboard_is_protected_at_apply_boundary(self):
        current = device()
        current.installed.add('com.user.keyboard')
        current.user_apps.add('com.user.keyboard')
        current.protected_packages.add('com.user.keyboard')
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaises(ValueError): apply_plan(adb, current, ['com.user.keyboard'], set(), None, False, Path(folder))
        self.assertEqual([], adb.calls)

    def test_kept_function_cannot_be_disabled_by_stale_selection(self):
        current = device()
        current.installed.add('com.mitv.milinkservice')
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaises(ValueError): apply_plan(adb, current, ['com.mitv.milinkservice'], {'cast'}, None, False, Path(folder))
        self.assertEqual([], adb.calls)

    def test_successful_disable_is_confirmed_and_original_state_recorded(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            path = apply_plan(adb, device(), ['com.miui.tv.analytics'], set(), None, False, Path(folder))
            journal = read_restore(path)
            self.assertTrue(journal['complete'])
            self.assertTrue(journal['changes'][0]['confirmed'])
            self.assertEqual(0, journal['changes'][0]['original'])

    def test_existing_disabled_package_is_not_written_to_restore_journal(self):
        current = device()
        current.disabled.add('com.miui.tv.analytics')
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            path = apply_plan(adb, current, ['com.miui.tv.analytics'], set(), None, False, Path(folder))
            self.assertEqual([], read_restore(path)['changes'])
        self.assertEqual([], adb.calls)

    def test_rejected_home_change_is_rolled_back_before_cleanup(self):
        current = device()
        current.installed.add('com.gathin.tvhome.backdrops')
        class RejectingHomeAdb(FakeAdb):
            def shell(self, *args):
                if args[:3] == ('cmd', 'package', 'resolve-activity'):
                    self.calls.append(args)
                    return current.home
                return super().shell(*args)
        adb = RejectingHomeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaises(RuntimeError):
                apply_plan(adb, current, ['com.miui.tv.analytics'], set(), None, True, Path(folder))
        self.assertFalse(any(call[:2] == ('pm', 'disable-user') for call in adb.calls))
        self.assertEqual(current.home, adb.calls[-1][-1])

    def test_explicit_enabled_state_restores_enable_not_default(self):
        class EnabledAdb(FakeAdb):
            def shell(self, *args):
                if args[:2] == ('dumpsys', 'package'):
                    self.calls.append(args)
                    return 'User 0: installed=true enabled=1'
                return super().shell(*args)
        adb = EnabledAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            path = apply_plan(adb, device(), ['com.miui.tv.analytics'], set(), None, False, Path(folder))
            restore(adb, device(), path)
        self.assertIn(('pm', 'enable', '--user', 0, 'com.miui.tv.analytics'), adb.calls)

    def test_journal_survives_disconnect_and_restores_pending_change(self):
        adb = FakeAdb(fail=True)
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            with self.assertRaises(TimeoutError):
                apply_plan(adb, device(), ['com.miui.tv.analytics'], set(), None, False, Path(folder))
            backup = next(Path(folder).glob('*/restore.json'))
            journal = read_restore(backup)
            self.assertEqual(0, journal['changes'][0]['original'])
            self.assertFalse(journal['changes'][0]['confirmed'])
            self.assertIn('com.miui.tv.analytics', adb.disabled)
            adb.fail = False
            restore(adb, device(), backup)
            self.assertNotIn('com.miui.tv.analytics', adb.disabled)
            self.assertIn(('pm', 'default-state', '--user', 0, 'com.miui.tv.analytics'), adb.calls)

    def test_foreign_backup_rejected_without_mutation(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            path = Path(folder) / 'restore.json'
            path.write_text(json.dumps({'version': 1, 'identity': 'b' * 64, 'user': 0,
                                        'changes': [], 'original_home': '', 'home_attempted': False}))
            with self.assertRaises(ValueError): restore(adb, device(), path)
        self.assertEqual([], adb.calls)

    def test_shell_injection_in_backup_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'restore.json'
            path.write_text(json.dumps({'version': 1, 'identity': 'a' * 64, 'user': 0,
                                        'changes': [{'package': 'com.test;reboot', 'original': 0}],
                                        'original_home': '', 'home_attempted': False}))
            with self.assertRaises(ValueError): read_restore(path)

    def test_imported_selection_rejects_shell_syntax(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'preset.json'
            path.write_text(json.dumps({'version': 1, 'packages': ['com.test;reboot'], 'keep': {}}))
            with self.assertRaises(ValueError): read_preset(path)

    def test_imported_selection_requires_real_boolean_feature_choices(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'preset.json'
            path.write_text(json.dumps({'version': 1, 'packages': [], 'keep': {'updates': 'false'}}))
            with self.assertRaises(ValueError): read_preset(path)


if __name__ == '__main__': unittest.main()
