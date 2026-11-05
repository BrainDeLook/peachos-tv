"""Standalone Windows setup UI. Workers never touch Tk; all device changes require a reviewed plan."""
import json
import hashlib
import os
import queue
import shutil
import sys
import threading
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from tkinter.scrolledtext import ScrolledText

from ui import BACKGROUND, configure_styles, apply_theme, card, paragraph, ScrollPage

from catalog import FEATURES, PROFILES, RULES, REFERENCE, candidates
from core import Adb, discover, wait_for_device, apply_plan, restore, read_preset, inspect_apk, airplay_compatibility, AIRPLAY

BASE = Path(sys.executable).parent if getattr(sys, 'frozen', False) else Path(__file__).parent
ASSETS = Path(sys._MEIPASS) if getattr(sys, 'frozen', False) else BASE
DATA = Path(os.environ.get('PEACHOS_SETUP_DATA', str(BASE / 'PeachOS-TV-data')))
BUNDLED_APK_LABEL = 'Встроенный PeachOS TV'
SETUP_VERSION = '1.5.13 preview 2'


def read_ui_settings(path):
    """Preferences are untrusted input; expose only a validated theme boolean."""
    try:
        if path.stat().st_size > 4096:
            return False
        value = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError, RecursionError):
        return False
    if not isinstance(value, dict) or type(value.get('version')) is not int or value['version'] != 1 or type(value.get('dark')) is not bool:
        return False
    return value['dark']


def write_ui_settings(path, dark):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    try:
        temporary.write_text(json.dumps({'version': 1, 'dark': dark}), encoding='utf-8')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def portable_adb():
    # ADB's server can outlive the GUI. Run it from the persistent portable cache,
    # so it does not lock the one-file runtime's temporary extraction directory.
    target = DATA / 'platform-tools'
    target.mkdir(parents=True, exist_ok=True)
    for name in ('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt'):
        source, destination = ASSETS / 'platform-tools' / name, target / name
        if destination.exists():
            with source.open('rb') as original, destination.open('rb') as cached:
                if hashlib.file_digest(original, 'sha256').digest() == hashlib.file_digest(cached, 'sha256').digest():
                    continue
        shutil.copyfile(source, destination)
    return target / 'adb.exe'


