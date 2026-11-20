"""Host integration checks; never connect to or modify a television."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import wizard


class HostPlatformTests(unittest.TestCase):
    def test_unix_adb_cache_preserves_companion_libraries_and_execute_permission(self):
        with tempfile.TemporaryDirectory() as folder:
            assets, data = Path(folder) / 'assets', Path(folder) / 'data'
            tools = assets / 'platform-tools'
            tools.mkdir(parents=True)
            for name in ('adb', 'NOTICE.txt', 'libadbmdns.so'):
                (tools / name).write_bytes(name.encode())
            with patch.multiple(wizard, ASSETS=assets, DATA=data, ADB_NAME='adb'), patch.object(wizard.sys, 'platform', 'linux'):
                executable = wizard.portable_adb()
                self.assertEqual(b'libadbmdns.so', (executable.parent / 'libadbmdns.so').read_bytes())
                # Windows does not expose POSIX execution mode; validate it on Unix CI.
                if __import__('os').name != 'nt':
                    self.assertTrue(executable.stat().st_mode & 0o100)
                with patch.object(wizard.shutil, 'copyfile') as copy:
                    self.assertEqual(executable, wizard.portable_adb())
                    copy.assert_not_called()

    def test_native_folder_open_uses_argument_vector_without_shell(self):
        for platform, command in (('darwin', 'open'), ('linux', 'xdg-open')):
            with self.subTest(platform=platform), patch.object(wizard.sys, 'platform', platform), patch.object(wizard.subprocess, 'Popen') as process:
                wizard.open_folder(Path('/fixture/a folder'))
                self.assertEqual([command, str(Path('/fixture/a folder'))], process.call_args.args[0])
                self.assertNotIn('shell', process.call_args.kwargs)


if __name__ == '__main__':
    unittest.main()
