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
from core import Adb, discover, apply_plan, restore, read_preset

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
        self.busy = False
        root.title('PeachOS TV · Мастер установки и очистки')
        root.geometry('1180x850')
        root.minsize(980, 700)
        root.protocol('WM_DELETE_WINDOW', self.close)
        frame = ttk.Frame(root, padding=14)
        frame.pack(fill='both', expand=True)
        ttk.Label(frame, text='Установка лаунчера и необязательная очистка', font=('Segoe UI', 18, 'bold')).pack(anchor='w')
        ttk.Label(frame, text='Галочка в списке = отключить. Сначала проверьте план. Приложения и их данные не удаляются.').pack(anchor='w', pady=(4, 12))
        connection = ttk.Frame(frame)
        connection.pack(fill='x')
        ttk.Label(connection, text='IP:порт или USB-серийный номер:').pack(side='left')
        self.target = tk.StringVar()
        ttk.Entry(connection, textvariable=self.target, width=27).pack(side='left', padx=6)
        self.connect_button = ttk.Button(connection, text='Подключиться', command=self.connect)
        self.connect_button.pack(side='left', padx=4)
        ttk.Button(connection, text='Выбрать adb.exe', command=self.choose_adb).pack(side='left')
        bundled_adb = ASSETS / 'platform-tools' / 'adb.exe'
        self.adb_path = str(bundled_adb) if bundled_adb.exists() else shutil.which('adb') or ''
        self.device_text = tk.StringVar(value='Включите отладку на ТВ. При подключении подтвердите разрешение компьютеру.')
        ttk.Label(frame, textvariable=self.device_text, wraplength=1120).pack(anchor='w', pady=8)
        apk_row = ttk.Frame(frame)
        apk_row.pack(fill='x')
        self.embedded_apk = ASSETS / 'PeachOS-TV.apk'
        self.apk = tk.StringVar(value=BUNDLED_APK_LABEL if self.embedded_apk.exists() else str(BASE / 'PeachOS-TV.apk'))
        ttk.Label(apk_row, text='APK:').pack(side='left')
        ttk.Entry(apk_row, textvariable=self.apk).pack(side='left', fill='x', expand=True, padx=6)
        ttk.Button(apk_row, text='Выбрать', command=self.choose_apk).pack(side='left')
        options = ttk.Frame(frame)
        options.pack(fill='x', pady=9)
        self.install = tk.BooleanVar(value=True)
        self.clean = tk.BooleanVar(value=False)
        self.home = tk.BooleanVar(value=False)
        for label, variable in [('Установить APK', self.install), ('Очистить выбранные пакеты', self.clean), ('Назначить PeachOS главным экраном', self.home)]:
            ttk.Checkbutton(options, text=label, variable=variable).pack(side='left', padx=(0, 18))
        self.xiaomi_home = tk.BooleanVar(value=False)
        ttk.Checkbutton(frame, text='Обход Home Xiaomi MiTV-AESP0 / Android 9: отключить штатный лаунчер, если назначение игнорируется (обратимо)',
                        variable=self.xiaomi_home).pack(anchor='w', pady=(0, 9))
        keep_frame = ttk.LabelFrame(frame, text='Что сохранить (пакеты этих функций нельзя выбрать для отключения)', padding=6)
        keep_frame.pack(fill='x')
        self.keep = {key: tk.BooleanVar(value=True) for key in FEATURES}
        for index, (key, label) in enumerate(FEATURES.items()):
            ttk.Checkbutton(keep_frame, text=label, variable=self.keep[key], command=self.refresh).grid(row=index // 4, column=index % 4, sticky='w', padx=10, pady=3)
        filter_row = ttk.Frame(frame)
        filter_row.pack(fill='x', pady=8)
        self.category = tk.StringVar(value='Все категории')
        categories = ['Все категории'] + sorted({r.category for r in RULES} | {'Другие пользовательские приложения', 'Неизвестные системные пакеты'})
        combo = ttk.Combobox(filter_row, textvariable=self.category, values=categories, state='readonly', width=34)
        combo.pack(side='left')
        combo.bind('<<ComboboxSelected>>', lambda event: self.refresh())
        self.search = tk.StringVar()
        self.search.trace_add('write', lambda *_: self.refresh())
        ttk.Entry(filter_row, textvariable=self.search, width=27).pack(side='left', padx=6)
        ttk.Button(filter_row, text='Рекомендованные', command=self.recommended).pack(side='left', padx=3)
        ttk.Button(filter_row, text='Выбрать категорию', command=self.select_category).pack(side='left', padx=3)
        ttk.Button(filter_row, text='Снять всё', command=self.clear_selection).pack(side='left', padx=3)
        table = ttk.Frame(frame)
        table.pack(fill='both', expand=True)
        self.tree = ttk.Treeview(table, columns=('check', 'title', 'category', 'package'), show='headings', selectmode='browse')
        for key, title, width in [('check', 'Отключить', 85), ('title', 'Приложение / служба', 230), ('category', 'Категория', 215), ('package', 'Пакет Android', 405)]:
            self.tree.heading(key, text=title)
            self.tree.column(key, width=width, minwidth=50, stretch=key != 'check')
        self.tree.tag_configure('blocked', foreground='#888888')
        scrollbar = ttk.Scrollbar(table, orient='vertical', command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side='left', fill='both', expand=True)
        scrollbar.pack(side='right', fill='y')
        self.tree.bind('<ButtonRelease-1>', self.toggle)
        self.tree.bind('<space>', self.toggle_keyboard)
        self.tree.bind('<<TreeviewSelect>>', self.details)
        self.detail = tk.StringVar(value='Выберите строку: здесь появятся назначение пакета, зависимости и ограничения.')
        ttk.Label(frame, textvariable=self.detail, wraplength=1120, justify='left').pack(fill='x', pady=8)
        self.status = tk.StringVar(value='Очистка по умолчанию выключена. Можно установить только APK.')
        ttk.Label(frame, textvariable=self.status, wraplength=1120).pack(anchor='w', pady=4)
        bottom = ttk.Frame(frame)
        bottom.pack(fill='x')
        self.plan_button = ttk.Button(bottom, text='Посмотреть план →', command=self.review, state='disabled')
        self.plan_button.pack(side='right')
        self.restore_button = ttk.Button(bottom, text='Восстановить из копии…', command=self.restore, state='disabled')
        self.restore_button.pack(side='left')
        ttk.Button(bottom, text='Папка журналов', command=lambda: self.open_logs()).pack(side='left', padx=8)
        ttk.Button(bottom, text='Сохранить выбор…', command=self.save_preset).pack(side='left', padx=4)
        ttk.Button(bottom, text='Загрузить выбор…', command=self.load_preset).pack(side='left', padx=4)
        root.after(80, self.poll)

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
            except Exception as error:
                self.events.put(('error', str(error)))
        threading.Thread(target=work, daemon=True).start()

    def poll(self):
        while not self.events.empty():
            event = self.events.get()
            if event[0] == 'status': self.status.set(event[1])
            else:
                self.busy = False
                self.connect_button.configure(state='normal')
                for button in (self.plan_button, self.restore_button): button.configure(state='normal' if self.device else 'disabled')
                if event[0] == 'done': event[1](event[2])
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
        def read_device():
            executable = portable_adb() if adb_path == str(ASSETS / 'platform-tools' / 'adb.exe') else adb_path
            adb = Adb(executable, log=DATA / 'adb-log.jsonl')
            return adb, discover(adb, target)
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

    def blocked(self, package):
        if package not in self.rows: return 'Неизвестный или защищённый системный пакет: отключение недоступно'
        if package in self.device.disabled: return 'Уже отключено до этой операции'
        kept = [FEATURES[k] for k in self.rows[package].features if self.keep[k].get()]
        return 'Нужно сохранить: ' + ', '.join(kept) if kept else ''

    def visible(self, package):
        rule = self.rows.get(package)
        category = rule.category if rule else 'Неизвестные системные пакеты'
        text = package + ' ' + (rule.title if rule else '')
        return (self.category.get() == 'Все категории' or self.category.get() == category) and self.search.get().casefold() in text.casefold()

    def refresh(self):
        if not hasattr(self, 'tree') or not self.device: return
        self.selected = {p for p in self.selected if not self.blocked(p)}
        focus = self.tree.selection()
        self.tree.delete(*self.tree.get_children())
        for package in sorted(self.device.installed, key=lambda p: (self.rows[p].category if p in self.rows else 'Я', p)):
            if not self.visible(package): continue
            rule = self.rows.get(package)
            reason = self.blocked(package)
            self.tree.insert('', 'end', iid=package, values=('—' if reason else '☑' if package in self.selected else '☐',
                             rule.title if rule else package, rule.category if rule else 'Неизвестные системные пакеты', package), tags=('blocked',) if reason else ())
        if focus and self.tree.exists(focus[0]): self.tree.selection_set(focus[0])
        self.status.set(f'Выбрано для отключения: {len(self.selected)}. Доступно кандидатов: {len(self.rows)}. Неизвестные системные пакеты только для просмотра.')

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
        self.detail.set(package + '\n' + (rule.impact if rule else 'Назначение не подтверждено базой. Не отключается мастером.')
                        + '\n' + (self.blocked(package) or ('Профиль: ' + ', '.join(PROFILES[p][0] for p in rule.profiles) if rule.profiles else 'Общий кандидат; выбор остаётся за пользователем.')))

    def recommended(self):
        if self.busy or not self.device: return
        self.selected = {p for p, rule in self.rows.items() if rule.suggested and not self.blocked(p)}
        self.refresh()

    def select_category(self):
        if self.busy or not self.device: return
        if self.category.get() in ('Все категории', 'Неизвестные системные пакеты'):
            messagebox.showinfo('Выберите категорию', 'Выберите конкретную категорию, чтобы не отключить всё сразу.')
            return
        self.selected.update(p for p in self.rows if self.visible(p) and not self.blocked(p))
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
        self.selected = {p for p in preset['packages'] if p in self.rows and not self.blocked(p)}
        self.refresh()
        messagebox.showinfo('Выбор загружен', f'Доступно для этого устройства: {len(self.selected)} из {len(preset["packages"])}. Неподходящие и защищённые пакеты пропущены. Изменения не применены.')

    def review(self):
        if self.busy or not self.device: return
        selected = sorted(self.selected) if self.clean.get() else []
        apk = self.selected_apk() if self.install.get() else None
        set_home = self.home.get()
        xiaomi_home = self.xiaomi_home.get() and set_home
        keep = {key for key, value in self.keep.items() if value.get()}
        if not (apk or selected or set_home):
            messagebox.showinfo('План пуст', 'Выберите установку APK, назначение Home или пакеты очистки.')
            return
        lines = [self.device_text.get(), '', 'APK: ' + (self.apk.get() if apk else 'не устанавливать'),
                 'Home: ' + ('назначить PeachOS' if set_home else 'не менять'),
                 'Обход Xiaomi: ' + ('если выбор игнорируется, отключить com.google.android.tvlauncher; состояние сохранится в копии' if xiaomi_home else 'выключен; штатный лаунчер не отключается'),
                 'Сохранить: ' + ', '.join(FEATURES[k] for k in keep), '', f'Отключить пакетов: {len(selected)}']
        for package in selected:
            rule = self.rows[package]
            lines += [f'• {rule.title} ({package})', '  ' + rule.impact]
        lines += ['', 'Удалений и очистки данных не будет.', 'Откат возвращает состояния пакетов и исходный Home, но не удаляет установленный APK.',
                  'Резервные копии и подробный журнал: ' + str(DATA), 'После применения проверьте пульт, HDMI, сеть и используемые приложения.']
        window = tk.Toplevel(self.root)
        window.title('Проверка плана — изменений пока нет')
        window.geometry('900x650')
        window.transient(self.root)
        window.grab_set()
        text = ScrolledText(window, wrap='word', padx=12, pady=12)
        text.pack(fill='both', expand=True)
        text.insert('1.0', '\n'.join(lines)); text.configure(state='disabled')
        controls = ttk.Frame(window, padding=12); controls.pack(fill='x')
        ttk.Button(controls, text='Отмена', command=window.destroy).pack(side='left')
        def apply():
            window.destroy()
            self.job(lambda: apply_plan(self.adb, self.device, selected, keep, apk, set_home, DATA / 'backups', self.progress,
                                       xiaomi_home_fallback=xiaomi_home), self.completed)
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
        if self.busy:
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
                                               'adb_version': Adb(test_adb).run('version', target=False) if test_adb else '',
                                               'apk_sha256': hashlib.sha256(app.selected_apk().read_bytes()).hexdigest() if app.selected_apk().exists() else ''}), encoding='utf-8')
        root.destroy()
    else:
        Wizard(root)
        root.mainloop()
