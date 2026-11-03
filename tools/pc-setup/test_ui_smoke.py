"""Native Tk smoke checks with an isolated fixture and no real ADB operations."""
from pathlib import Path
import tempfile
import tkinter as tk
from tkinter import ttk
import unittest
from unittest.mock import Mock, patch

from core import Device, PEACH
from ui import DARK, LIGHT, ScrollPage
from wizard import Wizard, read_ui_settings, write_ui_settings


def descendants(parent):
    for widget in parent.winfo_children():
        yield widget
        yield from descendants(widget)


class InstallerUiSmokeTests(unittest.TestCase):
    def setUp(self):
        self.assets = tempfile.TemporaryDirectory()
        self.addCleanup(self.assets.cleanup)
        assets = patch('wizard.ASSETS', Path(self.assets.name))
        assets.start()
        self.addCleanup(assets.stop)
        data = patch('wizard.DATA', Path(self.assets.name) / 'data')
        data.start()
        self.addCleanup(data.stop)
        self.root = tk.Tk()
        self.root.withdraw()
        self.addCleanup(self.destroy_ui)
        self.app = Wizard(self.root)

    def destroy_ui(self):
        self.root.update_idletasks()
        for callback in self.root.tk.call('after', 'info'):
            self.root.after_cancel(callback)
        self.root.destroy()

    def test_busy_disables_edits_and_restores_readonly_and_unavailable_module(self):
        combo = next(widget for widget in descendants(self.app.frame) if isinstance(widget, ttk.Combobox))
        self.assertTrue(combo.instate(('readonly', '!disabled')))
        self.assertTrue(all(choice.instate(('disabled',)) for choice in self.app.airplay_choices))
        self.app.set_busy(True)
        self.assertTrue(combo.instate(('readonly', 'disabled')))
        self.assertTrue(self.app.mode_button.instate(('disabled',)))
        self.assertTrue(self.app.theme_button.instate(('disabled',)))
        self.assertTrue(self.app.simple_connect_button.instate(('disabled',)))
        self.app.set_busy(False)
        self.assertTrue(combo.instate(('readonly', '!disabled')))
        self.assertTrue(self.app.mode_button.instate(('!disabled',)))
        self.assertTrue(self.app.simple_connect_button.instate(('!disabled',)))
        self.assertTrue(all(choice.instate(('disabled',)) for choice in self.app.airplay_choices))

    def test_theme_roundtrip_preserves_fields_and_recolors_plan_and_canvas(self):
        self.app.target.set('fixture-device')
        self.app.connected((Mock(), Device('fixture', 'a' * 64, 0, {}, {PEACH.split('/')[0]}, set(), set(), PEACH, set())))
        self.app.review()
        self.app.dark_mode.set(True)
        self.app.change_theme()
        self.assertTrue(read_ui_settings(Path(self.assets.name) / 'data' / 'ui-settings.json'))
        self.assertEqual('fixture-device', self.app.target.get())
        self.assertIsNotNone(self.app.device)
        self.assertEqual(DARK.background, self.root.cget('background'))
        self.assertEqual(DARK.disabled, ttk.Style(self.root).lookup('Vertical.TScrollbar', 'background', ('disabled',)))
        for widget in descendants(self.root):
            if isinstance(widget, (tk.Canvas, tk.Toplevel)):
                self.assertEqual(DARK.background, widget.cget('background'))
            elif isinstance(widget, tk.Text):
                self.assertEqual(DARK.surface, widget.cget('background'))
                self.assertEqual(DARK.text, widget.cget('foreground'))
        self.app.dark_mode.set(False)
        self.app.change_theme()
        self.assertFalse(read_ui_settings(Path(self.assets.name) / 'data' / 'ui-settings.json'))
        self.assertEqual(LIGHT.surface, ttk.Style(self.root).lookup('Card.TFrame', 'background'))

    def test_invalid_theme_preferences_fall_back_and_atomic_write_replaces_them(self):
        path = Path(self.assets.name) / 'settings.json'
        for value in ('not json', '[]', '{"version":true,"dark":true}', '{"version":1,"dark":1}',
                      '{"version":2,"dark":true}', '[' * 1500, ' ' * 4097):
            path.write_text(value, encoding='utf-8')
            self.assertFalse(read_ui_settings(path))
        write_ui_settings(path, True)
        self.assertTrue(read_ui_settings(path))
        self.assertEqual([], list(path.parent.glob('.settings.json.*.tmp')))

    def test_worker_error_discards_stale_device_and_disables_mutations(self):
        self.app.connected((Mock(), Device('fixture', 'a' * 64, 0, {}, {PEACH.split('/')[0]}, set(), set(), PEACH, set())))
        self.app.selected.add('com.test.stale')
        self.app.set_busy(True)
        self.app.events.put(('error', 'fixture failure'))
        with patch('wizard.messagebox.showerror') as error:
            self.app.poll()
        error.assert_called_once()
        self.assertIsNone(self.app.device)
        self.assertIsNone(self.app.adb)
        self.assertFalse(self.app.selected)
        self.assertFalse(self.app.rows)
        self.assertFalse(self.app.tree.get_children())
        for button in (self.app.plan_button, self.app.restore_button, self.app.update_button):
            self.assertTrue(button.instate(('disabled',)))

    def test_narrow_high_dpi_layout_keeps_actions_inside_pages(self):
        self.root.tk.call('tk', 'scaling', 2.0)
        self.root.geometry('940x660')
        self.root.deiconify()
        self.app.switch_mode()
        self.root.update()
        for index in range(4):
            self.app.tabs.select(index)
            self.root.update()
            page = self.app.tabs.nametowidget(self.app.tabs.tabs()[index])
            self.assertIsInstance(page, ScrollPage)
            self.assertGreater(page.canvas.winfo_width(), 600)
            for widget in descendants(page.body):
                if isinstance(widget, (ttk.Button, ttk.Entry, ttk.Combobox, ttk.Checkbutton)):
                    right = widget.winfo_rootx() + widget.winfo_width()
                    self.assertLessEqual(right, page.body.winfo_rootx() + page.body.winfo_width() + 1,
                                         str(widget.cget('text') if 'text' in widget.keys() else widget))
                    if isinstance(widget, (ttk.Button, ttk.Checkbutton)):
                        self.assertGreaterEqual(widget.winfo_width(), widget.winfo_reqwidth())

    def test_approved_plan_uses_reviewed_snapshot_without_reading_changed_ui(self):
        device = Device('fixture', 'a' * 64, 0, {'ro.product.model': 'Fixture TV'},
                        {PEACH.split('/')[0], 'com.test.selected'}, set(), set(), PEACH, set())
        adb = Mock()
        self.app.connected((adb, device))
        self.app.switch_mode()
        self.app.clean.set(True)
        self.app.selected = {'com.test.selected'}
        self.app.extra_apks = [Path('first.apk')]
        self.app.review()
        plan = next(widget for widget in self.root.winfo_children() if isinstance(widget, tk.Toplevel))
        apply_button = next(widget for widget in descendants(plan)
                            if isinstance(widget, ttk.Button) and widget.cget('text') == 'Применить план')
        self.app.selected.add('com.test.not.reviewed')
        self.app.extra_apks.append(Path('not-reviewed.apk'))
        self.app.clean.set(False)
        self.app.home.set(True)
        self.app.adb, self.app.device = Mock(), Mock()
        self.app.job = lambda action, callback: action()
        with patch('wizard.apply_plan') as apply:
            apply_button.invoke()
        args, options = apply.call_args
        self.assertIs(adb, args[0])
        self.assertIs(device, args[1])
        self.assertEqual(('com.test.selected',), args[2])
        self.assertIsInstance(args[3], frozenset)
        self.assertFalse(args[5])
        self.assertEqual((Path('first.apk'),), options['extra_apks'])

    def test_reopening_plan_does_not_accumulate_global_scroll_bindings(self):
        device = Device('fixture', 'a' * 64, 0, {}, {PEACH.split('/')[0]}, set(), set(), PEACH, set())
        self.app.connected((Mock(), device))
        original = self.root.bind_all('<MouseWheel>')
        for _ in range(4):
            self.app.review()
            plan = next(widget for widget in self.root.winfo_children() if isinstance(widget, tk.Toplevel))
            plan.destroy()
        self.assertEqual(original, self.root.bind_all('<MouseWheel>'))


if __name__ == '__main__':
    unittest.main()
