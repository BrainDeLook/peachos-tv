import json
import shutil
import subprocess
from pathlib import Path
import tempfile
import unittest
import threading
import tkinter as tk
import zipfile
from unittest.mock import Mock, patch

from catalog import candidates, protected, matching_profiles, RULES, REFERENCE
from core import Adb, Device, apply_plan, restore, read_restore, read_preset, packages, component, PEACH, AIRPLAY, wait_for_device, inspect_apk, airplay_compatibility
from wizard import Wizard, portable_adb

XIAOMI = {'ro.product.manufacturer': 'Xiaomi', 'ro.product.model': 'MiTV-AESP0', 'ro.product.device': 'aquaman', 'ro.build.version.sdk': '28'}


class FakeAdb:
    def __init__(self, fail=False):
        self.calls, self.disabled, self.fail = [], set(), fail

    def run(self, *args, **kwargs):
        if args[0] == 'shell':
            return self.shell(*args[1:])
        raise AssertionError('Unexpected ADB call: ' + repr(args))

    def shell(self, *args):
        self.calls.append(args)
        if args[:2] == ('dumpsys', 'package'): return 'User 0: installed=true enabled=0'
        if args[:2] == ('pm', 'disable-user'):
            self.disabled.add(args[-1])
            if self.fail: raise TimeoutError('Connection lost after command')
        if args[:2] in (('pm', 'enable'), ('pm', 'default-state')): self.disabled.discard(args[-1])
        if args[:4] == ('pm', 'list', 'packages', '-d'): return '\n'.join('package:' + p for p in self.disabled)
        if args[:3] == ('pm', 'list', 'packages'): return 'package:' + PEACH.split('/')[0]
        return ''


def device(identity='a' * 64):
    return Device('test', identity, 0, XIAOMI,
                  {'com.miui.tv.analytics', 'com.android.systemui'}, set(), set(), 'com.google.android.tvlauncher/.Main')


