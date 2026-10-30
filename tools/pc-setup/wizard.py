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

from catalog import FEATURES, PROFILES, RULES, candidates
from core import Adb, discover, wait_for_device, apply_plan, restore, read_preset

BASE = Path(sys.executable).parent if getattr(sys, 'frozen', False) else Path(__file__).parent
ASSETS = Path(sys._MEIPASS) if getattr(sys, 'frozen', False) else BASE
DATA = Path(os.environ.get('PEACHOS_SETUP_DATA', str(BASE / 'PeachOS-TV-data')))
BUNDLED_APK_LABEL = 'Встроенный PeachOS TV'


def portable_adb():
    # ADB's server can outlive the GUI. Run it from the persistent portable cache,
    # so it does not lock the one-file runtime's temporary extraction directory.
    target = DATA / 'platform-tools'
    target.mkdir(parents=True, exist_ok=True)
    for name in ('adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt'):
        source, destination = ASSETS / 'platform-tools' / name, target / name
        if not destination.exists() or hashlib.sha256(source.read_bytes()).digest() != hashlib.sha256(destination.read_bytes()).digest():
            shutil.copyfile(source, destination)
    return target / 'adb.exe'


class Wizard:
    def __init__(self, root):
        self.root, self.events = root, queue.Queue()
        self.device = self.adb = None
        self.rows, self.selected, self.profiles = {}, set(), ()
        self.extra_apks = []
        self.busy = False
        self.wait_window = None
        self.cancel_connect = threading.Event()
        root.title('PeachOS TV · Setup')
        root.geometry('1120x800')
        root.minsize(960, 720)
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.configure(background='#171920')
        style = ttk.Style(root)
        style.theme_use('clam')
        style.configure('.', background='#171920', foreground='#f4f3f5', font=('Segoe UI', 10), borderwidth=0,
                        bordercolor='#171920', lightcolor='#171920', darkcolor='#171920')
        style.configure('TLabel', padding=0)
        style.configure('Muted.TLabel', foreground='#a9adbb')
        style.configure('Title.TLabel', font=('Segoe UI', 23, 'bold'))
        style.configure('Heading.TLabel', font=('Segoe UI', 15, 'bold'))
        style.configure('Warning.TLabel', foreground='#ffb49e')
        style.configure('TButton', background='#30333f', padding=(15, 10), focusthickness=1, focuscolor='#ffb49e')
        style.map('TButton', background=[('active', '#424654'), ('disabled', '#242630')], foreground=[('disabled', '#717584')])
        style.configure('Accent.TButton', background='#ffb49e', foreground='#25212a', font=('Segoe UI', 10, 'bold'))
        style.map('Accent.TButton', background=[('active', '#ffccb9'), ('disabled', '#35343b')], foreground=[('disabled', '#797682')])
        style.configure('TEntry', fieldbackground='#262934', foreground='#f4f3f5', padding=9, insertcolor='white')
        style.configure('TCheckbutton', padding=(2, 8), indicatorbackground='#30333f', indicatorforeground='#ffb49e')
        style.map('TCheckbutton', background=[('active', '#171920')], indicatorbackground=[('selected', '#ffb49e')])
        style.configure('TCombobox', fieldbackground='#262934', background='#30333f', arrowcolor='#f4f3f5', padding=7)
        style.map('TCombobox', fieldbackground=[('readonly', '#262934')], foreground=[('readonly', '#f4f3f5')])
        root.option_add('*TCombobox*Listbox.background', '#262934')
        root.option_add('*TCombobox*Listbox.foreground', '#f4f3f5')
        style.configure('TNotebook', borderwidth=0)
        style.layout('TNotebook.Tab', [])
        style.configure('Nav.TButton', background='#171920', foreground='#a9adbb', padding=(20, 13))
        style.configure('Active.Nav.TButton', background='#30333f', foreground='#ffb49e')
        style.configure('Treeview', background='#20232d', fieldbackground='#20232d', foreground='#f4f3f5', rowheight=34)
        style.map('Treeview', background=[('selected', '#414554')], foreground=[('selected', '#ffffff')])
        style.configure('Treeview.Heading', background='#2c303c', foreground='#bfc3d0', padding=9, relief='flat')
        style.configure('Horizontal.TProgressbar', background='#ffb49e', troughcolor='#252833', borderwidth=0)
        if (ASSETS / 'peachos.ico').exists(): root.iconbitmap(str(ASSETS / 'peachos.ico'))
        frame = ttk.Frame(root, padding=(28, 22))
        frame.pack(fill='both', expand=True)
        header = ttk.Frame(frame)
        header.pack(fill='x')
        if (ASSETS / 'peachos.png').exists():
            self.brand = tk.PhotoImage(file=str(ASSETS / 'peachos.png'))
            ttk.Label(header, image=self.brand).pack(side='left', padx=(0, 16))
        title = ttk.Frame(header)
        title.pack(side='left')
        ttk.Label(title, text='PeachOS TV', style='Title.TLabel').pack(anchor='w')
        ttk.Label(title, text='Ваш телевизор. Ваши приложения. Ничего лишнего.', style='Muted.TLabel').pack(anchor='w', pady=(2, 0))
        ttk.Label(header, text='PORTABLE  /  PREVIEW', style='Muted.TLabel').pack(side='right')
        self.status = tk.StringVar(value='Начните с подключения устройства. Никаких автоматических изменений.')
        footer = ttk.Frame(frame); footer.pack(side='bottom', fill='x', pady=(16, 0))
        ttk.Label(footer, textvariable=self.status, style='Muted.TLabel', wraplength=730).pack(side='left', fill='x', expand=True)
        self.plan_button = ttk.Button(footer, text='Посмотреть план →', style='Accent.TButton', command=self.review, state='disabled')
        self.plan_button.pack(side='right', padx=(16, 0))
        navigation = ttk.Frame(frame); navigation.pack(fill='x', pady=(20, 18))
        self.tabs = ttk.Notebook(frame)
        self.tabs.pack(fill='both', expand=True)
        connection, apps, cleanup, recovery = [ttk.Frame(self.tabs, padding=(4, 6)) for _ in range(4)]
        self.nav_buttons = []
        for index, (panel, label) in enumerate(zip((connection, apps, cleanup, recovery), ('01  Устройство', '02  Приложения', '03  Очистка', '04  Восстановление'))):
            self.tabs.add(panel, text=label)
            button = ttk.Button(navigation, text=label, style='Active.Nav.TButton' if index == 0 else 'Nav.TButton',
                                command=lambda i=index: self.tabs.select(i))
            button.pack(side='left', padx=(0, 6)); self.nav_buttons.append(button)
        def navigation_changed(event):
            for i, button in enumerate(self.nav_buttons):
                button.configure(style='Active.Nav.TButton' if i == self.tabs.index('current') else 'Nav.TButton')
        self.tabs.bind('<<NotebookTabChanged>>', navigation_changed)

        ttk.Label(connection, text='Подключим телевизор', style='Heading.TLabel').pack(anchor='w', pady=(12, 8))
        ttk.Label(connection, text='Включите отладку в настройках разработчика. ПК и телевизор должны быть в одной сети.', style='Muted.TLabel').pack(anchor='w')
        ttk.Label(connection, text='Адрес устройства', style='Muted.TLabel').pack(anchor='w', pady=(28, 6))
        row = ttk.Frame(connection); row.pack(fill='x')
        self.target = tk.StringVar()
        entry = ttk.Entry(row, textvariable=self.target, width=36)
        entry.pack(side='left', padx=(0, 12))
        entry.bind('<Return>', lambda event: self.connect())
        self.connect_button = ttk.Button(row, text='Подключиться', style='Accent.TButton', command=self.connect)
        self.connect_button.pack(side='left')
        ttk.Label(connection, text='Например: 192.168.1.199  ·  или USB-серийный номер. Для единственного USB-устройства оставьте поле пустым.', style='Muted.TLabel', wraplength=930).pack(anchor='w', pady=10)
        self.device_text = tk.StringVar(value='Устройство пока не подключено')
        ttk.Label(connection, textvariable=self.device_text, style='Heading.TLabel', wraplength=920).pack(anchor='w', pady=(32, 12))
        ttk.Label(connection, text='При первом подключении на ТВ появится запрос. Мастер дождётся подтверждения — не закрывайте его.\nОчистка и установка начинаются только после просмотра и подтверждения плана.', style='Muted.TLabel', wraplength=920).pack(anchor='w')
        ttk.Button(connection, text='Другой adb.exe…', command=self.choose_adb).pack(anchor='w', pady=24)
        bundled_adb = ASSETS / 'platform-tools' / 'adb.exe'
        self.adb_path = str(bundled_adb) if bundled_adb.exists() else shutil.which('adb') or ''

        self.install = tk.BooleanVar(value=True)
        self.clean = tk.BooleanVar(value=False)
        self.home = tk.BooleanVar(value=False)
        self.xiaomi_home = tk.BooleanVar(value=False)
        self.expert = tk.BooleanVar(value=False)
        self.embedded_apk = ASSETS / 'PeachOS-TV.apk'
        self.apk = tk.StringVar(value=BUNDLED_APK_LABEL if self.embedded_apk.exists() else str(BASE / 'PeachOS-TV.apk'))
        ttk.Label(apps, text='Установка приложений', style='Heading.TLabel').pack(anchor='w', pady=(12, 8))
        ttk.Checkbutton(apps, text='Установить PeachOS TV', variable=self.install).pack(anchor='w')
        apk_row = ttk.Frame(apps); apk_row.pack(fill='x', pady=(0, 8))
        ttk.Label(apk_row, textvariable=self.apk, style='Muted.TLabel', wraplength=660).pack(side='left', fill='x', expand=True)
        ttk.Button(apk_row, text='Другой APK лаунчера…', command=self.choose_apk).pack(side='right')
        ttk.Button(apk_row, text='Встроенный', command=lambda: self.apk.set(BUNDLED_APK_LABEL) if not self.busy else None).pack(side='right', padx=8)
        ttk.Checkbutton(apps, text='Назначить PeachOS главным экраном', variable=self.home).pack(anchor='w')
        ttk.Checkbutton(apps, text='Обход Home на Xiaomi MiTV-AESP0 / Android 9: обратимо отключить штатный Home при необходимости', variable=self.xiaomi_home).pack(anchor='w')
        ttk.Separator(apps).pack(fill='x', pady=16)
        apk_controls = ttk.Frame(apps); apk_controls.pack(fill='x', pady=(0, 10))
        ttk.Label(apk_controls, text='Ваши APK с компьютера', style='Heading.TLabel').pack(side='left')
        ttk.Button(apk_controls, text='Добавить APK…', command=self.add_apks).pack(side='right')
        ttk.Button(apk_controls, text='Убрать выбранные', command=self.remove_apks).pack(side='right', padx=8)
        apk_table = ttk.Frame(apps); apk_table.pack(fill='both', expand=True)
        self.apk_tree = ttk.Treeview(apk_table, columns=('file', 'path'), show='headings', selectmode='extended', height=5)
        for key, label, width in [('file', 'Приложение', 260), ('path', 'Файл на компьютере', 650)]:
            self.apk_tree.heading(key, text=label); self.apk_tree.column(key, width=width, minwidth=80)
        apk_scroll = ttk.Scrollbar(apk_table, orient='vertical', command=self.apk_tree.yview)
        self.apk_tree.configure(yscrollcommand=apk_scroll.set)
        self.apk_tree.pack(side='left', fill='both', expand=True); apk_scroll.pack(side='right', fill='y')
        ttk.Label(apps, text='Установка по очереди, с сохранением данных. Обычные .apk; split-наборы .apks / .xapk не поддерживаются.', style='Muted.TLabel').pack(anchor='w', pady=10)

        cleanup_top = ttk.Frame(cleanup); cleanup_top.pack(fill='x')
        ttk.Checkbutton(cleanup_top, text='Отключить выбранные пакеты', variable=self.clean).pack(side='left')
        ttk.Checkbutton(cleanup_top, text='Экспертный режим', variable=self.expert, command=self.toggle_expert).pack(side='right')
        self.expert_note = tk.StringVar(value='Обычный режим: критические и неизвестные пакеты защищены.')
        ttk.Label(cleanup, textvariable=self.expert_note, style='Warning.TLabel', wraplength=980).pack(anchor='w', pady=(0, 4))
        self.keep_frame = ttk.Frame(cleanup)
        self.keep_frame.pack(fill='x')
        self.keep = {key: tk.BooleanVar(value=True) for key in FEATURES}
        for index, (key, label) in enumerate(FEATURES.items()):
            ttk.Checkbutton(self.keep_frame, text='Сохранить: ' + label, variable=self.keep[key], command=self.refresh).grid(row=index // 3, column=index % 3, sticky='w', padx=(0, 14))
        filter_row = ttk.Frame(cleanup); filter_row.pack(fill='x', pady=10)
        self.category = tk.StringVar(value='Все категории')
        categories = ['Все категории'] + sorted({r.category for r in RULES} | {'Другие пользовательские приложения', 'Неизвестные системные пакеты'})
        combo = ttk.Combobox(filter_row, textvariable=self.category, values=categories, state='readonly', width=30)
        combo.pack(side='left'); combo.bind('<<ComboboxSelected>>', lambda event: self.refresh())
        self.search = tk.StringVar(); self.search.trace_add('write', lambda *_: self.refresh())
        ttk.Label(filter_row, text='Поиск', style='Muted.TLabel').pack(side='left', padx=(10, 0))
        ttk.Entry(filter_row, textvariable=self.search, width=18).pack(side='left', padx=8)
        ttk.Button(filter_row, text='Рекомендованные', command=self.recommended).pack(side='left')
        ttk.Button(filter_row, text='Категория', command=self.select_category).pack(side='left', padx=8)
        ttk.Button(filter_row, text='Снять всё', command=self.clear_selection).pack(side='left')
        self.detail = tk.StringVar(value='Поиск по названию или пакету. Нажмите строку, чтобы увидеть назначение и ограничения.')
        ttk.Label(cleanup, textvariable=self.detail, style='Muted.TLabel', wraplength=850, justify='left').pack(side='bottom', fill='x', pady=8)
        table = ttk.Frame(cleanup); table.pack(fill='both', expand=True)
        self.tree = ttk.Treeview(table, columns=('check', 'title', 'category', 'package'), show='headings', selectmode='browse')
        for key, title, width in [('check', 'Отключить', 85), ('title', 'Приложение / служба', 220), ('category', 'Категория', 210), ('package', 'Пакет Android', 380)]:
            self.tree.heading(key, text=title); self.tree.column(key, width=width, minwidth=50, stretch=key != 'check')
        self.tree.tag_configure('blocked', foreground='#7f8594')
        self.tree.tag_configure('danger', foreground='#ffb49e')
        scrollbar = ttk.Scrollbar(table, orient='vertical', command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side='left', fill='both', expand=True); scrollbar.pack(side='right', fill='y')
        self.tree.bind('<ButtonRelease-1>', self.toggle); self.tree.bind('<space>', self.toggle_keyboard)
        self.tree.bind('<<TreeviewSelect>>', self.details)

        ttk.Label(recovery, text='Можно вернуться назад', style='Heading.TLabel').pack(anchor='w', pady=(12, 10))
        ttk.Label(recovery, text='Перед отключением сохраняем состояния пакетов. Копия привязана к конкретному устройству.\nВосстановление требует работающего ADB; это не резервная копия прошивки или приложений.\nУстановленные APK и обновления приложений автоматически не откатываются.', style='Muted.TLabel', wraplength=930).pack(anchor='w', pady=(0, 20))
        self.restore_button = ttk.Button(recovery, text='Восстановить из копии…', command=self.restore, state='disabled')
        self.restore_button.pack(anchor='w')
        ttk.Button(recovery, text='Открыть журналы и копии', command=self.open_logs).pack(anchor='w', pady=10)
        ttk.Separator(recovery).pack(fill='x', pady=18)
        ttk.Label(recovery, text='Ваш набор отключений', style='Heading.TLabel').pack(anchor='w', pady=(0, 8))
        ttk.Label(recovery, text='Сохраняйте выбор пакетов и функций для следующего запуска. Экспертный режим не включается из файла.', style='Muted.TLabel', wraplength=930).pack(anchor='w')
        preset_row = ttk.Frame(recovery); preset_row.pack(anchor='w', pady=14)
        ttk.Button(preset_row, text='Сохранить выбор…', command=self.save_preset).pack(side='left')
        ttk.Button(preset_row, text='Загрузить выбор…', command=self.load_preset).pack(side='left', padx=10)
        root.after(80, self.poll)

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

    def job(self, action, callback):
        if self.busy: return
        self.busy = True
        for button in (self.connect_button, self.plan_button, self.restore_button): button.configure(state='disabled')
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
                self.busy = False
                self.connect_button.configure(state='normal')
                for button in (self.plan_button, self.restore_button): button.configure(state='normal' if self.device else 'disabled')
                if event[0] == 'done': event[1](event[2])
                elif event[0] == 'cancelled': self.status.set('Подключение отменено. Изменений нет.')
                else:
                    self.status.set('Операция остановлена. Журнал и копии: ' + str(DATA))
                    messagebox.showerror('Операция не завершена', event[1] + '\n\nКопии и журнал: ' + str(DATA))
        self.root.after(80, self.poll)

    def progress(self, text): self.events.put(('status', text))

    def connect(self):
        if self.busy: return
        if not self.adb_path:
            messagebox.showinfo('Нужен ADB', 'Выберите adb.exe из официальных Android Platform Tools: https://developer.android.com/tools/releases/platform-tools')
            return
        target = self.target.get().strip()
        adb_path = self.adb_path
        # A failed connection must not leave actions targeting the previously connected TV.
        self.device = self.adb = None
        self.rows.clear(); self.selected.clear()
        self.tree.delete(*self.tree.get_children())
        self.device_text.set('Подключение к ' + (target or 'единственному ADB-устройству') + '…')
        self.status.set('Подключение и чтение устройства… Подтвердите отладку на ТВ.')
        self.cancel_connect.clear()
        self.wait_window = tk.Toplevel(self.root)
        self.wait_window.title('Подтвердите подключение на ТВ')
        self.wait_window.geometry('600x340')
        self.wait_window.resizable(False, False)
        self.wait_window.transient(self.root)
        self.wait_window.protocol('WM_DELETE_WINDOW', self.cancel_connect.set)
        self.wait_window.grab_set()
        content = ttk.Frame(self.wait_window, padding=24); content.pack(fill='both', expand=True)
        ttk.Label(content, text='Разрешите отладку на телевизоре', style='Heading.TLabel').pack(anchor='w')
        ttk.Label(content, textvariable=self.status, wraplength=540, style='Muted.TLabel').pack(anchor='w', pady=16)
        progress = ttk.Progressbar(content, mode='indeterminate'); progress.pack(fill='x', pady=(0, 16)); progress.start(12)
        ttk.Button(content, text='Отменить подключение', command=self.cancel_connect.set).pack(anchor='e')
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
        self.status.set('Устройство подключено. Выберите APK и необязательную очистку, затем проверьте план.')

    def blocked(self, package):
        if package in self.device.disabled: return 'Уже отключено до этой операции'
        if self.expert.get(): return ''
        if package not in self.rows: return 'Неизвестный или защищённый системный пакет: отключение недоступно'
        kept = [FEATURES[k] for k in self.rows[package].features if self.keep[k].get()]
        return 'Нужно сохранить: ' + ', '.join(kept) if kept else ''

    def visible(self, package):
        rule = self.rows.get(package)
        category = rule.category if rule else 'Неизвестные системные пакеты'
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
                             rule.title if rule else package, rule.category if rule else 'Неизвестные системные пакеты', package),
                             tags=('blocked',) if reason else ('danger',) if self.expert.get() and package not in self.rows else ())
        if focus and self.tree.exists(focus[0]): self.tree.selection_set(focus[0])
        self.status.set(f'Отключить: {len(self.selected)} · Установлено пакетов: {len(self.device.installed)} · ' + ('Экспертный режим — защита отключена' if self.expert.get() else 'Обычный режим — защита включена'))

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
        self.refresh()

    def details(self, event=None):
        if not self.tree.selection(): return
        package = self.tree.selection()[0]
        rule = self.rows.get(package)
        self.detail.set(package + '\n' + (rule.impact if rule else 'Назначение не подтверждено базой. В экспертном режиме отключение на ваш риск.')
                        + '\n' + (self.blocked(package) or ('Профиль: ' + ', '.join(PROFILES[p][0] for p in rule.profiles) if rule and rule.profiles else 'Проверьте назначение пакета перед отключением.')))

    def recommended(self):
        if self.busy or not self.device: return
        self.selected = {p for p, rule in self.rows.items() if rule.suggested and not self.blocked(p)}
        self.refresh()

    def select_category(self):
        if self.busy or not self.device: return
        if self.category.get() == 'Все категории' or (self.category.get() == 'Неизвестные системные пакеты' and not self.expert.get()):
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

    def review(self):
        if self.busy or not self.device: return
        selected = sorted(self.selected) if self.clean.get() else []
        apk = self.selected_apk() if self.install.get() else None
        set_home = self.home.get()
        xiaomi_home = self.xiaomi_home.get() and set_home
        extra_apks = tuple(self.extra_apks)
        expert = self.expert.get()
        keep = {key for key, value in self.keep.items() if value.get()}
        if not (apk or extra_apks or selected or set_home):
            messagebox.showinfo('План пуст', 'Выберите установку APK, назначение Home или пакеты очистки.')
            return
        lines = [self.device_text.get(), '', 'APK: ' + (self.apk.get() if apk else 'не устанавливать'),
                 'Дополнительные APK (по порядку):', *['• ' + str(file) for file in extra_apks], '',
                 'Home: ' + ('назначить PeachOS' if set_home else 'не менять'),
                 'Обход Xiaomi: ' + ('если выбор игнорируется, отключить com.google.android.tvlauncher; состояние сохранится в копии' if xiaomi_home else 'выключен; штатный лаунчер не отключается'),
                 'Сохранить: ' + ', '.join(FEATURES[k] for k in keep), '', f'Отключить пакетов: {len(selected)}']
        for package in selected:
            rule = self.rows.get(package)
            lines += [f'• {rule.title} ({package})' if rule else '• ' + package,
                      '  ' + (rule.impact if rule else 'НЕИЗВЕСТНЫЙ / СИСТЕМНЫЙ ПАКЕТ. Возможна потеря загрузки, сети или ADB.')]
        if expert and selected:
            lines += ['', 'ЭКСПЕРТНЫЙ РЕЖИМ: защита критических пакетов и сохраняемых функций отключена. Возможны окирпичивание и невозможность восстановления через ADB.']
        lines += ['', 'Удалений и очистки данных не будет.', 'Откат возвращает состояния пакетов и исходный Home, но не удаляет установленный APK.',
                  'Установка APK не атомарна: при ошибке предыдущие APK останутся установленными. Копия не откатывает версии APK.',
                  'Резервные копии и подробный журнал: ' + str(DATA), 'После применения проверьте пульт, HDMI, сеть и используемые приложения.']
        window = tk.Toplevel(self.root)
        window.title('Проверка плана — изменений пока нет')
        window.geometry('900x650')
        window.transient(self.root)
        window.grab_set()
        text = ScrolledText(window, wrap='word', padx=20, pady=20, background='#20232d', foreground='#f4f3f5', insertbackground='white', relief='flat', font=('Segoe UI', 10))
        text.pack(fill='both', expand=True)
        text.insert('1.0', '\n'.join(lines)); text.configure(state='disabled')
        controls = ttk.Frame(window, padding=12); controls.pack(fill='x')
        ttk.Button(controls, text='Отмена', command=window.destroy).pack(side='left')
        def apply():
            if expert and selected and not messagebox.askyesno('Подтверждение экспертного плана',
                    'Отключить выбранные пакеты без защиты? Это может привести к окирпичиванию и потере доступа по ADB. Копия не гарантирует восстановление.', parent=window, icon='warning', default='no'):
                return
            window.destroy()
            self.job(lambda: apply_plan(self.adb, self.device, selected, keep, apk, set_home, DATA / 'backups', self.progress,
                                       xiaomi_home_fallback=xiaomi_home, extra_apks=extra_apks, expert=expert), self.completed)
        ttk.Button(controls, text='Применить этот план', command=apply).pack(side='right')

    def completed(self, path):
        self.status.set('Готово. Восстановление: ' + str(path))
        messagebox.showinfo('Готово', 'План выполнен.\nКопия для восстановления:\n' + str(path) + '\n\nПроверьте Home, микрофон, громкость, HDMI и сеть. Отключите отладку, если удалённый доступ больше не нужен.')
        self.connect()

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
        Path(sys.argv[2]).write_text(json.dumps({'tk': root.tk.call('info', 'patchlevel'), 'rules': len(RULES),
                                               'adb': bool(app.adb_path), 'apk': app.selected_apk().exists(),
                                               'icon': (ASSETS / 'peachos.ico').exists(), 'tabs': len(app.tabs.tabs()),
                                               'expert_default': app.expert.get(),
                                               'adb_version': Adb(test_adb).run('version', target=False) if test_adb else '',
                                               'apk_sha256': hashlib.sha256(app.selected_apk().read_bytes()).hexdigest() if app.selected_apk().exists() else ''}), encoding='utf-8')
        root.destroy()
    else:
        Wizard(root)
        root.mainloop()
