"""Shared presentation widgets for the portable Tk installer; no device logic."""
import tkinter as tk
from tkinter import ttk
from dataclasses import dataclass

BACKGROUND = '#f4f5f7'
SURFACE = '#ffffff'
TEXT = '#202330'
MUTED = '#687181'
ACCENT = '#f1ac91'


@dataclass(frozen=True)
class Palette:
    background: str
    surface: str
    text: str
    muted: str
    border: str
    button: str
    hover: str
    pressed: str
    disabled: str
    disabled_text: str
    selected: str
    selected_text: str
    warning: str
    success: str
    heading: str
    scrollbar: str


LIGHT = Palette(BACKGROUND, SURFACE, TEXT, MUTED, '#d7dce5', '#e9ecf1', '#e0e4eb', '#d8dde5',
                '#eef0f4', '#9da4af', '#f8e2d7', '#75432e', '#a5492b', '#25764d', '#edf0f4', '#d7dce4')
DARK = Palette('#171920', '#242733', '#f2f3f7', '#a8afbf', '#3a3f4e', '#343947', '#414756', '#4b5263',
               '#292d38', '#737c90', '#493630', '#ffd4c1', '#ffb49e', '#93d4ac', '#2e3340', '#50586b')


def configure_styles(root, dark=False):
    ttk.Style(root).theme_use('clam')
    apply_theme(root, dark)
    # One application binding, rather than one retained callback per transient plan.
    root.bind_all('<MouseWheel>', scroll_page_wheel, add='+')


def apply_theme(root, dark):
    colors = DARK if dark else LIGHT
    root.peach_palette = colors
    root.peach_dark = dark
    style = ttk.Style(root)
    style.configure('.', background=colors.background, foreground=colors.text, font=('Segoe UI', 10),
                    borderwidth=0, bordercolor=colors.background, lightcolor=colors.background, darkcolor=colors.background)
    style.configure('TFrame', background=colors.background)
    style.configure('Card.TFrame', background=colors.surface, bordercolor=colors.border,
                    lightcolor=colors.border, darkcolor=colors.border)
    style.configure('TLabel', padding=0)
    style.configure('Muted.TLabel', foreground=colors.muted)
    style.configure('Title.TLabel', font=('Segoe UI', 22, 'bold'))
    style.configure('Heading.TLabel', font=('Segoe UI', 17, 'bold'))
    style.configure('Version.TLabel', foreground=colors.muted, font=('Segoe UI', 10))
    for name, font, color in (
            ('Card.TLabel', ('Segoe UI', 10), colors.text),
            ('CardTitle.TLabel', ('Segoe UI', 13, 'bold'), colors.text),
            ('CardMuted.TLabel', ('Segoe UI', 10), colors.muted),
            ('CardSmall.TLabel', ('Segoe UI', 9), colors.muted),
            ('Warning.TLabel', ('Segoe UI', 10), colors.warning),
            ('Success.TLabel', ('Segoe UI', 14, 'bold'), colors.success)):
        style.configure(name, background=colors.surface, font=font, foreground=color)
    style.configure('TButton', background=colors.button, foreground=colors.text, padding=(14, 9),
                    borderwidth=0, focusthickness=1, focuscolor=ACCENT)
    style.map('TButton', background=[('disabled', colors.disabled), ('pressed', colors.pressed), ('active', colors.hover)],
              foreground=[('disabled', colors.disabled_text)])
    style.configure('Accent.TButton', background=ACCENT, foreground='#29222a', font=('Segoe UI', 10, 'bold'))
    style.map('Accent.TButton', background=[('disabled', colors.disabled), ('pressed', '#df967b'), ('active', '#f5bda6')],
              foreground=[('disabled', colors.disabled_text)])
    style.configure('Small.TButton', padding=(10, 6), font=('Segoe UI', 9))
    style.configure('Link.TButton', background=colors.background, foreground=colors.selected_text, padding=(6, 5))
    style.map('Link.TButton', background=[('active', colors.hover)])
    style.configure('Nav.TButton', anchor='w', background=colors.background, foreground=colors.muted, padding=(16, 12))
    style.configure('Active.Nav.TButton', anchor='w', background=colors.selected, foreground=colors.selected_text,
                    font=('Segoe UI', 10, 'bold'), padding=(16, 12))
    style.map('Active.Nav.TButton', background=[('active', colors.selected)])
    style.configure('TEntry', fieldbackground=colors.surface, foreground=colors.text, padding=9,
                    borderwidth=1, bordercolor=colors.border, lightcolor=colors.border, darkcolor=colors.border, insertcolor=colors.text)
    style.map('TEntry', bordercolor=[('focus', '#cf8a6f')], fieldbackground=[('disabled', colors.disabled)],
              foreground=[('disabled', colors.disabled_text)])
    style.configure('TCheckbutton', background=colors.surface, padding=(0, 6), indicatorbackground=colors.surface,
                    indicatorforeground='#29222a', bordercolor=colors.border)
    style.map('TCheckbutton', background=[('active', colors.surface)],
              indicatorbackground=[('disabled', colors.disabled), ('selected', ACCENT)], foreground=[('disabled', colors.disabled_text)])
    style.configure('Plain.TCheckbutton', background=colors.background)
    style.map('Plain.TCheckbutton', background=[('active', colors.background)])
    style.configure('TCombobox', fieldbackground=colors.surface, background=colors.heading, foreground=colors.text,
                    arrowcolor=colors.muted, padding=7, borderwidth=1, bordercolor=colors.border)
    style.map('TCombobox', fieldbackground=[('disabled', colors.disabled), ('readonly', colors.surface)],
              foreground=[('disabled', colors.disabled_text), ('readonly', colors.text)])
    root.option_add('*TCombobox*Listbox.background', colors.surface)
    root.option_add('*TCombobox*Listbox.foreground', colors.text)
    root.option_add('*TCombobox*Listbox.selectBackground', colors.selected)
    root.option_add('*TCombobox*Listbox.selectForeground', colors.text)
    style.configure('TNotebook', borderwidth=0)
    style.layout('TNotebook.Tab', [])
    style.configure('Treeview', background=colors.surface, fieldbackground=colors.surface, foreground=colors.text,
                    rowheight=29, borderwidth=0, font=('Segoe UI', 9))
    style.map('Treeview', background=[('selected', colors.selected)], foreground=[('selected', colors.text)])
    style.configure('Treeview.Heading', background=colors.heading, foreground=colors.muted,
                    padding=(7, 8), relief='flat', font=('Segoe UI', 9, 'bold'))
    style.configure('Vertical.TScrollbar', background=colors.scrollbar, troughcolor=colors.background,
                    borderwidth=0, arrowcolor=colors.muted, width=11, bordercolor=colors.background,
                    lightcolor=colors.scrollbar, darkcolor=colors.scrollbar)
    style.map('Vertical.TScrollbar', background=[('disabled', colors.disabled), ('pressed', colors.pressed), ('active', colors.hover)],
              lightcolor=[('disabled', colors.disabled)], darkcolor=[('disabled', colors.disabled)],
              arrowcolor=[('disabled', colors.disabled_text)])
    style.configure('Horizontal.TProgressbar', background=ACCENT, troughcolor=colors.disabled, borderwidth=0)
    pending = [root]
    while pending:
        widget = pending.pop()
        pending.extend(widget.winfo_children())
        if isinstance(widget, (tk.Tk, tk.Toplevel, tk.Canvas)):
            widget.configure(background=colors.background)
        elif isinstance(widget, tk.Text):
            widget.configure(background=colors.surface, foreground=colors.text, insertbackground=colors.text,
                             selectbackground=colors.selected, selectforeground=colors.text, highlightbackground=colors.border)
        elif isinstance(widget, (tk.Frame, tk.Scrollbar)) and not isinstance(widget, ttk.Widget):
            widget.configure(background=colors.surface)
            if isinstance(widget, tk.Scrollbar):
                widget.configure(troughcolor=colors.surface, activebackground=colors.hover, highlightbackground=colors.surface)
        elif isinstance(widget, ttk.Treeview):
            widget.tag_configure('blocked', foreground=colors.disabled_text)
            widget.tag_configure('danger', foreground=colors.warning)



