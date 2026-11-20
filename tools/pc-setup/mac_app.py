"""Native AppKit installer. No Tk widgets, canvas, wheel handlers or UI polling."""
import hashlib
import json
import os
from pathlib import Path
import sys
import threading
import subprocess
from dataclasses import dataclass

import objc
import AppKit as A
import Foundation as F
from PyObjCTools import AppHelper

from catalog import candidates, FEATURES, REFERENCE, protected
from core import Adb, discover, wait_for_device, apply_plan, restore, read_preset, inspect_apk, airplay_compatibility, PEACH, Device
from core import pair_wifi, wifi_services, diagnostic_report, check_home_after_reboot, packages_reenabled
from host_runtime import cache_adb

VERSION = '1.5.24'
ASSETS = Path(sys._MEIPASS) if getattr(sys, 'frozen', False) else Path(__file__).parent
DATA = Path(os.environ.get('PEACHOS_SETUP_DATA', str(Path.home() / 'Library' / 'Application Support' / 'PeachOS TV Setup')))


@dataclass(frozen=True)
class Plan:
    adb: Adb
    device: Device
    selected: tuple[str, ...]
    keep: frozenset[str]
    apk: Path | None
    home: bool
    compatible: bool
    extras: tuple[Path, ...]
    expert: bool
    airplay: Path | None


def label(text, frame, size=13, bold=False):
    field = A.NSTextField.wrappingLabelWithString_(text)
    field.setFrame_(frame)
    field.setFont_(A.NSFont.boldSystemFontOfSize_(size) if bold else A.NSFont.systemFontOfSize_(size))
    field.setAutoresizingMask_(A.NSViewWidthSizable)
    return field


class Surface(A.NSView):
    def drawRect_(self, rect):
        A.NSColor.windowBackgroundColor().setFill()
        A.NSRectFill(rect)


class Document(Surface):
    def isFlipped(self):
        return True