class SetupTests(unittest.TestCase):
    def setUp(self):
        # These tests exercise plan ordering/rollback. Foreground timing and real
        # capability parsing have separate tests in test_home_setup.py.
        check = patch('core.verify_home', side_effect=lambda adb, user: component(adb.shell(
            'cmd', 'package', 'resolve-activity', '--brief', '--user', user,
            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')) == PEACH)
        check.start()
        self.addCleanup(check.stop)

    def test_android_shell_quotes_empty_fields_and_service_inner_class_once_at_boundary(self):
        adb = Adb('fixture-adb')
        adb.run = Mock(return_value='ok')
        self.assertEqual('ok', adb.shell('am', 'broadcast', '--es', 'stock_package', '',
                                        '--es', 'service', 'org.example/.Service$Inner'))
        adb.run.assert_called_once_with('shell', "am broadcast --es stock_package '' --es service 'org.example/.Service$Inner'")
    def destroy_ui(self, root):
        root.update_idletasks()
        for callback in root.tk.call('after', 'info'):
            root.after_cancel(callback)
        root.destroy()

    def test_optional_module_ui_is_unchecked_and_absent_feature_is_disabled(self):
        with tempfile.TemporaryDirectory() as folder, patch('wizard.ASSETS', Path(folder)):
            root = tk.Tk(); root.withdraw()
            self.addCleanup(self.destroy_ui, root)
            wizard = Wizard(root)
            self.assertIsNone(wizard.airplay_module)
            self.assertFalse(wizard.airplay.get())
            self.assertEqual(['disabled', 'disabled'], [str(choice['state']) for choice in wizard.airplay_choices])
            self.assertIn('остаётся поиск', wizard.airplay_hint.get())

    def test_optional_module_ui_rejects_unsupported_android_abi_on_connect(self):
        with tempfile.TemporaryDirectory() as folder, patch('wizard.ASSETS', Path(folder)):
            with zipfile.ZipFile(Path(folder) / 'PeachOS-AirPlay.apk', 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'test')
                archive.writestr('lib/armeabi-v7a/libairplay.so', b'lib')
            root = tk.Tk(); root.withdraw()
            self.addCleanup(self.destroy_ui, root)
            wizard = Wizard(root)
            self.assertFalse(wizard.airplay.get())
            wizard.airplay.set(True)
            current = device()
            current.props = dict(XIAOMI, **{'ro.product.cpu.abilist': 'arm64-v8a'})
            wizard.connected((Mock(), current))
            self.assertFalse(wizard.airplay.get())
            self.assertEqual(['disabled', 'disabled'], [str(choice['state']) for choice in wizard.airplay_choices])
            self.assertIn('arm64-v8a', wizard.airplay_hint.get())
            current.props = dict(XIAOMI, **{'ro.product.cpu.abilist': 'armeabi-v7a'})
            wizard.connected((Mock(), current))
            self.assertFalse(wizard.airplay.get())
            self.assertEqual(['normal', 'normal'], [str(choice['state']) for choice in wizard.airplay_choices])

    def test_catalogue_is_unique_and_new_common_candidates_have_upstream_evidence(self):
        self.assertEqual(len(RULES), len({r.package for r in RULES}))
        for package in ('tv.samba.ssm', 'org.droidtv.candeebug', 'com.tcl.bi', 'com.google.android.play.games'):
            self.assertIn(package, REFERENCE['packages'])
            self.assertIn(package, candidates({}, {package}, set(), '')[1])

    def test_reference_entry_does_not_bypass_normal_protection(self):
        self.assertIn('com.google.android.tungsten.setupwraith', REFERENCE['packages'])
        self.assertEqual({}, candidates({}, {'com.google.android.tungsten.setupwraith'}, set(), '')[1])

    def test_update_only_rejects_cleanup_home_and_missing_install_before_mutation(self):
        current = device()
        current.installed.add(PEACH.split('/')[0])
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            for selected, home in ((['com.miui.tv.analytics'], False), ([], True)):
                with self.assertRaises(ValueError):
                    apply_plan(adb, current, selected, set(), Path(folder)/'a.apk', home, Path(folder), update_only=True)
            current.installed.remove(PEACH.split('/')[0])
            with self.assertRaises(ValueError):
                apply_plan(adb, current, [], set(), Path(folder)/'a.apk', False, Path(folder), update_only=True)
        self.assertEqual([], adb.calls)

    def test_update_only_installs_without_any_disable_or_home_command(self):
        class InstallingAdb(FakeAdb):
            def run(self, *args, **kwargs): self.calls.append(args); return 'Success'
        current = device()
        current.installed.add(PEACH.split('/')[0])
        adb = InstallingAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            file = Path(folder)/'launcher.apk'
            with zipfile.ZipFile(file, 'w') as archive: archive.writestr('AndroidManifest.xml', b'test')
            path = apply_plan(adb, current, [], set(), file, False, Path(folder)/'backups', update_only=True)
            self.assertEqual([('install', '-r', str(file))], adb.calls)
            journal = read_restore(path)
            self.assertTrue(journal['update_only'])
            self.assertEqual([], journal['changes'])
            self.assertFalse(journal['home_attempted'])
            self.assertFalse(journal['optional_modules']['airplay'])

    def test_airplay_uses_android_os_abis_not_hardware_model(self):
        with tempfile.TemporaryDirectory() as folder:
            module = Path(folder) / 'module.apk'
            with zipfile.ZipFile(module, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'test')
                archive.writestr('lib/armeabi-v7a/libairplay-jni.so', b'lib')
            info = inspect_apk(module)
            self.assertEqual(frozenset({'armeabi-v7a'}), info.abis)
            self.assertEqual('', airplay_compatibility(info, {'ro.build.version.sdk': '31',
                             'ro.product.cpu.abilist': 'arm64-v8a,armeabi-v7a,armeabi'}))
            self.assertEqual('', airplay_compatibility(info, {'ro.build.version.sdk': '31',
                             'ro.product.cpu.abilist': 'armeabi-v7a,armeabi'}))
            self.assertIn('arm64-v8a', airplay_compatibility(info, {'ro.build.version.sdk': '31',
                          'ro.product.cpu.abilist': 'arm64-v8a'}))
            self.assertTrue(airplay_compatibility(info, {'ro.build.version.sdk': '23',
                            'ro.product.cpu.abilist': 'armeabi-v7a'}))
            self.assertTrue(airplay_compatibility(info, {'ro.build.version.sdk': '31'}))

    def test_incompatible_optional_module_stops_before_launcher_install_and_cleanup(self):
        current = device()
        current.props = dict(XIAOMI, **{'ro.product.cpu.abilist': 'arm64-v8a'})
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            launcher, module = Path(folder) / 'launcher.apk', Path(folder) / 'airplay.apk'
            for file in (launcher, module):
                with zipfile.ZipFile(file, 'w') as archive:
                    archive.writestr('AndroidManifest.xml', b'test')
                    if file == module: archive.writestr('lib/armeabi-v7a/libairplay.so', b'lib')
            with self.assertRaisesRegex(ValueError, 'arm64-v8a'):
                apply_plan(adb, current, ['com.miui.tv.analytics'], set(), launcher, False,
                           Path(folder) / 'backups', airplay_apk=module)
            self.assertFalse((Path(folder) / 'backups').exists())
        self.assertEqual([], adb.calls)

    def test_optional_module_failure_is_journalled_and_stops_before_home_cleanup(self):
        class InstallingAdb(FakeAdb):
            def run(self, *args, **kwargs):
                self.calls.append(args)
                if args[-1].endswith('airplay.apk'): raise RuntimeError('INSTALL_FAILED_UPDATE_INCOMPATIBLE')
                return 'Success'
        current = device()
        current.props = dict(XIAOMI, **{'ro.product.cpu.abilist': 'armeabi-v7a,armeabi'})
        adb = InstallingAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            launcher, module = Path(folder) / 'launcher.apk', Path(folder) / 'airplay.apk'
            for file in (launcher, module):
                with zipfile.ZipFile(file, 'w') as archive:
                    archive.writestr('AndroidManifest.xml', b'test')
                    if file == module: archive.writestr('lib/armeabi-v7a/libairplay.so', b'lib')
            with self.assertRaisesRegex(RuntimeError, 'UPDATE_INCOMPATIBLE'):
                apply_plan(adb, current, ['com.miui.tv.analytics'], set(), launcher, True,
                           Path(folder) / 'backups', airplay_apk=module)
            journal = read_restore(next((Path(folder) / 'backups').glob('*/restore.json')))
            self.assertTrue(journal['optional_modules']['airplay'])
            self.assertEqual(['launcher', 'airplay'], [entry['role'] for entry in journal['installs']])
            self.assertTrue(journal['installs'][0]['confirmed'])
            self.assertFalse(journal['installs'][1]['confirmed'])
            self.assertFalse(journal['home_attempted'])
            self.assertFalse(journal['complete'])
            self.assertEqual([], journal['changes'])

    def test_update_only_rejects_optional_module_before_mutation(self):
        current = device()
        current.installed.add(PEACH.split('/')[0])
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaisesRegex(ValueError, 'только APK лаунчера'):
                apply_plan(adb, current, [], set(), Path(folder) / 'launcher.apk', False, Path(folder),
                           update_only=True, airplay_apk=Path(folder) / 'airplay.apk')
        self.assertEqual([], adb.calls)

    def test_launcher_only_install_never_installs_existing_companion(self):
        class InstallingAdb(FakeAdb):
            def run(self, *args, **kwargs): self.calls.append(args); return 'Success'
        current = device()
        current.installed.add(AIRPLAY)
        adb = InstallingAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            launcher = Path(folder) / 'launcher.apk'
            with zipfile.ZipFile(launcher, 'w') as archive: archive.writestr('AndroidManifest.xml', b'test')
            path = apply_plan(adb, current, [], set(), launcher, False, Path(folder) / 'backups')
            self.assertEqual([('install', '-r', str(launcher))], adb.calls)
            self.assertFalse(read_restore(path)['optional_modules']['airplay'])

    def test_authorization_waits_then_connects_instead_of_failing(self):
        class AuthorizingAdb:
            serial = ''
            def __init__(self): self.polls = 0
            def run(self, *args, **kwargs):
                if args[0] == 'connect': return 'failed to authenticate'
                self.polls += 1
                return 'List of devices attached\n192.168.1.5:5555\t' + ('unauthorized' if self.polls == 1 else 'device')
        adb = AuthorizingAdb()
        messages = []
        with patch('threading.Event.wait', return_value=False):
            self.assertEqual('192.168.1.5:5555', wait_for_device(adb, '192.168.1.5', threading.Event(), messages.append))
        self.assertTrue(any('Подтвердите' in message for message in messages))
        self.assertEqual(2, adb.polls)

    def test_authorization_can_be_cancelled_without_device_changes(self):
        event = threading.Event(); event.set()
        class UsbAdb:
            def run(self, *args, **kwargs): raise AssertionError('No calls after cancellation')
        with self.assertRaises(InterruptedError): wait_for_device(UsbAdb(), '', event)

    def test_cancelled_network_connection_does_not_start_adb(self):
        event = threading.Event(); event.set()
        adb = Mock()
        with self.assertRaises(InterruptedError):
            wait_for_device(adb, '192.168.1.5', event)
        adb.run.assert_not_called()

    def test_disable_confirmation_queries_only_changed_package(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            apply_plan(adb, device(), ['com.miui.tv.analytics'], set(), None, False, Path(folder))
        self.assertIn(('pm', 'list', 'packages', '-d', '--user', 0, 'com.miui.tv.analytics'), adb.calls)

    def test_oversized_restore_is_rejected_before_loading_json(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'restore.json'
            with path.open('wb') as stream:
                stream.truncate(4 * 1024 * 1024 + 1)
            with patch('core.json.loads') as loads:
                with self.assertRaisesRegex(ValueError, 'слишком большой'):
                    read_restore(path)
                loads.assert_not_called()

    def test_package_toggle_preserves_table_and_changes_only_checkbox(self):
        wizard = Wizard.__new__(Wizard)
        wizard.blocked = Mock(return_value='')
        wizard.selected = set()
        wizard.device = device()
        wizard.expert = Mock(); wizard.expert.get.return_value = False
        wizard.clean = Mock(); wizard.clean.get.return_value = False
        wizard.cleanup_summary = Mock()
        wizard.status, wizard.tree, wizard.refresh = Mock(), Mock(), Mock()
        wizard.toggle_package('com.miui.tv.analytics')
        wizard.tree.set.assert_called_once_with('com.miui.tv.analytics', 'check', '☑')
        wizard.refresh.assert_not_called()
        wizard.toggle_package('com.miui.tv.analytics')
        self.assertEqual(set(), wizard.selected)
        wizard.tree.set.assert_called_with('com.miui.tv.analytics', 'check', '☐')
        wizard.tree.delete.assert_not_called()

    def test_cleanup_summary_keeps_selected_packages_distinct_from_enabled_cleanup(self):
        wizard = Wizard.__new__(Wizard)
        wizard.device = device()
        wizard.selected = {'com.test.first', 'com.test.second'}
        wizard.clean = Mock(); wizard.clean.get.return_value = False
        wizard.expert = Mock(); wizard.expert.get.return_value = False
        wizard.cleanup_summary, wizard.status = Mock(), Mock()
        wizard.selection_summary()
        self.assertIn('Выбрано: 2', wizard.cleanup_summary.set.call_args.args[0])
        self.assertIn('не будут изменены', wizard.cleanup_summary.set.call_args.args[0])
        self.assertIn('очистка выключена', wizard.status.set.call_args.args[0])

    def test_cleanup_summary_explains_confirmation_before_active_cleanup(self):
        wizard = Wizard.__new__(Wizard)
        wizard.device = device()
        wizard.selected = {'com.test.first', 'com.test.second'}
        wizard.clean = Mock(); wizard.clean.get.return_value = True
        wizard.expert = Mock(); wizard.expert.get.return_value = False
        wizard.cleanup_summary, wizard.status = Mock(), Mock()
        wizard.selection_summary()
        self.assertIn('Будет отключено: 2', wizard.cleanup_summary.set.call_args.args[0])
        self.assertIn('после подтверждения плана', wizard.cleanup_summary.set.call_args.args[0])
        self.assertIn('К отключению: 2', wizard.status.set.call_args.args[0])

    def test_cleanup_summary_keeps_expert_warning_visible_with_cleanup_on_or_off(self):
        wizard = Wizard.__new__(Wizard)
        wizard.device = device()
        wizard.selected = set()
        wizard.clean, wizard.expert = Mock(), Mock()
        wizard.expert.get.return_value = True
        wizard.cleanup_summary, wizard.status = Mock(), Mock()
        for enabled in (False, True):
            with self.subTest(cleanup=enabled):
                wizard.clean.get.return_value = enabled
                wizard.selection_summary()
                self.assertIn('Экспертный режим.', wizard.status.set.call_args.args[0])
                self.assertIn('план', wizard.status.set.call_args.args[0])

    def test_portable_adb_hashes_streams_and_preserves_unchanged_cached_executable(self):
        with tempfile.TemporaryDirectory() as folder:
            assets, data = Path(folder) / 'assets', Path(folder) / 'data'
            (assets / 'platform-tools').mkdir(parents=True)
            for name in ('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt'):
                (assets / 'platform-tools' / name).write_bytes(name.encode())
            with patch('wizard.ASSETS', assets), patch('wizard.DATA', data), patch.object(Path, 'read_bytes', side_effect=AssertionError('Whole-file hash')):
                executable = portable_adb()
                with patch('wizard.shutil.copyfile') as copy:
                    self.assertEqual(executable, portable_adb())
                    copy.assert_not_called()
                (assets / 'platform-tools' / 'NOTICE.txt').write_text('updated', encoding='utf-8')
                portable_adb()
            self.assertEqual('updated', (data / 'platform-tools' / 'NOTICE.txt').read_text(encoding='utf-8'))

    @unittest.skipUnless(shutil.which('powershell') or shutil.which('pwsh'), 'PowerShell unavailable')
    def test_portable_build_embeds_arbitrary_apk_name_under_expected_launcher_name(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, adb, output = root / 'my launcher-release.apk', root / 'platform tools', root / 'portable output'
            source.write_bytes(b'APK fixture')
            adb.mkdir()
            fake_python = root / 'fake-python.ps1'
            fake_python.write_text("$args | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'args.txt')\n$global:LASTEXITCODE = 0\n", encoding='utf-8')
            result = subprocess.run([shutil.which('powershell') or shutil.which('pwsh'), '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                     '-File', str(Path(__file__).with_name('build.ps1')), '-Apk', str(source),
                                     '-AdbDirectory', str(adb), '-Python', str(fake_python), '-Output', str(output)],
                                    capture_output=True, text=True, timeout=30,
                                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            bundled = output / 'build' / 'portable-assets' / 'PeachOS-TV.apk'
            self.assertEqual(source.read_bytes(), bundled.read_bytes())
            arguments = (root / 'args.txt').read_text(encoding='utf-8-sig')
            self.assertIn(str(bundled) + ';.', arguments)
            self.assertNotIn('PeachOS-AirPlay', arguments)

    @unittest.skipUnless(shutil.which('powershell') or shutil.which('pwsh'), 'PowerShell unavailable')
    def test_optional_companion_build_requires_source_and_bundles_all_three_assets(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            launcher, module, source, license_file = (root / name for name in ('launcher.apk', 'receiver.apk', 'source.zip', 'COPYING'))
            launcher.write_bytes(b'APK fixture')
            with zipfile.ZipFile(module, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'test')
                archive.writestr('lib/armeabi-v7a/libairplay.so', b'lib')
            with zipfile.ZipFile(source, 'w') as archive: archive.writestr('module/source.java', b'source')
            license_file.write_text('GPL-3.0', encoding='utf-8')
            adb = root / 'adb'; adb.mkdir()
            fake_python = root / 'fake-python.ps1'
            fake_python.write_text("$args | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'args.txt')\n$global:LASTEXITCODE = 0\n", encoding='utf-8')
            command = [shutil.which('powershell') or shutil.which('pwsh'), '-NoProfile', '-ExecutionPolicy', 'Bypass',
                       '-File', str(Path(__file__).with_name('build.ps1')), '-Apk', str(launcher),
                       '-AdbDirectory', str(adb), '-Python', str(fake_python), '-Output', str(root / 'output'),
                       '-AirPlayApk', str(module)]
            result = subprocess.run(command, capture_output=True, text=True, errors='replace', timeout=30,
                                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('AirPlay requires', result.stderr)
            self.assertFalse((root / 'args.txt').exists())
            result = subprocess.run(command + ['-AirPlaySource', str(source), '-AirPlayLicense', str(license_file)],
                                    capture_output=True, text=True, errors='replace', timeout=30,
                                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            arguments = (root / 'args.txt').read_text(encoding='utf-8-sig')
            for original, name in ((module, 'PeachOS-AirPlay.apk'), (source, 'PeachOS-AirPlay-source.zip'), (license_file, 'LICENSE-AirPlay.txt')):
                bundled = root / 'output' / 'build' / 'portable-assets' / name
                self.assertEqual(original.read_bytes(), bundled.read_bytes())
                self.assertIn(str(bundled) + ';.', arguments)
            result = subprocess.run(command[:-2], capture_output=True, text=True, errors='replace', timeout=30,
                                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('PeachOS-AirPlay', (root / 'args.txt').read_text(encoding='utf-8-sig'))

    def test_expert_mode_can_disable_protected_package_and_restore_it(self):
        adb = FakeAdb()
        current = device()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            path = apply_plan(adb, current, ['com.android.systemui'], {'voice'}, None, False, Path(folder), expert=True)
            self.assertTrue(read_restore(path)['expert'])
            self.assertIn('com.android.systemui', adb.disabled)
            restore(adb, current, path)
            self.assertEqual(set(), adb.disabled)

    def test_expert_mode_still_rejects_noninstalled_names(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            with self.assertRaises(ValueError):
                apply_plan(adb, device(), ['bad;reboot'], set(), None, False, Path(folder), expert=True)
        self.assertEqual([], adb.calls)

    def test_multiple_apks_install_in_order_and_are_journalled(self):
        class InstallingAdb(FakeAdb):
            def run(self, *args, **kwargs): self.calls.append(args); return 'Success'
        adb = InstallingAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            files = [Path(folder) / name for name in ('one.apk', 'two.apk')]
            for file in files:
                with zipfile.ZipFile(file, 'w') as archive: archive.writestr('AndroidManifest.xml', b'test')
            path = apply_plan(adb, device(), [], set(), None, False, Path(folder) / 'backups', extra_apks=files)
            self.assertEqual([str(file) for file in files], [call[-1] for call in adb.calls])
            self.assertTrue(all(entry['confirmed'] for entry in read_restore(path)['installs']))

    def test_bad_second_apk_stops_before_any_install_or_cleanup(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            good, bad = Path(folder) / 'good.apk', Path(folder) / 'bad.apk'
            with zipfile.ZipFile(good, 'w') as archive: archive.writestr('AndroidManifest.xml', b'test')
            bad.write_text('not apk')
            with self.assertRaises(ValueError):
                apply_plan(adb, device(), ['com.miui.tv.analytics'], set(), None, False, Path(folder) / 'backups', extra_apks=[good, bad])
        self.assertEqual([], adb.calls)

    def test_second_install_failure_records_first_success_and_skips_cleanup(self):
        class InstallingAdb(FakeAdb):
            def run(self, *args, **kwargs):
                self.calls.append(args)
                if args[-1].endswith('two.apk'): raise RuntimeError('INSTALL_FAILED_NO_MATCHING_ABIS')
                return 'Success'
        adb = InstallingAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=device()):
            files = [Path(folder) / name for name in ('one.apk', 'two.apk')]
            for file in files:
                with zipfile.ZipFile(file, 'w') as archive: archive.writestr('AndroidManifest.xml', b'test')
            with self.assertRaises(RuntimeError):
                apply_plan(adb, device(), ['com.miui.tv.analytics'], set(), None, False, Path(folder) / 'backups', extra_apks=files)
            journal = read_restore(next((Path(folder) / 'backups').glob('*/restore.json')))
            self.assertTrue(journal['installs'][0]['confirmed'])
            self.assertFalse(journal['installs'][1]['confirmed'])
            self.assertEqual([], journal['changes'])
            self.assertEqual(set(), adb.disabled)

    def test_expert_mode_allows_unknown_system_package_and_ignores_keep(self):
        current = device()
        current.installed.update({'com.vendor.unknown', 'com.google.android.katniss'})
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            apply_plan(adb, current, ['com.vendor.unknown', 'com.google.android.katniss'], {'voice'}, None, False, Path(folder), expert=True)
        self.assertEqual({'com.vendor.unknown', 'com.google.android.katniss'}, adb.disabled)

    def test_short_component_is_normalized_at_adb_boundary(self):
        self.assertEqual('com.example/com.example.Main', component('priority=2\ncom.example/.Main'))
        self.assertEqual(PEACH, component(PEACH))

    def test_xiaomi_home_fallback_is_opt_in_journalled_and_restorable(self):
        current = device()
        current.installed.add(PEACH.split('/')[0])
        class XiaomiAdb(FakeAdb):
            def shell(self, *args):
                if args[:3] == ('cmd', 'package', 'resolve-activity'):
                    return PEACH if 'com.google.android.tvlauncher' in self.disabled else current.home
                return super().shell(*args)
        adb = XiaomiAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            path = apply_plan(adb, current, [], set(), None, True, Path(folder), xiaomi_home_fallback=True)
            self.assertTrue(read_restore(path)['changes'][0]['confirmed'])
            self.assertEqual({'com.google.android.tvlauncher'}, adb.disabled)
            restore(adb, current, path)
            self.assertEqual(set(), adb.disabled)

    def test_failed_xiaomi_fallback_rolls_back_before_cleanup(self):
        current = device()
        current.installed.add(PEACH.split('/')[0])
        class XiaomiAdb(FakeAdb):
            def shell(self, *args):
                if args[:3] == ('cmd', 'package', 'resolve-activity'): return current.home
                return super().shell(*args)
        adb = XiaomiAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaises(RuntimeError):
                apply_plan(adb, current, ['com.miui.tv.analytics'], set(), None, True, Path(folder), xiaomi_home_fallback=True)
        self.assertEqual(set(), adb.disabled)
        self.assertFalse(any(call[:2] == ('pm', 'disable-user') and call[-1] == 'com.miui.tv.analytics' for call in adb.calls))

    def test_xiaomi_fallback_rejects_other_devices(self):
        current = device()
        current.props = {}
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaises(ValueError):
                apply_plan(adb, current, [], set(), None, True, Path(folder), xiaomi_home_fallback=True)
        self.assertEqual([], adb.calls)

    def test_tcl_rejected_home_preserves_install_and_stops_before_cleanup_with_specific_advice(self):
        current = device()
        current.props = {'ro.product.manufacturer': 'TCL', 'ro.build.version.sdk': '34'}
        current.home = 'com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity'
        current.installed.update({PEACH.split('/')[0], 'com.google.android.feedback'})
        class TclAdb(FakeAdb):
            def shell(self, *args):
                if args[:3] == ('cmd', 'package', 'resolve-activity'): return current.home
                return super().shell(*args)
        adb = TclAdb()
        with tempfile.TemporaryDirectory() as folder, patch('core.discover', return_value=current):
            with self.assertRaisesRegex(RuntimeError, 'Совместимый Home') as failure:
                apply_plan(adb, current, ['com.google.android.feedback'], set(), None, True, Path(folder))
            self.assertNotIn('Xiaomi', str(failure.exception))
            self.assertIn('SetupWraith', str(failure.exception))
            journal = read_restore(next(Path(folder).glob('*/restore.json')))
            self.assertTrue(journal['home_attempted'])
            self.assertEqual([], journal['changes'])
        self.assertEqual(set(), adb.disabled)
        self.assertIn(('cmd', 'package', 'set-home-activity', '--user', '0', current.home), adb.calls)

    def test_android_framework_is_a_valid_inventory_package_but_protected(self):
        self.assertEqual({'android', 'com.google.android.gms'}, packages('package:android\npackage:com.google.android.gms\n'))
        self.assertTrue(protected('android'))

    def test_profile_requires_model_and_android_version(self):
        self.assertEqual(('xiaomi-aesp0',), matching_profiles(XIAOMI))
        self.assertEqual((), matching_profiles(dict(XIAOMI, **{'ro.build.version.sdk': '31'})))
        self.assertEqual((), matching_profiles(dict(XIAOMI, **{'ro.product.model': 'Different stick'})))

    def test_tcl_android14_au02_recommends_only_verified_profile_packages(self):
        props = {'ro.product.manufacturer': 'TCL', 'ro.product.device': 'G10', 'ro.product.name': 'G10_4K_GB',
                 'ro.build.version.sdk': '34', 'ro.build.display.id': 'G10-user 14 UTT2.250416.001 AU02 release-keys'}
        installed = {'com.google.android.feedback', 'com.tcl.logkit', 'com.tcl.esticker',
                     'com.tcl.tv.tclhome_passive', 'com.tcl.exhibit', 'com.tcl.ttvs', 'com.tcl.waterfall.overseas'}
        profiles, rows = candidates(props, installed, set(), '')
        self.assertEqual(('tcl-g10-au02',), profiles)
        self.assertEqual(installed - {'com.tcl.exhibit', 'com.tcl.ttvs'}, {p for p, rule in rows.items() if rule.suggested})
        self.assertNotIn('com.tcl.exhibit', rows)  # AU02 also uses this package for Art Mode.
        self.assertEqual(('updates',), rows['com.tcl.ttvs'].features)
        self.assertFalse(rows['com.tcl.ttvs'].suggested)
        for override in ({'ro.build.version.sdk': '33'}, {'ro.build.display.id': 'different AU02 firmware'},
                         {'ro.product.name': 'Different TV'}):
            self.assertEqual((), matching_profiles(dict(props, **override)))

    def test_standalone_vendor_apps_remain_optional_on_unmatched_firmware(self):
        installed = {'com.tcl.waterfall.overseas', 'com.tcl.tv.plus', 'com.tcl.tv.tclhome_passive',
                     'com.tcl.ocean.instructions', 'com.tcl.repairguide', 'com.google.android.tvrecommendations'}
        _, rows = candidates({}, installed, set(), '')
        self.assertEqual(installed, set(rows))
        self.assertFalse(any(rule.suggested for rule in rows.values()))

    def test_synced_tcl_content_rules_never_make_hdmi_or_cast_a_cleanup_default(self):
        installed = {'com.tcl.tv', 'com.tcl.tvinput', 'com.tcl.channelplus', 'com.tcl.miracast', 'com.tcl.hearaid'}
        _, rows = candidates({}, installed, set(), '')
        self.assertNotIn('com.tcl.tv', rows)
        self.assertNotIn('com.tcl.tvinput', rows)
        self.assertEqual(('channels',), rows['com.tcl.channelplus'].features)
        self.assertEqual(('cast',), rows['com.tcl.miracast'].features)
        self.assertEqual(('accessibility',), rows['com.tcl.hearaid'].features)
        self.assertFalse(any(rule.suggested for rule in rows.values()))

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