def card(parent, padding=20):
    return ttk.Frame(parent, style='Card.TFrame', padding=padding, borderwidth=1, relief='solid')


def paragraph(parent, text=None, *, textvariable=None, style='CardMuted.TLabel'):
    label = ttk.Label(parent, text=text, textvariable=textvariable, style=style,
                      wraplength=640, justify='left', anchor='w')
    label.bind('<Configure>', lambda event: label.configure(wraplength=max(120, event.width)))
    return label


class ScrollPage(ttk.Frame):
    """A page that stays usable at small window sizes and Windows display scaling."""
    def __init__(self, parent):
        super().__init__(parent)
        self.canvas = tk.Canvas(self, background=self._root().peach_palette.background, highlightthickness=0, borderwidth=0)
        scrollbar = ttk.Scrollbar(self, orient='vertical', command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right', fill='y', padx=(7, 0))
        self.canvas.pack(side='left', fill='both', expand=True)
        self.body = ttk.Frame(self.canvas)
        item = self.canvas.create_window((0, 0), window=self.body, anchor='nw')
        self.body.bind('<Configure>', lambda event: self.canvas.configure(scrollregion=self.canvas.bbox('all')))
        self.canvas.bind('<Configure>', lambda event: self.canvas.itemconfigure(item, width=event.width))


def scroll_page_wheel(event):
    widget = event.widget
    # Some Tk-native popups supply only a Tcl path rather than a Python widget.
    if not isinstance(widget, tk.Misc):
        return
    while widget is not None:
        if widget.winfo_class() in ('Treeview', 'Text', 'TCombobox', 'Listbox'):
            return
        if isinstance(widget, ScrollPage):
            if widget.canvas.yview() != (0.0, 1.0):
                widget.canvas.yview_scroll(-1 if event.delta > 0 else 1, 'units')
                return 'break'
            return
        widget = widget.master