class Installer(A.NSObject):
    @objc.python_method
    def build(self):
        self.device = self.adb = None
        self.rules = {}
        self.selected = set()
        self.visible_packages = []
        self.extras = []
        self.launcher_apk = ASSETS / 'PeachOS-TV.apk'
        self.busy = False
        self.controls = []
        self.cancelled = threading.Event()
        self.module = inspect_apk(ASSETS / 'PeachOS-AirPlay.apk') if (ASSETS / 'PeachOS-AirPlay.apk').exists() else None
        self.window = A.NSWindow.alloc().initWithContentRect_styleMask_backing_defer_(
            A.NSMakeRect(0, 0, 1100, 790), A.NSWindowStyleMaskTitled | A.NSWindowStyleMaskClosable | A.NSWindowStyleMaskMiniaturizable | A.NSWindowStyleMaskResizable,
            A.NSBackingStoreBuffered, False)
        self.window.setTitle_('PeachOS TV · Установщик ' + VERSION)
        self.window.setMinSize_(A.NSMakeSize(940, 680))
        self.window.setDelegate_(self)
        self.window.center()
        root = Surface.alloc().initWithFrame_(self.window.contentView().bounds())
        self.window.setContentView_(root)
        heading = label('PeachOS TV', A.NSMakeRect(28, 730, 400, 34), 26, True)
        heading.setAutoresizingMask_(A.NSViewMinYMargin | A.NSViewWidthSizable)
        root.addSubview_(heading)
        subtitle = label('Установка, обновление и настройка Android TV', A.NSMakeRect(28, 702, 650, 24))
        subtitle.setAutoresizingMask_(A.NSViewMinYMargin | A.NSViewWidthSizable)
        root.addSubview_(subtitle)
        self.tabs = A.NSTabView.alloc().initWithFrame_(A.NSMakeRect(24, 86, 1052, 602))
        self.tabs.setAutoresizingMask_(A.NSViewWidthSizable | A.NSViewHeightSizable)
        root.addSubview_(self.tabs)
        self.pages = {}
        for name in ('Установка', 'Приложения', 'Очистка', 'Копии'):
            item = A.NSTabViewItem.alloc().initWithIdentifier_(name)
            item.setLabel_(name)
            scroll = A.NSScrollView.alloc().initWithFrame_(A.NSMakeRect(0, 0, 1000, 540))
            scroll.setHasVerticalScroller_(True)
            scroll.setAutohidesScrollers_(True)
            scroll.setDrawsBackground_(False)
            scroll.setAutoresizingMask_(A.NSViewWidthSizable | A.NSViewHeightSizable)
            document = Document.alloc().initWithFrame_(A.NSMakeRect(0, 0, 970, 600))
            document.setAutoresizingMask_(A.NSViewWidthSizable)
            scroll.setDocumentView_(document)
            item.setView_(scroll)
            self.tabs.addTabViewItem_(item)
            self.pages[name] = document
        self.status = label('Подключите телевизор. Изменения начнутся после проверки плана.', A.NSMakeRect(28, 24, 760, 44))
        root.addSubview_(self.status)
        self.review_button = self.button(root, 'Проверить план', 'review:', 870, 30, 200)
        self.review_button.setAutoresizingMask_(A.NSViewMinXMargin)
        self.review_button.setKeyEquivalent_('\r')
        self.progress = A.NSProgressIndicator.alloc().initWithFrame_(A.NSMakeRect(28, 72, 1044, 4))
        self.progress.setStyle_(A.NSProgressIndicatorStyleBar)
        self.progress.setIndeterminate_(True)
        self.progress.setDisplayedWhenStopped_(False)
        self.progress.setAutoresizingMask_(A.NSViewWidthSizable)
        root.addSubview_(self.progress)
        self.build_install()
        self.build_apps()
        self.build_cleanup()
        self.build_backups()
        self.sync_controls()

    @objc.python_method
    def button(self, parent, title, action, x, y, width=190, check=False, on=False):
        button = A.NSButton.alloc().initWithFrame_(A.NSMakeRect(x, y, width, 32))
        button.setTitle_(title)
        if check:
            button.setButtonType_(A.NSButtonTypeSwitch)
            button.setState_(A.NSControlStateValueOn if on else A.NSControlStateValueOff)
        else:
            button.setBezelStyle_(A.NSBezelStyleRounded)
        button.setTarget_(self)
        button.setAction_(action)
        parent.addSubview_(button)
        self.controls.append(button)
        return button

    @objc.python_method
    def build_install(self):
        page = self.pages['Установка']
        page.addSubview_(label('Подключите телевизор', A.NSMakeRect(24, 22, 880, 30), 22, True))
        page.addSubview_(label('ПК и ТВ должны быть в одной сети. На ТВ включите отладку ADB и подтвердите запрос доступа.', A.NSMakeRect(24, 64, 880, 42)))
        self.target = A.NSTextField.alloc().initWithFrame_(A.NSMakeRect(24, 116, 470, 28))
        self.target.setPlaceholderString_('IP телевизора, например 192.168.1.100; пусто — USB')
        page.addSubview_(self.target)
        self.controls.append(self.target)
        self.button(page, 'Подключиться', 'connect:', 512, 113, 180)
        self.button(page, 'Как подготовить ТВ?', 'help:', 708, 113, 200)
        self.device_label = label('Устройство не подключено', A.NSMakeRect(24, 160, 440, 44), 14, True)
        page.addSubview_(self.device_label)
        self.button(page, 'Найти ТВ', 'findWifi:', 478, 154, 130)
        self.button(page, 'Сопрячь по коду', 'pairWifi:', 614, 154, 146)
        self.diagnostics_button = self.button(page, 'Диагностика', 'copyDiagnostics:', 770, 154, 138)
        self.install_choice = self.button(page, 'Установить / обновить PeachOS TV с сохранением данных', 'changed:', 24, 220, 850, True, True)
        self.home_choice = self.button(page, 'Назначить PeachOS главным экраном', 'changed:', 24, 266, 850, True)
        self.compat_choice = self.button(page, 'Совместимый Home — если прошивка игнорирует обычный выбор', 'compatible:', 24, 312, 880, True)
        page.addSubview_(label('Совместимый режим использует службу кнопок пульта. Штатный лаунчер сохраняется.', A.NSMakeRect(45, 348, 850, 40)))
        self.airplay_choice = self.button(page, 'Добавить необязательный модуль AirPlay с iPhone', 'changed:', 24, 400, 850, True)
        page.addSubview_(label('Настройки и порядок приложений сохранятся. Очистка включается отдельно во вкладке «Очистка».', A.NSMakeRect(24, 454, 880, 48)))
        self.cancel_button = self.button(page, 'Отменить подключение', 'cancel:', 24, 516, 240)

    @objc.python_method
    def build_apps(self):
        page = self.pages['Приложения']
        page.addSubview_(label('Дополнительные APK', A.NSMakeRect(24, 22, 880, 32), 22, True))
        page.addSubview_(label('Установятся по порядку после лаунчера. Данные сохраняются. Split-наборы .apks / .xapk не поддерживаются.', A.NSMakeRect(24, 68, 880, 44)))
        self.button(page, 'Добавить APK…', 'addApks:', 24, 120)
        self.button(page, 'Убрать выбранный', 'removeApk:', 230, 120)
        self.apk_table = self.table(page, [('file', 'Файл', 280), ('path', 'Путь', 570)], 170, 320, 1)
        self.button(page, 'Другой APK лаунчера…', 'chooseLauncher:', 24, 516, 280)
        self.launcher_label = label('Лаунчер: встроенный PeachOS TV', A.NSMakeRect(24, 558, 880, 36))
        page.addSubview_(self.launcher_label)

    @objc.python_method
    def table(self, parent, columns, y, height, tag):
        scroll = A.NSScrollView.alloc().initWithFrame_(A.NSMakeRect(24, y, 910, height))
        scroll.setHasVerticalScroller_(True)
        scroll.setAutohidesScrollers_(True)
        scroll.setAutoresizingMask_(A.NSViewWidthSizable)
        table = A.NSTableView.alloc().initWithFrame_(scroll.bounds())
        table.setTag_(tag)
        table.setRowHeight_(30)
        table.setUsesAlternatingRowBackgroundColors_(True)
        table.setColumnAutoresizingStyle_(A.NSTableViewLastColumnOnlyAutoresizingStyle)
        for key, title, width in columns:
            column = A.NSTableColumn.alloc().initWithIdentifier_(key)
            column.headerCell().setStringValue_(title)
            column.setWidth_(width)
            column.setEditable_(key == 'check')
            if key == 'check':
                cell = A.NSButtonCell.alloc().init()
                cell.setButtonType_(A.NSButtonTypeSwitch)
                cell.setTitle_('')
                column.setDataCell_(cell)
            table.addTableColumn_(column)
        table.setDataSource_(self)
        table.setDelegate_(self)
        scroll.setDocumentView_(table)
        parent.addSubview_(scroll)
        return table

    @objc.python_method
    def build_cleanup(self):
        page = self.pages['Очистка']
        page.setFrameSize_(A.NSMakeSize(970, 920))
        self.clean_choice = self.button(page, 'Включить очистку — обратимо отключить выбранные пакеты', 'changed:', 24, 18, 880, True)
        page.addSubview_(label('Какие функции сохранить', A.NSMakeRect(24, 64, 880, 26), 15, True))
        self.keep_choices = {}
        for index, (key, title) in enumerate(FEATURES.items()):
            self.keep_choices[key] = self.button(page, title, 'features:', 24 + (index % 2) * 450, 100 + (index // 2) * 36, 440, True, True)
        self.search = A.NSSearchField.alloc().initWithFrame_(A.NSMakeRect(24, 262, 450, 28))
        self.search.setPlaceholderString_('Поиск приложения, пакета или категории')
        self.search.setDelegate_(self)
        page.addSubview_(self.search)
        self.controls.append(self.search)
        self.button(page, 'Рекомендованные', 'recommended:', 492, 259, 220)
        self.button(page, 'Снять всё', 'clear:', 728, 259, 190)
        self.package_table = self.table(page, [('check', '', 40), ('title', 'Приложение / служба', 260), ('category', 'Категория', 230), ('package', 'Пакет Android', 350)], 306, 360, 2)
        self.detail = label('Выберите пакет, чтобы узнать его назначение и ограничения.', A.NSMakeRect(24, 684, 880, 110))
        page.addSubview_(self.detail)
        self.expert_choice = self.button(page, 'Экспертный режим — отключить защиту пакетов', 'expert:', 24, 808, 880, True)
        page.addSubview_(label('Неизвестные и критические пакеты могут нарушить загрузку, сеть и пульт. Копия не заменяет резервную копию прошивки.', A.NSMakeRect(24, 850, 880, 50)))

    @objc.python_method
    def build_backups(self):
        page = self.pages['Копии']
        page.addSubview_(label('Копии и восстановление', A.NSMakeRect(24, 22, 880, 32), 22, True))
        page.addSubview_(label('Перед изменениями сохраняются состояния пакетов и Home. Копия подходит только исходному устройству и пользователю. Она не откатывает версии APK и не заменяет копию прошивки.', A.NSMakeRect(24, 74, 880, 78)))
        self.restore_button = self.button(page, 'Восстановить из копии…', 'restore:', 24, 168, 260)
        self.button(page, 'Открыть копии и журнал', 'openLogs:', 306, 168, 260)
        page.addSubview_(label('Свой набор для следующего устройства', A.NSMakeRect(24, 242, 880, 30), 17, True))
        self.button(page, 'Сохранить выбор…', 'savePreset:', 24, 296, 230)
        self.button(page, 'Загрузить выбор…', 'loadPreset:', 274, 296, 230)
        page.addSubview_(label('При загрузке набора мастер проверит пакеты для текущего устройства. Экспертный режим автоматически не включается.', A.NSMakeRect(24, 346, 880, 48)))
        self.button(page, 'Исходники и лицензия AirPlay', 'airplaySources:', 24, 428, 320)
        theme = A.NSPopUpButton.alloc().initWithFrame_pullsDown_(A.NSMakeRect(24, 500, 320, 30), False)
        theme.addItemsWithTitles_(['Тема macOS', 'Светлая тема', 'Тёмная тема'])
        theme.setTarget_(self)
        theme.setAction_('theme:')
        page.addSubview_(theme)
        theme.selectItemAtIndex_(F.NSUserDefaults.standardUserDefaults().integerForKey_('PeachOSSetupTheme'))
        self.theme_(theme)
        self.reenabled_button = self.button(page, 'Проверить пакеты после обновления…', 'checkReenabled:', 24, 554, 310)
        self.reboot_button = self.button(page, 'Перезапустить ТВ и проверить Home…', 'checkReboot:', 350, 554, 328)

    @objc.python_method
    def sync_controls(self):
        for control in self.controls:
            control.setEnabled_(not self.busy)
        self.review_button.setEnabled_(not self.busy and self.device is not None)
        self.restore_button.setEnabled_(not self.busy and self.device is not None)
        self.diagnostics_button.setEnabled_(not self.busy and self.device is not None)
        self.reenabled_button.setEnabled_(not self.busy and self.device is not None)
        self.reboot_button.setEnabled_(not self.busy and self.device is not None)
        self.airplay_choice.setEnabled_(not self.busy and self.module is not None)
        self.cancel_button.setEnabled_(self.busy and self.connecting)

    @objc.python_method
    def message(self, title, detail, confirm=False):
        alert = A.NSAlert.alloc().init()
        alert.setMessageText_(title)
        alert.setInformativeText_(detail)
        alert.addButtonWithTitle_('Продолжить' if confirm else 'Понятно')
        if confirm:
            alert.addButtonWithTitle_('Отмена')
        return alert.runModal() == A.NSAlertFirstButtonReturn

    @objc.python_method
    def progress_text(self, text):
        AppHelper.callAfter(self.status.setStringValue_, text)

    @objc.python_method
    def work(self, operation, success, connecting=False):
        self.busy, self.connecting = True, connecting
        self.sync_controls()
        self.progress.startAnimation_(None)
        def run():
            # Errors cross the worker/UI boundary once; Cocoa only runs on main.
            try:
                with objc.autorelease_pool():
                    result = operation()
            except Exception as error:
                AppHelper.callAfter(self.finished, None, None, str(error))
            else:
                AppHelper.callAfter(self.finished, success, result, '')
        threading.Thread(target=run, daemon=True).start()

    @objc.python_method
    def finished(self, success, result, error):
        self.busy = self.connecting = False
        self.progress.stopAnimation_(None)
        if error:
            self.device = self.adb = None
            self.rules = {}
            self.selected.clear()
            self.visible_packages = []
            self.package_table.reloadData()
            self.status.setStringValue_('Операция остановлена. Подключитесь заново. Журнал сохранён.')
            self.message('Операция не завершена', error + '\n\nКопии и журнал: ' + str(DATA))
        else:
            success(result)
        self.sync_controls()

    def findWifi_(self, sender):
        self.work(lambda: wifi_services(Adb(cache_adb(ASSETS, DATA, 'adb'))), self.show_wifi)

    @objc.python_method
    def show_wifi(self, endpoints):
        if not endpoints:
            self.message('Поиск телевизора', 'Адреса отладки по Wi-Fi не найдены. Включите отладку на ТВ или введите адрес вручную.')
            return
        alert = A.NSAlert.alloc().init()
        alert.setMessageText_('Выберите устройство')
        choices = A.NSPopUpButton.alloc().initWithFrame_pullsDown_(A.NSMakeRect(0, 0, 360, 30), False)
        choices.addItemsWithTitles_(endpoints)
        alert.setAccessoryView_(choices)
        alert.addButtonWithTitle_('Использовать адрес')
        alert.addButtonWithTitle_('Отмена')
        if alert.runModal() == A.NSAlertFirstButtonReturn:
            self.target.setStringValue_(choices.titleOfSelectedItem())

    def pairWifi_(self, sender):
        alert = A.NSAlert.alloc().init()
        alert.setMessageText_('Сопряжение по Wi-Fi')
        alert.setInformativeText_('На ТВ откройте «Отладка по Wi-Fi» → «Сопряжение по коду».')
        view = A.NSView.alloc().initWithFrame_(A.NSMakeRect(0, 0, 455, 106))
        fields = []
        for index, (title, initial) in enumerate((
                ('IP:порт из окна с кодом', ''),
                ('IP:порт с главного экрана отладки', str(self.target.stringValue())),
                ('Шестизначный код', ''))):
            y = 72 - index * 34
            view.addSubview_(label(title, A.NSMakeRect(0, y, 232, 26)))
            field_type = A.NSSecureTextField if index == 2 else A.NSTextField
            field = field_type.alloc().initWithFrame_(A.NSMakeRect(242, y + 2, 212, 24))
            field.setStringValue_(initial)
            view.addSubview_(field)
            fields.append(field)
        alert.setAccessoryView_(view)
        alert.addButtonWithTitle_('Сопрячь')
        alert.addButtonWithTitle_('Отмена')
        alert.window().setInitialFirstResponder_(fields[0])
        if alert.runModal() == A.NSAlertFirstButtonReturn:
            pairing = tuple(str(field.stringValue()).strip() for field in fields)
            self.target.setStringValue_(pairing[1])
            self.connect_device(pairing)

    def copyDiagnostics_(self, sender):
        if not self.device:
            return
        adb, device = self.adb, self.device
        def copy(report):
            pasteboard = A.NSPasteboard.generalPasteboard()
            pasteboard.clearContents()
            pasteboard.setString_forType_(report, A.NSPasteboardTypeString)
            self.message('Диагностика скопирована', report)
        self.work(lambda: diagnostic_report(adb, device, VERSION), copy)

    def checkReboot_(self, sender):
        if not self.device or not self.message('Перезапустить телевизор?', 'Открытые приложения закроются. После загрузки мастер проверит переход на главный экран. Оставьте ADB включённым.', True):
            return
        adb, device = self.adb, self.device
        self.cancelled.clear()
        self.work(lambda: check_home_after_reboot(adb, device, self.cancelled, self.progress_text),
                  lambda result: self.message('Проверка Home', result), connecting=True)

    def checkReenabled_(self, sender):
        if not self.device:
            return
        panel = A.NSOpenPanel.openPanel()
        panel.setAllowedFileTypes_(['json'])
        if panel.runModal() != A.NSModalResponseOK:
            return
        path = Path(str(panel.URL().path()))
        adb, device = self.adb, self.device
        keep = {key for key, choice in self.keep_choices.items() if choice.state()}
        def show(result):
            current, selected, protected_now = result
            preserved = '\nСохранены текущими правилами: ' + ', '.join(sorted(protected_now)) if protected_now else ''
            if not selected:
                self.message('Проверка пакетов', 'Снова включённых пакетов для обычной очистки нет.' + preserved)
                return
            if not self.message('Проверка пакетов', f'Доступно для повторной очистки: {len(selected)}.' + preserved
                                + '\nПеренести доступные пакеты в выбор для проверки плана?', True):
                return
            self.device = current
            _, self.rules = candidates(current.props, current.installed, current.user_apps, current.home.split('/')[0])
            self.expert_choice.setState_(0)
            self.selected = selected
            self.clean_choice.setState_(1)
            self.refresh_packages()
            self.status.setStringValue_('Пакеты отмечены во вкладке «Очистка». Проверьте план перед применением.')
        self.work(lambda: packages_reenabled(adb, device, path, keep), show)

    def connect_(self, sender):
        self.connect_device()

    @objc.python_method
    def connect_device(self, pairing=None):
        if self.busy:
            return
        self.device = self.adb = None
        self.cancelled.clear()
        target = str(self.target.stringValue()).strip()
        self.status.setStringValue_('Подключение… Подтвердите отладку на телевизоре. Мастер дождётся ответа.')
        def operation():
            adb = Adb(cache_adb(ASSETS, DATA, 'adb'), log=DATA / 'adb-log.jsonl')
            serial = (pair_wifi(adb, *pairing, self.cancelled, self.progress_text) if pairing
                      else wait_for_device(adb, target, self.cancelled, self.progress_text))
            device = discover(adb, serial)
            if self.cancelled.is_set():
                raise InterruptedError('Подключение отменено')
            return adb, device
        self.work(operation, self.connected, True)

    @objc.python_method
    def connected(self, result):
        self.adb, self.device = result
        d = self.device
        _, self.rules = candidates(d.props, d.installed, d.user_apps, d.home.split('/')[0])
        for package in d.protected_packages:
            self.rules.pop(package, None)
        self.selected.clear()
        self.device_label.setStringValue_(' '.join(filter(None, (d.props.get('ro.product.manufacturer'), d.props.get('ro.product.model'), '· Android', d.props.get('ro.build.version.release')))))
        self.home_choice.setState_(A.NSControlStateValueOff if PEACH.split('/')[0] in d.installed else A.NSControlStateValueOn)
        self.compat_choice.setState_(A.NSControlStateValueOff)
        self.refresh_packages()
        self.status.setStringValue_('Подключено. Выберите операции и проверьте план.')

    def cancel_(self, sender):
        self.cancelled.set()

    def changed_(self, sender):
        if not self.home_choice.state():
            self.compat_choice.setState_(0)

    def compatible_(self, sender):
        if sender.state():
            self.home_choice.setState_(1)

    @objc.python_method
    def blocked(self, package):
        if not self.device:
            return 'Сначала подключите телевизор.'
        if self.expert_choice.state():
            return ''
        rule = self.rules.get(package)
        if not rule or protected(package, self.device.home.split('/')[0]) or package in self.device.protected_packages:
            return 'Защищённый или неизвестный пакет. Недоступен в обычном режиме.'
        if any(self.keep_choices[key].state() for key in rule.features):
            return 'Пакет нужен сохраняемой функции.'
        return ''

    @objc.python_method
    def refresh_packages(self):
        self.selected = {p for p in self.selected if not self.blocked(p)}
        query = str(self.search.stringValue()).casefold()
        self.visible_packages = [p for p in sorted(self.device.installed) if query in (p + ' ' + (self.rules[p].title + ' ' + self.rules[p].category if p in self.rules else '')).casefold()] if self.device else []
        self.package_table.reloadData()

    def controlTextDidChange_(self, notification):
        if notification.object() is self.search:
            self.refresh_packages()

    def features_(self, sender):
        self.refresh_packages()

    def recommended_(self, sender):
        self.selected = {p for p, rule in self.rules.items() if rule.suggested and not self.blocked(p)}
        self.package_table.reloadData()

    def clear_(self, sender):
        self.selected.clear()
        self.package_table.reloadData()

    def expert_(self, sender):
        if sender.state() and not self.message('Экспертный режим', 'Можно отключить критические службы. Возможны потеря сети, ADB и окирпичивание устройства. Восстановление из копии не гарантировано. Включить?', True):
            sender.setState_(0)
        self.refresh_packages()

    def numberOfRowsInTableView_(self, table):
        return len(self.extras) if table.tag() == 1 else len(self.visible_packages)

    def tableView_objectValueForTableColumn_row_(self, table, column, row):
        key = str(column.identifier())
        if table.tag() == 1:
            file = self.extras[row]
            return file.name if key == 'file' else str(file)
        package = self.visible_packages[row]
        rule = self.rules.get(package)
        if key == 'check':
            return package in self.selected
        if key == 'package':
            return package
        if key == 'title':
            return rule.title if rule else package
        return rule.category if rule else 'Требуется проверка'

    def tableView_setObjectValue_forTableColumn_row_(self, table, value, column, row):
        if self.busy:
            return
        package = self.visible_packages[row]
        reason = self.blocked(package)
        if reason:
            self.detail.setStringValue_(reason)
            table.reloadData()
        elif value:
            self.selected.add(package)
        else:
            self.selected.discard(package)

    def tableViewSelectionDidChange_(self, notification):
        table = notification.object()
        row = table.selectedRow()
        if table.tag() != 2 or row < 0:
            return
        package = self.visible_packages[row]
        rule = self.rules.get(package)
        self.detail.setStringValue_(package + '\n' + (rule.impact if rule else 'Назначение не подтверждено базой.') + '\n' + self.blocked(package))

    def addApks_(self, sender):
        panel = A.NSOpenPanel.openPanel()
        panel.setAllowedFileTypes_(['apk'])
        panel.setAllowsMultipleSelection_(True)
        if panel.runModal() == A.NSModalResponseOK:
            self.extras = list(dict.fromkeys(self.extras + [Path(str(url.path())) for url in panel.URLs()]))
            self.apk_table.reloadData()

    def chooseLauncher_(self, sender):
        panel = A.NSOpenPanel.openPanel()
        panel.setAllowedFileTypes_(['apk'])
        if panel.runModal() == A.NSModalResponseOK:
            self.launcher_apk = Path(str(panel.URL().path()))
            self.launcher_label.setStringValue_('Лаунчер: ' + self.launcher_apk.name)

    def removeApk_(self, sender):
        row = self.apk_table.selectedRow()
        if row >= 0:
            self.extras.pop(row)
            self.apk_table.reloadData()

    def review_(self, sender):
        if self.busy or not self.device:
            return
        module = self.module.file if self.airplay_choice.state() and self.module else None
        if module:
            reason = airplay_compatibility(self.module, self.device.props)
            if reason:
                self.message('AirPlay недоступен', reason)
                return
        plan = Plan(self.adb, self.device, tuple(sorted(self.selected)) if self.clean_choice.state() else (),
                    frozenset(key for key, choice in self.keep_choices.items() if choice.state()),
                    self.launcher_apk if self.install_choice.state() else None,
                    bool(self.home_choice.state()), bool(self.compat_choice.state()), tuple(self.extras), bool(self.expert_choice.state()), module)
        if not (plan.apk or plan.home or plan.extras or plan.selected or plan.airplay):
            self.message('План пуст', 'Выберите хотя бы одну операцию.')
            return
        summary = [str(self.device_label.stringValue()), '',
                   'Лаунчер: ' + ('установить / обновить с сохранением данных' if plan.apk else 'не менять'),
                   'Home: ' + ('назначить PeachOS' if plan.home else 'не менять'),
                   'Совместимый Home: ' + ('включить при необходимости' if plan.compatible else 'не включать'),
                   'AirPlay: ' + ('установить модуль' if plan.airplay else 'не менять'),
                   f'Дополнительных APK: {len(plan.extras)}', *[str(file) for file in plan.extras],
                   f'Отключить пакетов: {len(plan.selected)}',
                   *[package + ' — ' + (self.rules[package].impact if package in self.rules else 'Неизвестный пакет, риск нарушения работы ТВ') for package in plan.selected],
                   '', 'Перед изменениями сохраняется копия. Данные приложений не удаляются. Копия не откатывает версии APK.',
                   'Копии: ' + str(DATA)]
        if plan.expert and plan.selected:
            summary.append('ЭКСПЕРТНЫЙ РЕЖИМ: возможна потеря загрузки и ADB.')
        alert = A.NSAlert.alloc().init()
        alert.setMessageText_('Проверьте план изменений')
        alert.setInformativeText_('Изменения выполнятся только после нажатия «Применить».')
        scroll = A.NSScrollView.alloc().initWithFrame_(A.NSMakeRect(0, 0, 640, 340))
        scroll.setHasVerticalScroller_(True)
        text = A.NSTextView.alloc().initWithFrame_(A.NSMakeRect(0, 0, 620, 340))
        text.setString_('\n'.join(summary))
        text.setEditable_(False)
        text.setFont_(A.NSFont.systemFontOfSize_(13))
        text.setVerticallyResizable_(True)
        text.setHorizontallyResizable_(False)
        text.textContainer().setWidthTracksTextView_(True)
        scroll.setDocumentView_(text)
        alert.setAccessoryView_(scroll)
        alert.addButtonWithTitle_('Применить')
        alert.addButtonWithTitle_('Отмена')
        if alert.runModal() != A.NSAlertFirstButtonReturn:
            return
        self.work(lambda: apply_plan(plan.adb, plan.device, plan.selected, plan.keep, plan.apk, plan.home, DATA / 'backups',
                                    self.progress_text, extra_apks=plan.extras, expert=plan.expert,
                                    airplay_apk=plan.airplay, allow_home_redirect=plan.compatible), self.applied)

    @objc.python_method
    def applied(self, result):
        self.status.setStringValue_('Готово. Проверьте Home, пульт и приложения на ТВ. Копия: ' + str(result))
        # Reconnect before the next plan: package and Home state have changed.
        self.device = self.adb = None
        self.selected.clear()
        self.visible_packages = []
        self.package_table.reloadData()

    def restore_(self, sender):
        if not self.device or self.busy:
            return
        panel = A.NSOpenPanel.openPanel()
        panel.setAllowedFileTypes_(['json'])
        if panel.runModal() != A.NSModalResponseOK:
            return
        path = Path(str(panel.URL().path()))
        if self.message('Восстановить исходное состояние?', 'Восстановятся состояния пакетов и Home из выбранной копии. Версии APK не изменятся.\n' + str(path), True):
            adb, device = self.adb, self.device
            self.work(lambda: restore(adb, device, path, self.progress_text), self.applied)

    def openLogs_(self, sender):
        DATA.mkdir(parents=True, exist_ok=True)
        A.NSWorkspace.sharedWorkspace().openURL_(F.NSURL.fileURLWithPath_(str(DATA)))

    def airplaySources_(self, sender):
        import shutil
        destination = DATA / 'AirPlay'
        destination.mkdir(parents=True, exist_ok=True)
        for name in ('PeachOS-AirPlay-source.zip', 'LICENSE-AirPlay.txt'):
            source = ASSETS / name
            if source.exists():
                shutil.copyfile(source, destination / name)
        A.NSWorkspace.sharedWorkspace().openURL_(F.NSURL.fileURLWithPath_(str(destination)))

    def savePreset_(self, sender):
        panel = A.NSSavePanel.savePanel()
        panel.setNameFieldStringValue_('PeachOS-choice.json')
        if panel.runModal() == A.NSModalResponseOK:
            data = {'version': 1, 'packages': sorted(self.selected), 'keep': {key: bool(choice.state()) for key, choice in self.keep_choices.items()}}
            Path(str(panel.URL().path())).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

    def loadPreset_(self, sender):
        if not self.device:
            self.message('Подключите телевизор', 'Выбор пакетов проверяется для конкретного устройства.')
            return
        panel = A.NSOpenPanel.openPanel()
        panel.setAllowedFileTypes_(['json'])
        if panel.runModal() == A.NSModalResponseOK:
            try:
                preset = read_preset(Path(str(panel.URL().path())))
            except (OSError, ValueError) as error:
                self.message('Не удалось загрузить набор', str(error))
                return
            for key, value in preset['keep'].items():
                self.keep_choices[key].setState_(1 if value else 0)
            self.selected = {p for p in preset['packages'] if p in self.device.installed and not self.blocked(p)}
            self.refresh_packages()

    def theme_(self, sender):
        names = [None, A.NSAppearanceNameAqua, A.NSAppearanceNameDarkAqua]
        index = sender.indexOfSelectedItem()
        name = names[index]
        F.NSUserDefaults.standardUserDefaults().setInteger_forKey_(index, 'PeachOSSetupTheme')
        self.window.setAppearance_(A.NSAppearance.appearanceNamed_(name) if name else None)

    def help_(self, sender):
        self.message('Подготовка телевизора', '1. Настройки ТВ → Система → Об устройстве → нажмите «Сборка» семь раз.\n\n2. Для разработчиков → включите отладку USB / ADB / по сети.\n\n3. Узнайте IP в настройках сети. ПК и ТВ должны быть в одной сети.\n\n4. Нажмите «Подключиться» и подтвердите запрос отладки на ТВ. Мастер будет ждать.\n\nПри USB на Mac драйвер обычно не требуется. Для Wi-Fi pairing по коду используйте предварительно сопряжённый ADB.')

    def windowShouldClose_(self, sender):
        if self.busy:
            self.message('Операция выполняется', 'Дождитесь завершения. Для подключения можно нажать «Отменить подключение».')
            return False
        return True

    def applicationShouldTerminate_(self, app):
        if self.busy:
            self.message('Операция выполняется', 'Дождитесь завершения операции перед выходом.')
            return A.NSTerminateCancel
        return A.NSTerminateNow

    def applicationShouldTerminateAfterLastWindowClosed_(self, app):
        return True


def main():
    app = A.NSApplication.sharedApplication()
    app.setActivationPolicy_(A.NSApplicationActivationPolicyRegular)
    menu = A.NSMenu.alloc().init()
    item = A.NSMenuItem.alloc().init()
    menu.addItem_(item)
    submenu = A.NSMenu.alloc().init()
    submenu.addItemWithTitle_action_keyEquivalent_('Завершить PeachOS TV', 'terminate:', 'q')
    item.setSubmenu_(submenu)
    edit_item = A.NSMenuItem.alloc().init()
    edit_menu = A.NSMenu.alloc().initWithTitle_('Правка')
    for title, action, key in [('Вырезать', 'cut:', 'x'), ('Копировать', 'copy:', 'c'), ('Вставить', 'paste:', 'v'), ('Выбрать всё', 'selectAll:', 'a')]:
        edit_menu.addItemWithTitle_action_keyEquivalent_(title, action, key)
    edit_item.setSubmenu_(edit_menu)
    menu.addItem_(edit_item)
    app.setMainMenu_(menu)
    controller = Installer.alloc().init()
    controller.connecting = False
    controller.build()
    app.setDelegate_(controller)
    if len(sys.argv) == 3 and sys.argv[1] == '--self-test':
        adb = cache_adb(ASSETS, DATA, 'adb')
        apk = ASSETS / 'PeachOS-TV.apk'
        report = {'installer_version': VERSION, 'ui_backend': 'AppKit', 'tk_loaded': 'tkinter' in sys.modules,
                  'native_scroll_views': 4, 'tabs': controller.tabs.numberOfTabViewItems(),
                  'apk': apk.exists(), 'airplay_bundled': controller.module is not None, 'simple_default': True,
                  'adb_version': Adb(adb).run('version', target=False),
                  'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'reference_packages': len(REFERENCE['packages'])}
        Path(sys.argv[2]).write_text(json.dumps(report), encoding='utf-8')
        app.finishLaunching()
        controller.window.makeKeyAndOrderFront_(None)
        app.activateIgnoringOtherApps_(True)
        F.NSRunLoop.currentRunLoop().runUntilDate_(F.NSDate.dateWithTimeIntervalSinceNow_(0.2))
        controller.window.displayIfNeeded()
        view = controller.window.contentView()
        bitmap = view.bitmapImageRepForCachingDisplayInRect_(view.bounds())
        view.cacheDisplayInRect_toBitmapImageRep_(view.bounds(), bitmap)
        bitmap.representationUsingType_properties_(A.NSBitmapImageFileTypePNG, {}).writeToFile_atomically_(str(Path(sys.argv[2]).with_suffix('.png')), True)
        # Capture Cocoa's composited controls as well as ordinary NSView drawing.
        # The runner contains only this fixture window, without connected TVs.
        subprocess.run(['/usr/sbin/screencapture', '-x', '-l', str(controller.window.windowNumber()),
                        str(Path(sys.argv[2]).with_suffix('.png'))], capture_output=True, timeout=15)
        controller.window.orderOut_(None)
    else:
        controller.window.makeKeyAndOrderFront_(None)
        app.activateIgnoringOtherApps_(True)
        AppHelper.runEventLoop()


if __name__ == '__main__':
    main()