class Wizard:
    def __init__(self, root):
        self.root, self.events = root, queue.Queue()
        self.device = self.adb = None
        self.rows, self.selected, self.profiles = {}, set(), ()
        self.extra_apks = []
        self.advanced = False
        self.busy = False
        self.wait_window = None
        self.cancel_connect = threading.Event()
        self.frozen_controls = {}
        root.title('PeachOS TV · Установщик ' + SETUP_VERSION)
        root.geometry(f'{min(1140, root.winfo_screenwidth() - 60)}x{min(860, root.winfo_screenheight() - 70)}')
        root.minsize(min(940, root.winfo_screenwidth() - 60), min(660, root.winfo_screenheight() - 70))
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.configure(background=BACKGROUND)
        self.dark_mode = tk.BooleanVar(value=read_ui_settings(DATA / 'ui-settings.json'))
        configure_styles(root, self.dark_mode.get())
        if (ASSETS / 'peachos.ico').exists():
            root.iconbitmap(str(ASSETS / 'peachos.ico'))
        self.frame = ttk.Frame(root, padding=(26, 20))
        self.frame.pack(fill='both', expand=True)
        header = ttk.Frame(self.frame)
        header.pack(fill='x', pady=(0, 20))
        if (ASSETS / 'peachos.png').exists():
            self.brand = tk.PhotoImage(file=str(ASSETS / 'peachos.png'))
            self.brand = self.brand.subsample(max(1, (self.brand.width() + 43) // 44))
            ttk.Label(header, image=self.brand).pack(side='left', padx=(0, 12))
        title = ttk.Frame(header)
        title.pack(side='left')
        brand_line = ttk.Frame(title)
        brand_line.pack(anchor='w')
        ttk.Label(brand_line, text='PeachOS TV', style='Title.TLabel').pack(side='left')
        ttk.Label(brand_line, text='  ' + SETUP_VERSION, style='Version.TLabel').pack(side='left', pady=(6, 0))
        ttk.Label(title, text='Установка и обновление для Android TV', style='Muted.TLabel').pack(anchor='w')
        self.mode_button = ttk.Button(header, text='Расширенный мастер', command=self.switch_mode)
        self.mode_button.pack(side='right')
        self.theme_button = ttk.Checkbutton(header, text='Тёмная тема', variable=self.dark_mode,
                                           style='Plain.TCheckbutton', command=self.change_theme)
        self.theme_button.pack(side='right', padx=(10, 16))
        self.status = tk.StringVar(value='Подключите устройство, выберите параметры и проверьте план.')
        footer = ttk.Frame(self.frame)
        footer.pack(side='bottom', fill='x', pady=(16, 0))
        self.operation_progress = ttk.Progressbar(footer, mode='indeterminate')
        self.plan_button = ttk.Button(footer, text='Проверить план  →', style='Accent.TButton', command=self.review, state='disabled')
        self.plan_button.pack(side='right', padx=(18, 0))
        paragraph(footer, textvariable=self.status, style='Muted.TLabel').pack(side='left', fill='x', expand=True)

        self.target = tk.StringVar()
        self.device_text = tk.StringVar(value='Устройство пока не подключено')
        bundled_adb = ASSETS / 'platform-tools' / 'adb.exe'
        self.adb_path = str(bundled_adb) if bundled_adb.exists() else shutil.which('adb') or ''
        self.install = tk.BooleanVar(value=True)
        self.clean = tk.BooleanVar(value=False)
        self.home = tk.BooleanVar(value=False)
        self.compat_home = tk.BooleanVar(value=False)
        self.expert = tk.BooleanVar(value=False)
        self.embedded_apk = ASSETS / 'PeachOS-TV.apk'
        self.airplay = tk.BooleanVar(value=False)
        airplay_file = ASSETS / 'PeachOS-AirPlay.apk'
        self.airplay_module = inspect_apk(airplay_file) if airplay_file.is_file() else None
        self.airplay_hint = tk.StringVar(value='Трансляция с iPhone. Без модуля в панели остаётся поиск.'
                                        if self.airplay_module else 'Модуль не включён в этот установщик. В панели остаётся поиск.')
        self.airplay_choices = []
        self.apk = tk.StringVar(value=BUNDLED_APK_LABEL if self.embedded_apk.exists() else str(BASE / 'PeachOS-TV.apk'))
        self.simple_home = tk.BooleanVar(value=True)
        self.simple_cleanup = tk.BooleanVar(value=False)
        self.simple_hint = tk.StringVar(value='При обновлении сохранятся настройки и порядок приложений.')

        self.simple_container = ScrollPage(self.frame)
        self.simple_container.pack(fill='both', expand=True)
        simple = self.simple_container.body
        ttk.Label(simple, text='Начнём с вашего телевизора', style='Heading.TLabel').pack(anchor='w', pady=(0, 6))
        paragraph(simple, 'Три понятных шага. Изменения начнутся только после вашего подтверждения.',
                  style='Muted.TLabel').pack(fill='x', pady=(0, 16))
        self.simple_stage = tk.StringVar(value='1  Подключение     →     2  Параметры     →     3  Проверка плана')
        ttk.Label(simple, textvariable=self.simple_stage, style='Version.TLabel').pack(anchor='w', pady=(0, 14))
        connection_card = card(simple)
        connection_card.pack(fill='x', pady=(0, 12))
        ttk.Label(connection_card, text='1. Подключение', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 5))
        paragraph(connection_card, 'ПК и телевизор должны быть в одной сети. Включите отладку и введите IP-адрес телевизора.').pack(fill='x', pady=(0, 12))
        simple_connection = ttk.Frame(connection_card, style='Card.TFrame')
        simple_connection.pack(fill='x')
        entry = ttk.Entry(simple_connection, textvariable=self.target, width=28)
        entry.pack(side='left', padx=(0, 10))
        entry.bind('<Return>', lambda event: self.connect())
        self.simple_connect_button = ttk.Button(simple_connection, text='Подключиться', style='Accent.TButton', command=self.connect)
        self.simple_connect_button.pack(side='left')
        ttk.Button(simple_connection, text='Как подготовить ТВ?', style='Small.TButton', command=self.preparation).pack(side='right')
        paragraph(connection_card, 'Например: 192.168.1.199. При первом подключении подтвердите запрос на экране ТВ.',
                  style='CardSmall.TLabel').pack(fill='x', pady=(9, 0))
        paragraph(connection_card, textvariable=self.device_text, style='Card.TLabel').pack(fill='x', pady=(12, 0))
        options = card(simple)
        options.pack(fill='x', pady=(0, 12))
        ttk.Label(options, text='2. Параметры установки', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 5))
        paragraph(options, textvariable=self.simple_hint).pack(fill='x', pady=(0, 7))
        self.simple_home_choice = ttk.Checkbutton(options, text='Настроить открытие PeachOS по кнопке Home', variable=self.simple_home,
                                                command=lambda: self.compat_home.set(False) if not self.simple_home.get() else None)
        self.simple_home_choice.pack(anchor='w')
        ttk.Checkbutton(options, text='Совместимый Home через службу пульта', variable=self.compat_home,
                        command=self.select_compatible_home).pack(anchor='w')
        paragraph(options, 'Если обычное назначение не работает: разрешить службу пульта и автозапуск PeachOS, если прошивка его поддерживает. Системные приложения сохранятся.', style='CardSmall.TLabel').pack(fill='x', pady=(0, 6))
        ttk.Checkbutton(options, text='Лёгкая очистка рекомендованных пакетов', variable=self.simple_cleanup).pack(anchor='w')
        airplay_choice = ttk.Checkbutton(options, text='Добавить AirPlay с iPhone · необязательный прототип', variable=self.airplay,
                                        state='normal' if self.airplay_module else 'disabled')
        airplay_choice.pack(anchor='w')
        self.airplay_choices.append(airplay_choice)
        paragraph(options, textvariable=self.airplay_hint, style='CardSmall.TLabel').pack(fill='x', pady=(2, 0))
        paragraph(simple, 'Голосовой поиск, трансляция, обновления и эфирные каналы сохраняются. Для своих APK и выбора отдельных пакетов откройте расширенный мастер.',
                  style='Muted.TLabel').pack(fill='x', pady=(0, 10))

        self.advanced_container = ttk.Frame(self.frame)
        navigation = ttk.Frame(self.advanced_container, width=180)
        navigation.pack(side='left', fill='y', padx=(0, 20))
        navigation.pack_propagate(False)
        self.tabs = ttk.Notebook(self.advanced_container)
        self.tabs.pack(side='left', fill='both', expand=True)
        pages = [ScrollPage(self.tabs) for _ in range(4)]
        connection, apps, cleanup, recovery = [page.body for page in pages]
        self.nav_buttons = []
        for index, (page, label) in enumerate(zip(pages, ('Устройство', 'Приложения', 'Очистка', 'Копии'))):
            self.tabs.add(page, text=label)
            button = ttk.Button(navigation, text=label, width=15,
                                style='Active.Nav.TButton' if index == 0 else 'Nav.TButton',
                                command=lambda i=index: self.tabs.select(i))
            button.pack(fill='x', pady=(0, 5))
            self.nav_buttons.append(button)
        paragraph(navigation, 'Все изменения — только после проверки плана.', style='Muted.TLabel').pack(fill='x', pady=(20, 0))
        def navigation_changed(event):
            for index, button in enumerate(self.nav_buttons):
                button.configure(style='Active.Nav.TButton' if index == self.tabs.index('current') else 'Nav.TButton')
        self.tabs.bind('<<NotebookTabChanged>>', navigation_changed)

        ttk.Label(connection, text='Устройство', style='Heading.TLabel').pack(anchor='w', pady=(0, 14))
        device_card = card(connection)
        device_card.pack(fill='x', pady=(0, 12))
        ttk.Label(device_card, text='Подключение к телевизору', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 6))
        paragraph(device_card, 'Укажите IP-адрес или USB-серийный номер. Для единственного USB-устройства оставьте поле пустым.').pack(fill='x', pady=(0, 12))
        row = ttk.Frame(device_card, style='Card.TFrame')
        row.pack(fill='x')
        entry = ttk.Entry(row, textvariable=self.target, width=28)
        entry.pack(side='left', padx=(0, 10))
        entry.bind('<Return>', lambda event: self.connect())
        self.connect_button = ttk.Button(row, text='Подключиться', style='Accent.TButton', command=self.connect)
        self.connect_button.pack(side='left')
        paragraph(device_card, 'IP без порта подключается через 5555. Запрос отладки нужно принять на экране ТВ.', style='CardSmall.TLabel').pack(fill='x', pady=(10, 0))
        paragraph(device_card, textvariable=self.device_text, style='Card.TLabel').pack(fill='x', pady=(14, 0))
        help_card = card(connection)
        help_card.pack(fill='x')
        ttk.Label(help_card, text='Если подключение не получилось', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 7))
        paragraph(help_card, 'Проверьте, что включена отладка, адрес не изменился, а ПК и телевизор видят друг друга в локальной сети. Для сопряжения по коду используйте adb pair отдельно.').pack(fill='x', pady=(0, 12))
        actions = ttk.Frame(help_card, style='Card.TFrame')
        actions.pack(fill='x')
        ttk.Button(actions, text='Подготовка телевизора', style='Small.TButton', command=self.preparation).pack(side='left')
        ttk.Button(actions, text='Выбрать другой adb.exe…', style='Small.TButton', command=self.choose_adb).pack(side='left', padx=(8, 0))

        ttk.Label(apps, text='Приложения', style='Heading.TLabel').pack(anchor='w', pady=(0, 14))
        launcher_card = card(apps)
        launcher_card.pack(fill='x', pady=(0, 12))
        title_row = ttk.Frame(launcher_card, style='Card.TFrame')
        title_row.pack(fill='x', pady=(0, 8))
        ttk.Label(title_row, text='PeachOS TV', style='CardTitle.TLabel').pack(side='left')
        self.update_button = ttk.Button(title_row, text='Только обновить', style='Small.TButton', command=lambda: self.review(update_only=True), state='disabled')
        self.update_button.pack(side='right')
        ttk.Checkbutton(launcher_card, text='Установить или обновить лаунчер', variable=self.install).pack(anchor='w')
        paragraph(launcher_card, textvariable=self.apk, style='CardSmall.TLabel').pack(fill='x', pady=(0, 8))
        apk_row = ttk.Frame(launcher_card, style='Card.TFrame')
        apk_row.pack(fill='x', pady=(0, 8))
        ttk.Button(apk_row, text='Другой APK…', style='Small.TButton', command=self.choose_apk).pack(side='left')
        ttk.Button(apk_row, text='Использовать встроенный', style='Small.TButton', command=lambda: self.apk.set(BUNDLED_APK_LABEL) if not self.busy else None).pack(side='left', padx=8)
        ttk.Checkbutton(launcher_card, text='Назначить PeachOS главным экраном', variable=self.home,
                        command=lambda: self.compat_home.set(False) if not self.home.get() else None).pack(anchor='w')
        ttk.Checkbutton(launcher_card, text='Совместимый Home через службу пульта', variable=self.compat_home,
                        command=self.select_compatible_home).pack(anchor='w')
        paragraph(launcher_card, 'Сначала штатное назначение Android. При отказе — отдельная настройка Home и разрешение службы пульта с автозапуском, если он поддерживается. Без отключения штатного Home и сетевых служб.', style='CardSmall.TLabel').pack(fill='x')
        module_card = card(apps)
        module_card.pack(fill='x', pady=(0, 12))
        ttk.Label(module_card, text='Дополнительный модуль', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 6))
        airplay_choice = ttk.Checkbutton(module_card, text='Установить AirPlay для iPhone · прототип', variable=self.airplay,
                                        state='normal' if self.airplay_module else 'disabled')
        airplay_choice.pack(anchor='w')
        self.airplay_choices.append(airplay_choice)
        paragraph(module_card, textvariable=self.airplay_hint).pack(fill='x')
        if self.airplay_module:
            ttk.Button(module_card, text='Лицензия и исходники AirPlay', style='Small.TButton', command=self.open_airplay_sources).pack(anchor='w', pady=(10, 0))
        apks_card = card(apps)
        apks_card.pack(fill='x')
        ttk.Label(apks_card, text='Ваши APK с компьютера', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 7))
        paragraph(apks_card, 'Приложения установятся по порядку с сохранением данных. Поддерживаются обычные .apk, без split-наборов .apks / .xapk.', style='CardSmall.TLabel').pack(fill='x', pady=(0, 10))
        apk_controls = ttk.Frame(apks_card, style='Card.TFrame')
        apk_controls.pack(fill='x', pady=(0, 10))
        ttk.Button(apk_controls, text='Добавить APK…', style='Small.TButton', command=self.add_apks).pack(side='left')
        ttk.Button(apk_controls, text='Убрать выбранные', style='Small.TButton', command=self.remove_apks).pack(side='left', padx=8)
        apk_table = ttk.Frame(apks_card, style='Card.TFrame')
        apk_table.pack(fill='x')
        self.apk_tree = ttk.Treeview(apk_table, columns=('file', 'path'), show='headings', selectmode='extended', height=5)
        for key, label, width in [('file', 'Приложение', 210), ('path', 'Файл на компьютере', 360)]:
            self.apk_tree.heading(key, text=label)
            self.apk_tree.column(key, width=width, minwidth=90)
        apk_scroll = ttk.Scrollbar(apk_table, orient='vertical', command=self.apk_tree.yview)
        self.apk_tree.configure(yscrollcommand=apk_scroll.set)
        self.apk_tree.pack(side='left', fill='both', expand=True)
        apk_scroll.pack(side='right', fill='y')

        ttk.Label(cleanup, text='Очистка', style='Heading.TLabel').pack(anchor='w', pady=(0, 6))
        paragraph(cleanup, 'Выберите ненужные приложения и службы. Пакеты отключаются, данные не удаляются.', style='Muted.TLabel').pack(fill='x', pady=(0, 14))
        protection = card(cleanup)
        protection.pack(fill='x', pady=(0, 12))
        ttk.Checkbutton(protection, text='Включить очистку выбранных пакетов', variable=self.clean, command=self.selection_summary).pack(anchor='w')
        self.cleanup_summary = tk.StringVar(value='Очистка выключена. Выбор пакетов пока не применяется.')
        paragraph(protection, textvariable=self.cleanup_summary, style='CardSmall.TLabel').pack(fill='x', pady=(0, 10))
        ttk.Label(protection, text='Какие функции сохранить', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 5))
        self.keep_frame = ttk.Frame(protection, style='Card.TFrame')
        self.keep_frame.pack(fill='x')
        self.keep = {key: tk.BooleanVar(value=True) for key in FEATURES}
        for index, (key, label) in enumerate(FEATURES.items()):
            ttk.Checkbutton(self.keep_frame, text=label, variable=self.keep[key], command=self.refresh).grid(row=index // 2, column=index % 2, sticky='w', padx=(0, 18))
        self.keep_frame.columnconfigure(0, weight=1)
        self.keep_frame.columnconfigure(1, weight=1)
        packages_card = card(cleanup, padding=16)
        packages_card.pack(fill='x', pady=(0, 12))
        self.category = tk.StringVar(value='Все категории')
        categories = ['Все категории'] + sorted({rule.category for rule in RULES} | {'Другие пользовательские приложения', 'Неизвестные системные пакеты', 'CleanYourTV — требуется проверка'})
        filter_row = ttk.Frame(packages_card, style='Card.TFrame')
        filter_row.pack(fill='x', pady=(0, 10))
        combo = ttk.Combobox(filter_row, textvariable=self.category, values=categories, state='readonly', width=25)
        combo.pack(fill='x')
        combo.bind('<<ComboboxSelected>>', lambda event: self.refresh())
        self.search = tk.StringVar()
        self.search.trace_add('write', lambda *_: self.refresh())
        search_row = ttk.Frame(packages_card, style='Card.TFrame')
        search_row.pack(fill='x', pady=(0, 10))
        ttk.Label(search_row, text='Поиск', style='CardSmall.TLabel').pack(side='left', padx=(0, 8))
        ttk.Entry(search_row, textvariable=self.search, width=20).pack(side='left', fill='x', expand=True)
        selection_row = ttk.Frame(packages_card, style='Card.TFrame')
        selection_row.pack(fill='x', pady=(0, 12))
        ttk.Button(selection_row, text='Рекомендованные', style='Small.TButton', command=self.recommended).grid(row=0, column=0, sticky='ew', padx=(0, 6))
        ttk.Button(selection_row, text='Выбрать категорию', style='Small.TButton', command=self.select_category).grid(row=0, column=1, sticky='ew')
        ttk.Button(selection_row, text='Снять всё', style='Small.TButton', command=self.clear_selection).grid(row=1, column=0, sticky='w', pady=(6, 0))
        selection_row.columnconfigure(0, weight=1, uniform='actions')
        selection_row.columnconfigure(1, weight=1, uniform='actions')
        table = ttk.Frame(packages_card, style='Card.TFrame')
        table.pack(fill='x')
        self.tree = ttk.Treeview(table, columns=('check', 'title', 'category', 'package'), show='headings', selectmode='browse', height=8)
        for key, label, width in [('check', 'Выбор', 52), ('title', 'Приложение / служба', 180), ('category', 'Категория', 165), ('package', 'Пакет Android', 225)]:
            self.tree.heading(key, text=label)
            self.tree.column(key, width=width, minwidth=45, stretch=key != 'check')
        self.tree.tag_configure('blocked', foreground='#929aa6')
        self.tree.tag_configure('danger', foreground='#a5492b')
        scrollbar = ttk.Scrollbar(table, orient='vertical', command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side='left', fill='both', expand=True)
        scrollbar.pack(side='right', fill='y')
        self.tree.bind('<ButtonRelease-1>', self.toggle)
        self.tree.bind('<space>', self.toggle_keyboard)
        self.tree.bind('<<TreeviewSelect>>', self.details)
        self.detail = tk.StringVar(value='Выберите строку, чтобы увидеть назначение пакета и ограничения. Для выбора отключения нажмите флажок слева или пробел.')
        paragraph(packages_card, textvariable=self.detail, style='CardSmall.TLabel').pack(fill='x', pady=(12, 0))
        expert_card = card(cleanup)
        expert_card.pack(fill='x')
        ttk.Checkbutton(expert_card, text='Экспертный режим · отключить защиту', variable=self.expert, command=self.toggle_expert).pack(anchor='w')
        self.expert_note = tk.StringVar(value='Выключен. Критические и неизвестные системные пакеты защищены.')
        paragraph(expert_card, textvariable=self.expert_note, style='Warning.TLabel').pack(fill='x')

        ttk.Label(recovery, text='Копии и восстановление', style='Heading.TLabel').pack(anchor='w', pady=(0, 14))
        recovery_card = card(recovery)
        recovery_card.pack(fill='x', pady=(0, 12))
        ttk.Label(recovery_card, text='Вернуть отключённые пакеты', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 7))
        paragraph(recovery_card, 'Перед изменениями мастер сохраняет исходные состояния и назначение Home. Копия подходит только тому устройству и пользователю, для которых создана.').pack(fill='x', pady=(0, 7))
        paragraph(recovery_card, 'Для восстановления нужен работающий ADB. Копия не заменяет резервную копию прошивки и не откатывает установленные APK или их версии.', style='CardSmall.TLabel').pack(fill='x', pady=(0, 12))
        recovery_actions = ttk.Frame(recovery_card, style='Card.TFrame')
        recovery_actions.pack(fill='x')
        self.restore_button = ttk.Button(recovery_actions, text='Восстановить из копии…', command=self.restore, state='disabled')
        self.restore_button.pack(side='left')
        ttk.Button(recovery_actions, text='Открыть папку копий', style='Small.TButton', command=self.open_logs).pack(side='left', padx=8)
        preset_card = card(recovery)
        preset_card.pack(fill='x')
        ttk.Label(preset_card, text='Свой набор для следующего устройства', style='CardTitle.TLabel').pack(anchor='w', pady=(0, 7))
        paragraph(preset_card, 'Сохраните выбранные пакеты и функции. При загрузке мастер проверит их совместимость с устройством. Экспертный режим из файла не включается.').pack(fill='x', pady=(0, 12))
        preset_row = ttk.Frame(preset_card, style='Card.TFrame')
        preset_row.pack(fill='x')
        ttk.Button(preset_row, text='Сохранить выбор…', command=self.save_preset).pack(side='left')
        ttk.Button(preset_row, text='Загрузить выбор…', command=self.load_preset).pack(side='left', padx=8)
        apply_theme(root, self.dark_mode.get())
        root.after(80, self.poll)

    def change_theme(self):
        apply_theme(self.root, self.dark_mode.get())
        try:
            write_ui_settings(DATA / 'ui-settings.json', self.dark_mode.get())
        except OSError:
            self.status.set('Тема изменена. Не удалось сохранить настройку рядом с установщиком.')

    def preparation(self):
        messagebox.showinfo('Подготовка Android TV / Google TV',
            '1. Откройте настройки ТВ → Система / Об устройстве. Найдите «Сборка» и нажмите ОК семь раз.\n\n'
            '2. В меню «Для разработчиков» включите отладку USB (на некоторых ТВ — отладку ADB или по сети).\n\n'
            '3. В настройках сети ТВ посмотрите его локальный IP. Подключите ПК к той же сети и введите этот IP в мастер.\n\n'
            '4. Нажмите «Подключиться» и разрешите отладку на экране ТВ.\n\n'
            'Названия пунктов зависят от прошивки. Если нужно сопряжение по коду, выполните его отдельно через adb pair.', parent=self.root)

    def open_airplay_sources(self):
        if self.busy: return
        destination = DATA / 'AirPlay'
        destination.mkdir(parents=True, exist_ok=True)
        for name in ('PeachOS-AirPlay-source.zip', 'LICENSE-AirPlay.txt'):
            shutil.copyfile(ASSETS / name, destination / name)
        os.startfile(destination)

    def switch_mode(self):
        if self.busy: return
        self.advanced = not self.advanced
        if self.advanced:
            self.simple_container.pack_forget()
            self.advanced_container.pack(fill='both', expand=True)
        else:
            if self.expert.get():
                self.expert.set(False)
                self.toggle_expert()
            self.advanced_container.pack_forget()
            self.simple_container.pack(fill='both', expand=True)
        self.mode_button.configure(text='Простой установщик' if self.advanced else 'Расширенный мастер')

    def add_apks(self):
        if self.busy: return
        files = filedialog.askopenfilenames(title='Выберите приложения для установки', filetypes=[('Android APK', '*.apk')])
        self.extra_apks = list(dict.fromkeys(self.extra_apks + [Path(file) for file in files]))
        self.refresh_apks()

    def remove_apks(self):
        if self.busy: return
        removed = set(self.apk_tree.selection())
        self.extra_apks = [file for file in self.extra_apks if str(file) not in removed]
        self.refresh_apks()

    def refresh_apks(self):
        self.apk_tree.delete(*self.apk_tree.get_children())
        for file in self.extra_apks:
            self.apk_tree.insert('', 'end', iid=str(file), values=(file.name, str(file)))

    def toggle_expert(self):
        if self.busy:
            self.expert.set(not self.expert.get())
            return
        if self.expert.get() and not messagebox.askyesno('Экспертный режим — риск окирпичивания',
                'Будут доступны все установленные пакеты, включая Android, сеть, ADB, пульт и главный экран.\n\nОтключение критических компонентов может привести к неработоспособности или окирпичиванию устройства. Восстановление через ADB может стать невозможным и потребовать сброса или перепрошивки.\n\nЗащита «Сохранить функции» в этом режиме не действует. Автоматического выбора критических пакетов нет.\n\nВключить экспертный режим?', icon='warning', default='no'):
            self.expert.set(False)
        self.expert_note.set('ЭКСПЕРТНЫЙ РЕЖИМ: защита отключена. Возможны потеря ADB, загрузки, сети и окирпичивание.' if self.expert.get()
                             else 'Обычный режим: критические и неизвестные пакеты защищены.')
        for widget in self.keep_frame.winfo_children(): widget.configure(state='disabled' if self.expert.get() else 'normal')
        self.refresh()

    def choose_adb(self):
        if self.busy: return
        path = filedialog.askopenfilename(title='Выберите adb.exe из Android Platform Tools', filetypes=[('ADB', 'adb.exe')])
        if path: self.adb_path = path

    def choose_apk(self):
        if self.busy: return
        path = filedialog.askopenfilename(filetypes=[('Android APK', '*.apk')])
        if path: self.apk.set(path)

    def selected_apk(self):
        return self.embedded_apk if self.apk.get() == BUNDLED_APK_LABEL else Path(self.apk.get())

    def open_logs(self):
        DATA.mkdir(parents=True, exist_ok=True)
        os.startfile(DATA)

    def set_busy(self, busy):
        self.busy = busy
        if busy:
            # Freeze all editable controls, including hidden pages. Keep the connection
            # dialog's cancel button usable; it lives outside the main frame.
            pending = [self.frame]
            while pending:
                widget = pending.pop()
                pending.extend(widget.winfo_children())
                if isinstance(widget, (ttk.Button, ttk.Checkbutton, ttk.Entry, ttk.Combobox, ttk.Treeview, ttk.Notebook)):
                    self.frozen_controls[widget] = widget.instate(('disabled',))
                    widget.state(('disabled',))
            self.operation_progress.pack(side='top', fill='x', before=self.plan_button, pady=(0, 8))
            self.operation_progress.start(14)
        else:
            self.operation_progress.stop()
            self.operation_progress.pack_forget()
            for widget, disabled in self.frozen_controls.items():
                widget.state(('disabled',) if disabled else ('!disabled',))
            self.frozen_controls.clear()

    def job(self, action, callback):
        if self.busy: return
        self.set_busy(True)
        def work():
            try:
                result = action()
                self.events.put(('done', callback, result))
            except InterruptedError:
                self.events.put(('cancelled',))
            except Exception as error:
                self.events.put(('error', str(error)))
        threading.Thread(target=work, daemon=True).start()

    def poll(self):
        while not self.events.empty():
            event = self.events.get()
            if event[0] == 'status': self.status.set(event[1])
            else:
                if self.wait_window:
                    self.wait_window.destroy()
                    self.wait_window = None
                self.set_busy(False)
                self.connect_button.configure(state='normal')
                self.simple_connect_button.configure(state='normal')
                for button in (self.plan_button, self.restore_button): button.configure(state='normal' if self.device else 'disabled')
                self.update_button.configure(state='normal' if self.device and 'com.gathin.tvhome.backdrops' in self.device.installed else 'disabled')
                if event[0] == 'done': event[1](event[2])
                elif event[0] == 'cancelled': self.status.set('Подключение отменено. Изменений нет.')
                else:
                    self.invalidate_device('Операция остановлена. Подключитесь заново, чтобы перечитать устройство.')
                    self.simple_stage.set('1  Подключение     →     2  Параметры     →     3  Проверка плана')
                    self.status.set('Операция остановлена. Журнал и копии: ' + str(DATA))
                    messagebox.showerror('Операция не завершена', event[1] + '\n\nПодключитесь заново перед повтором.\nКопии и журнал: ' + str(DATA), parent=self.root)
        self.root.after(80, self.poll)

    def progress(self, text): self.events.put(('status', text))

    def invalidate_device(self, message):
        self.device = self.adb = None
        self.rows.clear()
        self.selected.clear()
        self.profiles = ()
        self.tree.delete(*self.tree.get_children())
        self.device_text.set(message)
        for button in (self.plan_button, self.restore_button, self.update_button):
            button.configure(state='disabled')
        self.selection_summary()

    def connect(self):
        if self.busy: return
        if not self.adb_path:
            messagebox.showinfo('Нужен ADB', 'Выберите adb.exe из официальных Android Platform Tools: https://developer.android.com/tools/releases/platform-tools')
            return
        target = self.target.get().strip()
        adb_path = self.adb_path
        # A failed connection must not leave actions targeting the previously connected TV.
        self.invalidate_device('Подключение к ' + (target or 'единственному ADB-устройству') + '…')
        self.simple_stage.set('1  Подключение     →     2  Параметры     →     3  Проверка плана')
        self.status.set('Подключение и чтение устройства… Подтвердите отладку на ТВ.')
        self.cancel_connect.clear()
        self.wait_window = tk.Toplevel(self.root)
        self.wait_window.title('Подтвердите подключение на ТВ')
        self.wait_window.geometry('600x300')
        self.wait_window.resizable(False, False)
        self.wait_window.transient(self.root)
        self.wait_window.protocol('WM_DELETE_WINDOW', self.cancel_connect.set)
        self.wait_window.grab_set()
        self.wait_window.configure(background=self.root.peach_palette.background)
        content = ttk.Frame(self.wait_window, padding=26); content.pack(fill='both', expand=True)
        ttk.Label(content, text='Подтвердите подключение на ТВ', style='Heading.TLabel').pack(anchor='w')
        paragraph(content, textvariable=self.status, style='Muted.TLabel').pack(fill='x', pady=16)
        paragraph(content, 'Если появится запрос «Разрешить отладку», подтвердите его пультом. Мастер дождётся ответа.', style='Muted.TLabel').pack(fill='x', pady=(0, 16))
        progress = ttk.Progressbar(content, mode='indeterminate'); progress.pack(fill='x', pady=(0, 16)); progress.start(12)
        ttk.Button(content, text='Отменить подключение', command=self.cancel_connect.set).pack(anchor='e')
        apply_theme(self.root, self.dark_mode.get())
        def read_device():
            executable = portable_adb() if adb_path == str(ASSETS / 'platform-tools' / 'adb.exe') else adb_path
            adb = Adb(executable, log=DATA / 'adb-log.jsonl')
            serial = wait_for_device(adb, target, self.cancel_connect, self.progress)
            self.progress('Разрешение получено. Читаю приложения и параметры устройства…')
            device = discover(adb, serial)
            if self.cancel_connect.is_set():
                raise InterruptedError('Подключение отменено')
            return adb, device
        self.job(read_device, self.connected)

    def connected(self, result):
        self.adb, self.device = result
        d = self.device
        self.profiles, self.rows = candidates(d.props, d.installed, d.user_apps, d.home.split('/')[0])
        for package in d.protected_packages: self.rows.pop(package, None)
        self.selected.clear()
        profile = ', '.join(PROFILES[p][0] for p in self.profiles) or 'Общая база; точный профиль не найден'
        self.device_text.set(f"{d.props.get('ro.product.manufacturer', '')} {d.props.get('ro.product.model', '')} · Android {d.props.get('ro.build.version.release', '')} · {profile}")
        self.plan_button.configure(state='normal')
        self.restore_button.configure(state='normal')
        self.refresh()
        already_installed = 'com.gathin.tvhome.backdrops' in d.installed
        self.update_button.configure(state='normal' if already_installed else 'disabled')
        self.simple_stage.set('✓  Устройство подключено     →     2  Параметры     →     3  Проверка плана')
        self.simple_home.set(not already_installed)
        self.compat_home.set(False)
        self.simple_home_choice.configure(state='normal')
        self.simple_hint.set('PeachOS TV уже установлен: обновим до версии из этого мастера. Настройки, порядок и Home сохранятся.' if already_installed
                             else 'Установим встроенный PeachOS TV. Изменения главного экрана будут перечислены в плане.')
        if self.airplay_module:
            reason = airplay_compatibility(self.airplay_module, d.props)
            for choice in self.airplay_choices: choice.configure(state='disabled' if reason else 'normal')
            if reason: self.airplay.set(False)
            self.airplay_hint.set(reason or ('Модуль уже установлен. Снятая галочка оставит его как есть; выбранная обновит без удаления данных.'
                                             if AIRPLAY in d.installed else 'Необязательный модуль: iPhone → «Повтор экрана». Без него в панели остаётся поиск.'))
        self.status.set('Устройство подключено. Выберите APK и необязательную очистку, затем проверьте план.')

    def blocked(self, package):
        if package in self.device.disabled: return 'Уже отключено до этой операции'
        if self.expert.get(): return ''
        if package not in self.rows: return 'Неизвестный или защищённый системный пакет: отключение недоступно'
        kept = [FEATURES[k] for k in self.rows[package].features if self.keep[k].get()]
        return 'Нужно сохранить: ' + ', '.join(kept) if kept else ''

    def visible(self, package):
        rule = self.rows.get(package)
        category = rule.category if rule else 'CleanYourTV — требуется проверка' if package in REFERENCE['packages'] else 'Неизвестные системные пакеты'
        text = package + ' ' + (rule.title if rule else '')
        return (self.category.get() == 'Все категории' or self.category.get() == category) and self.search.get().casefold() in text.casefold()

    def refresh(self):
        if not self.device: return
        self.selected = {p for p in self.selected if not self.blocked(p)}
        focus = self.tree.selection()
        self.tree.delete(*self.tree.get_children())
        for package in sorted(self.device.installed, key=lambda p: (self.rows[p].category if p in self.rows else 'Я', p)):
            if not self.visible(package): continue
            rule = self.rows.get(package)
            reason = self.blocked(package)
            self.tree.insert('', 'end', iid=package, values=('—' if reason else '☑' if package in self.selected else '☐',
                             rule.title if rule else package, rule.category if rule else 'CleanYourTV — требуется проверка' if package in REFERENCE['packages'] else 'Неизвестные системные пакеты', package),
                             tags=('blocked',) if reason else ('danger',) if self.expert.get() and package not in self.rows else ())
        if focus and self.tree.exists(focus[0]): self.tree.selection_set(focus[0])
        self.selection_summary()

    def selection_summary(self):
        count = len(self.selected)
        self.cleanup_summary.set(f'Будет отключено: {count}. Изменения начнутся после подтверждения плана.' if self.clean.get()
                                 else f'Выбрано: {count}. Очистка выключена — пакеты не будут изменены.')
        if self.device:
            self.status.set(('Экспертный режим. ' if self.expert.get() else '')
                            + (f'К отключению: {count}.' if self.clean.get() else f'Выбрано пакетов: {count}; очистка выключена.')
                            + ' Проверьте итоговый план перед применением.')

    def toggle(self, event):
        if self.busy: return
        package = self.tree.identify_row(event.y)
        if package and self.tree.identify_column(event.x) == '#1': self.toggle_package(package)

    def toggle_keyboard(self, event):
        if not self.busy and self.tree.selection(): self.toggle_package(self.tree.selection()[0])
        return 'break'

    def toggle_package(self, package):
        reason = self.blocked(package)
        if reason: self.detail.set(reason); return
        if package in self.selected: self.selected.remove(package)
        else: self.selected.add(package)
        self.tree.set(package, 'check', '☑' if package in self.selected else '☐')
        self.selection_summary()

    def details(self, event=None):
        if not self.tree.selection(): return
        package = self.tree.selection()[0]
        rule = self.rows.get(package)
        self.detail.set(package + '\n' + (rule.impact if rule else 'Назначение не подтверждено базой. В экспертном режиме отключение на ваш риск.')
                        + '\n' + (self.blocked(package) or ('Профиль: ' + ', '.join(PROFILES[p][0] for p in rule.profiles) if rule and rule.profiles else 'Проверьте назначение пакета перед отключением.')))
        evidence = REFERENCE['packages'].get(package)
        if evidence:
            sources = evidence['profiles'] + evidence['optional_profiles']
            self.detail.set(self.detail.get() + '\nCleanYourTV: ' + ', '.join(sources[:3]) + (' …' if len(sources) > 3 else '')
                            + '. Присутствие в чужом списке не подтверждает безопасность на вашей модели.')

    def recommended(self):
        if self.busy or not self.device: return
        self.selected = {p for p, rule in self.rows.items() if rule.suggested and not self.blocked(p)}
        self.refresh()

    def select_category(self):
        if self.busy or not self.device: return
        if self.category.get() == 'Все категории' or (self.category.get() in ('Неизвестные системные пакеты', 'CleanYourTV — требуется проверка') and not self.expert.get()):
            messagebox.showinfo('Выберите категорию', 'Выберите конкретную категорию, чтобы не отключить всё сразу.')
            return
        if self.expert.get() and not messagebox.askyesno('Массовое отключение', 'Выбрать все видимые пакеты категории? Среди них могут быть критические компоненты.', icon='warning', default='no'):
            return
        self.selected.update(p for p in self.device.installed if self.visible(p) and not self.blocked(p))
        self.refresh()

    def clear_selection(self):
        if not self.busy: self.selected.clear(); self.refresh()

    def save_preset(self):
        if self.busy or not self.device: return
        path = filedialog.asksaveasfilename(defaultextension='.json', initialfile='PeachOS-choice.json', filetypes=[('Выбор пакетов', '*.json')])
        if path:
            try:
                Path(path).write_text(json.dumps({'version': 1, 'packages': sorted(self.selected),
                                                 'keep': {k: v.get() for k, v in self.keep.items()}}, ensure_ascii=False, indent=2), encoding='utf-8')
            except OSError as error:
                messagebox.showerror('Не удалось сохранить', str(error))

    def load_preset(self):
        if self.busy or not self.device: return
        path = filedialog.askopenfilename(filetypes=[('Выбор пакетов', '*.json')])
        if not path: return
        try:
            preset = read_preset(Path(path))
        except (OSError, ValueError) as error:
            messagebox.showerror('Не удалось загрузить', str(error))
            return
        for key, value in preset['keep'].items(): self.keep[key].set(value)
        self.selected = {p for p in preset['packages'] if p in self.device.installed and not self.blocked(p)}
        self.refresh()
        messagebox.showinfo('Выбор загружен', f'Доступно для этого устройства: {len(self.selected)} из {len(preset["packages"])}. Неподходящие и защищённые пакеты пропущены. Изменения не применены.')

    def select_compatible_home(self):
        if self.compat_home.get():
            self.home.set(True)
            self.simple_home.set(True)
            self.simple_hint.set('Настроим Home. Если штатное назначение не работает, применим совместимый режим через службу пульта; изменения будут указаны в плане.')

    def review(self, update_only=False):
        if self.busy or not self.device: return
        selected = sorted(self.selected) if self.clean.get() else []
        apk = self.selected_apk() if self.install.get() else None
        set_home = self.home.get() or self.compat_home.get()
        allow_home_redirect = self.compat_home.get()
        extra_apks = tuple(self.extra_apks)
        airplay_apk = self.airplay_module.file if self.airplay.get() and self.airplay_module and not update_only else None
        expert = self.expert.get()
        keep = {key for key, value in self.keep.items() if value.get()}
        if not self.advanced and not update_only:
            apk = self.embedded_apk
            extra_apks = ()
            expert = False
            keep = set(FEATURES)
            selected = [p for p, rule in self.rows.items() if self.simple_cleanup.get() and rule.suggested
                        and not set(rule.features).intersection(keep) and p not in self.device.disabled]
            installed = 'com.gathin.tvhome.backdrops' in self.device.installed
            set_home = self.simple_home.get() or self.compat_home.get()
            allow_home_redirect = self.compat_home.get()
            update_only = installed and not selected and not airplay_apk and not set_home
        if update_only:
            if 'com.gathin.tvhome.backdrops' not in self.device.installed:
                messagebox.showinfo('Лаунчер ещё не установлен', 'Используйте обычную установку PeachOS TV.')
                return
            apk, extra_apks, selected, set_home, allow_home_redirect, expert = self.embedded_apk, (), [], False, False, False
        if airplay_apk:
            reason = airplay_compatibility(self.airplay_module, self.device.props)
            if reason:
                messagebox.showinfo('AirPlay недоступен', reason)
                return
        if not (apk or extra_apks or airplay_apk or selected or set_home):
            messagebox.showinfo('План пуст', 'Выберите установку APK, назначение Home или пакеты очистки.')
            return
        lines = [self.device_text.get(), '', 'Режим: ' + ('Только обновление — очистка и смена Home исключены' if update_only else 'Установка и выбранные операции'),
                 'APK: ' + (BUNDLED_APK_LABEL if apk == self.embedded_apk else str(apk) if apk else 'не устанавливать'),
                 'Дополнительные APK (по порядку):', *['• ' + str(file) for file in extra_apks], '',
                 'Home: ' + ('назначить PeachOS' if set_home else 'не менять'),
                 'Совместимый Home: ' + ('разрешить службу пульта и поддерживаемый автозапуск, если обычный Home не работает' if allow_home_redirect else 'новые службы и разрешения не включаются'),
                 'Сохранить: ' + ', '.join(FEATURES[k] for k in keep), '', f'Отключить пакетов: {len(selected)}']
        airplay_lines = ['AirPlay: установить отдельный модуль — ' + str(airplay_apk),
                         'Он заменит лупу в панели переключателем AirPlay. Включённый значок будет синим.',
                         'Для автоматического полноэкранного приёма модуль попросит разрешение «Поверх других приложений». Мастер его не выдаёт.',
                         'После завершения трансляции вернётся предыдущий экран. Модуль — прототип, GPLv3; исходники и лицензия доступны в мастере.'] if airplay_apk else [
                             'AirPlay: установленный модуль не менять.' if AIRPLAY in self.device.installed
                             else 'AirPlay: не устанавливать; в панели остаётся поиск.']
        lines += ['', *airplay_lines]
        for package in selected:
            rule = self.rows.get(package)
            lines += [f'• {rule.title} ({package})' if rule else '• ' + package,
                      '  ' + (rule.impact if rule else 'НЕИЗВЕСТНЫЙ / СИСТЕМНЫЙ ПАКЕТ. Возможна потеря загрузки, сети или ADB.')]
        if expert and selected:
            lines += ['', 'ЭКСПЕРТНЫЙ РЕЖИМ: защита критических пакетов и сохраняемых функций отключена. Возможны окирпичивание и невозможность восстановления через ADB.']
        lines += ['', 'Удалений и очистки данных не будет.', 'Откат возвращает состояния пакетов и исходный Home, но не удаляет установленный APK.',
                  'Установка APK не атомарна: при ошибке предыдущие APK останутся установленными. Копия не откатывает версии APK.',
                  'Резервные копии и подробный журнал: ' + str(DATA), 'После применения проверьте пульт, HDMI, сеть и используемые приложения.']
        if not self.advanced:
            lines = [self.device_text.get(), '', 'Обновить PeachOS TV' if update_only else 'Установить PeachOS TV',
                     'Настройки и порядок приложений сохранятся. Приложения и данные не удаляются.', '',
                     'Главный экран: ' + ('назначить PeachOS' if set_home else 'оставить как сейчас'),
                     'Очистка: ' + (f'отключить {len(selected)} рекомендованных пакетов' if selected else 'не выполнять')]
            lines += ['', *airplay_lines]
            if allow_home_redirect:
                lines += ['', 'Если обычный Home не работает: включить службу «Кнопки пульта» и автозапуск PeachOS, если он поддерживается. Системные приложения не отключаются.']
            for package in selected:
                rule = self.rows[package]
                lines += ['• ' + rule.title + ': ' + rule.impact]
            lines += ['', 'Копии и журнал сохранятся рядом с установщиком в PeachOS-TV-data.',
                      'Копия возвращает состояния отключённых пакетов, но не предыдущую версию APK.']
        # The reviewed plan is a snapshot. UI edits never alter an operation already
        # approved, and the core independently revalidates the device at its boundary.
        selected, keep = tuple(selected), frozenset(keep)
        adb, device = self.adb, self.device
        window = tk.Toplevel(self.root)
        window.title('Проверка плана · PeachOS TV')
        window.geometry(f'{min(860, self.root.winfo_screenwidth() - 60)}x{min(650, self.root.winfo_screenheight() - 70)}')
        window.minsize(min(720, self.root.winfo_screenwidth() - 60), min(520, self.root.winfo_screenheight() - 70))
        colors = self.root.peach_palette
        window.configure(background=colors.background)
        window.transient(self.root)
        window.grab_set()
        shell = ttk.Frame(window, padding=22)
        shell.pack(fill='both', expand=True)
        controls = ttk.Frame(shell)
        controls.pack(side='bottom', fill='x', pady=(16, 0))
        ttk.Button(controls, text='Вернуться к выбору', command=window.destroy).pack(side='left')
        page = ScrollPage(shell)
        page.pack(fill='both', expand=True)
        content = page.body
        ttk.Label(content, text='Проверим изменения', style='Heading.TLabel').pack(anchor='w', pady=(0, 6))
        paragraph(content, self.device_text.get(), style='Muted.TLabel').pack(fill='x', pady=(0, 14))
        summary = card(content)
        summary.pack(fill='x', pady=(0, 12))
        launcher_action = ('Обновить PeachOS TV' if 'com.gathin.tvhome.backdrops' in device.installed else 'Установить PeachOS TV') if apk else 'Не устанавливать'
        values = (
            ('Лаунчер', launcher_action),
            ('Главный экран', ('Штатно; служба пульта при необходимости' if allow_home_redirect else 'Назначить PeachOS') if set_home else 'Оставить как сейчас'),
            ('Очистка', f'Отключить пакетов: {len(selected)}' if selected else 'Не выполнять'),
            ('Ваши приложения', f'{len(extra_apks)} APK по порядку' if extra_apks else 'Не устанавливать'),
            ('AirPlay', 'Установить отдельный модуль' if airplay_apk else 'Оставить как сейчас'),
            ('Резервная копия', 'Сохранить исходные состояния'),
        )
        for index, (label, value) in enumerate(values):
            field = ttk.Frame(summary, style='Card.TFrame')
            field.grid(row=index // 2, column=index % 2, sticky='nsew', padx=(0, 16), pady=(0, 12))
            paragraph(field, label, style='CardSmall.TLabel').pack(fill='x')
            paragraph(field, value, style='Card.TLabel').pack(fill='x', pady=(3, 0))
        summary.columnconfigure(0, weight=1, uniform='summary')
        summary.columnconfigure(1, weight=1, uniform='summary')
        paragraph(content, 'Данные приложений сохранятся. Копия восстанавливает состояния пакетов и Home, но не откатывает версии APK.',
                  style='Muted.TLabel').pack(fill='x', pady=(0, 10))
        if airplay_apk:
            paragraph(content, 'AirPlay — необязательный прототип. Для автоматического приёма модуль отдельно попросит разрешение «Поверх других приложений».',
                      style='Muted.TLabel').pack(fill='x', pady=(0, 10))
        if allow_home_redirect:
            permission_card = card(content, padding=14)
            permission_card.pack(fill='x', pady=(0, 10))
            paragraph(permission_card, 'Совместимый Home разрешает службе PeachOS наблюдать смену окон во всех приложениях. Для нового режима фильтрация кнопок выключена; существующие настройки пульта сохранятся. На некоторых прошивках требуется разрешение автозапуска. После установки проверьте физические Home и громкость.', style='CardMuted.TLabel').pack(fill='x')
        if expert and selected:
            warning = card(content, padding=14)
            warning.pack(fill='x', pady=(0, 10))
            paragraph(warning, 'Экспертный план: защита отключена. Возможны потеря ADB и окирпичивание; копия не гарантирует восстановление.',
                      style='Warning.TLabel').pack(fill='x')
        details = ttk.Frame(content)
        text = ScrolledText(details, wrap='word', height=14, padx=15, pady=15, background=colors.surface,
                            foreground=colors.text, insertbackground=colors.text, relief='flat', font=('Segoe UI', 10),
                            highlightthickness=1, highlightbackground=colors.border)
        text.pack(fill='both', expand=True)
        text.insert('1.0', '\n'.join(lines))
        text.configure(state='disabled')
        details_visible = tk.BooleanVar(value=False)
        def toggle_details():
            if details_visible.get():
                details.pack(fill='x', pady=(8, 0))
            else:
                details.pack_forget()
        ttk.Checkbutton(content, text='Показать подробный план и список пакетов', variable=details_visible,
                        style='Plain.TCheckbutton', command=toggle_details).pack(anchor='w')
        def apply():
            if expert and selected and not messagebox.askyesno('Подтверждение экспертного плана',
                    'Отключить выбранные пакеты без защиты? Это может привести к окирпичиванию и потере доступа по ADB. Копия не гарантирует восстановление.', parent=window, icon='warning', default='no'):
                return
            window.destroy()
            self.status.set('Выполняется подтверждённый план. Не отключайте устройство от сети.')
            self.job(lambda: apply_plan(adb, device, selected, keep, apk, set_home, DATA / 'backups', self.progress,
                                       allow_home_redirect=allow_home_redirect, extra_apks=extra_apks, expert=expert, update_only=update_only,
                                       airplay_apk=airplay_apk), self.completed)
        ttk.Button(controls, text='Применить план', style='Accent.TButton', command=apply).pack(side='right')
        apply_theme(self.root, self.dark_mode.get())

    def completed(self, path):
        self.status.set('Готово. План выполнен; копия сохранена в PeachOS-TV-data.')
        # Device state has changed. Keep an explicit success screen rather than
        # silently starting a second connection/authentication workflow.
        self.invalidate_device('Операция завершена. Подключитесь снова для нового плана.')
        self.simple_stage.set('✓  План выполнен     ·     Можно проверить телевизор')
        self.status.set('Готово. План выполнен; копия сохранена в PeachOS-TV-data.')
        window = tk.Toplevel(self.root)
        window.title('Готово · PeachOS TV')
        window.geometry('640x400')
        window.minsize(560, 360)
        window.transient(self.root)
        window.configure(background=self.root.peach_palette.background)
        window.grab_set()
        content = ttk.Frame(window, padding=26)
        content.pack(fill='both', expand=True)
        done = card(content)
        done.pack(fill='both', expand=True)
        ttk.Label(done, text='Всё готово', style='Success.TLabel').pack(anchor='w', pady=(0, 12))
        paragraph(done, 'План выполнен. Проверьте Home, микрофон, громкость, HDMI и сеть на телевизоре.', style='Card.TLabel').pack(fill='x', pady=(0, 10))
        paragraph(done, 'Копия исходных состояний и журнал сохранены:').pack(fill='x')
        paragraph(done, str(path), style='CardSmall.TLabel').pack(fill='x', pady=(4, 10))
        paragraph(done, 'Копия не откатывает установленные APK. Отключите отладку, если удалённый доступ больше не нужен.', style='CardSmall.TLabel').pack(fill='x')
        actions = ttk.Frame(content)
        actions.pack(fill='x', pady=(16, 0))
        ttk.Button(actions, text='Открыть копию и журнал', command=self.open_logs).pack(side='left')
        ttk.Button(actions, text='Готово', style='Accent.TButton', command=window.destroy).pack(side='right')
        apply_theme(self.root, self.dark_mode.get())

    def restore(self):
        if self.busy or not self.device: return
        path = filedialog.askopenfilename(title='Выберите restore.json из резервной копии', initialdir=str(DATA / 'backups'), filetypes=[('Журнал восстановления', '*.json')])
        if path and messagebox.askyesno('Восстановление', 'Вернуть состояния пакетов и Home из выбранной копии? Устройство будет сверено автоматически.'):
            self.job(lambda: restore(self.adb, self.device, Path(path), self.progress), self.completed)

    def close(self):
        if self.wait_window:
            self.cancel_connect.set()
        elif self.busy:
            messagebox.showinfo('Операция выполняется', 'Дождитесь окончания команды. При обрыве соединения журнал сохранён в ' + str(DATA))
        else: self.root.destroy()


if __name__ == '__main__':
    root = tk.Tk()
    if len(sys.argv) == 3 and sys.argv[1] == '--self-test':
        root.withdraw()
        app = Wizard(root)
        root.update_idletasks()
        test_adb = portable_adb() if app.adb_path == str(ASSETS / 'platform-tools' / 'adb.exe') else app.adb_path
        Path(sys.argv[2]).write_text(json.dumps({'installer_version': SETUP_VERSION,
                                               'tk': root.tk.call('info', 'patchlevel'), 'rules': len(RULES),
                                               'adb': bool(app.adb_path), 'apk': app.selected_apk().exists(),
                                               'icon': (ASSETS / 'peachos.ico').exists(), 'tabs': len(app.tabs.tabs()),
                                               'expert_default': app.expert.get(),
                                               'compatible_home_default': app.compat_home.get(),
                                               'simple_default': not app.advanced, 'reference_packages': len(REFERENCE['packages']),
                                               'airplay_bundled': app.airplay_module is not None, 'airplay_default': app.airplay.get(),
                                               'adb_version': Adb(test_adb).run('version', target=False) if test_adb else '',
                                               'apk_sha256': hashlib.sha256(app.selected_apk().read_bytes()).hexdigest() if app.selected_apk().exists() else ''}), encoding='utf-8')
        root.destroy()
    else:
        Wizard(root)
        root.mainloop()
