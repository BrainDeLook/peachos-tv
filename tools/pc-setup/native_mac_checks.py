"""Real AppKit checks on macOS. No network and no device changes."""
import sys
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

if sys.platform == 'darwin':
    import AppKit as A
    import mac_app as native
    from core import Device, PEACH


@unittest.skipUnless(sys.platform == 'darwin', 'AppKit requires macOS')
class NativeMacTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.assets = patch('mac_app.ASSETS', Path(self.folder.name))
        self.assets.start()
        self.app = A.NSApplication.sharedApplication()
        self.controller = native.Installer.alloc().init()
        self.controller.connecting = False
        self.controller.build()

    def tearDown(self):
        self.controller.window.setDelegate_(None)
        self.controller.window.orderOut_(None)
        self.controller.window.close()
        self.assets.stop()
        self.folder.cleanup()

    def test_pages_use_native_scroll_views_and_controls(self):
        self.assertEqual(4, self.controller.tabs.numberOfTabViewItems())
        for tab in self.controller.tabs.tabViewItems():
            self.assertIsInstance(tab.view(), A.NSScrollView)
            self.assertTrue(tab.view().hasVerticalScroller())
        self.assertIsInstance(self.controller.target, A.NSTextField)
        self.assertIsInstance(self.controller.package_table, A.NSTableView)
        self.assertFalse(self.controller.review_button.isEnabled())

    def test_busy_connection_allows_cancel_and_blocks_mutating_controls(self):
        self.controller.busy = self.controller.connecting = True
        self.controller.sync_controls()
        self.assertTrue(self.controller.cancel_button.isEnabled())
        self.assertFalse(self.controller.target.isEnabled())
        self.assertFalse(self.controller.review_button.isEnabled())
        self.controller.cancel_(None)
        self.assertTrue(self.controller.cancelled.is_set())

    def test_connected_catalog_and_search_are_backed_by_data_not_row_widgets(self):
        packages = {'org.example.app' + str(i) for i in range(1000)}
        device = Device('fixture', 'a'*64, 0, {}, packages, set(), packages, PEACH, set())
        self.controller.connected((object(), device))
        self.assertEqual(1000, self.controller.numberOfRowsInTableView_(self.controller.package_table))
        self.controller.search.setStringValue_('org.example.app999')
        self.controller.refresh_packages()
        self.assertEqual(['org.example.app999'], self.controller.visible_packages)
        self.assertFalse(self.controller.expert_choice.state())
        self.assertTrue(self.controller.home_choice.state())
        self.assertFalse(self.controller.compat_choice.state())
        self.assertEqual([], list(self.controller.selected))


if __name__ == '__main__':
    unittest.main()
