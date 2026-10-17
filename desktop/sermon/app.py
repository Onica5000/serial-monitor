"""Main window: menus, toolbar, board tabs, tool dialogs, settings, theme, alerts."""
import json
import os
import sys
import time
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, colorchooser, font as tkfont

from . import __version__
from .core import (LINE_ENDINGS, CHECKSUMS, checksum_all, parse_hex, port_info, load_settings, save_settings,
                   settings_path, compile_pattern, RateLimiter, load_log, extract_backtrace_addresses,
                   find_addr2line, guess_tool_for_text, run_addr2line, addr2line_search_roots)
from .tab import BoardTab

THEMES = {
    "light": dict(bg="#f0f0f0", fg="#000000", text_bg="#ffffff", text_fg="#000000", sel="#3399ff", sys="#888888",
                  found="#fff176", cur="#ff9800", plot_bg="#ffffff", grid="#e0e0e0", field="#ffffff"),
    "dark": dict(bg="#2b2b2b", fg="#dddddd", text_bg="#1e1e1e", text_fg="#d4d4d4", sel="#264f78", sys="#808080",
                 found="#614d00", cur="#b86e00", plot_bg="#1e1e1e", grid="#3a3a3a", field="#3c3c3c"),
}


class ListEditor(tk.Toplevel):
    """Generic editor for a list of dicts. fields: [(key, label, kind, default, options)] kind: str/bool/choice/color."""

    def __init__(self, master, title, items, fields, on_save):
        super().__init__(master)
        self.title(title); self.transient(master); self.geometry("700x380")
        self.items = [dict(i) for i in items]
        self.fields, self.on_save = fields, on_save
        cols = [f[0] for f in fields]
        self.tree = ttk.Treeview(self, columns=cols, show="headings", height=8)
        for k, label, *_ in fields:
            self.tree.heading(k, text=label); self.tree.column(k, width=110)
        self.tree.pack(fill="both", expand=True, padx=6, pady=6)
        self.tree.bind("<<TreeviewSelect>>", lambda e: self._load())
        form = ttk.Frame(self); form.pack(fill="x", padx=6)
        self.vars = {}
        for i, (k, label, kind, default, opts) in enumerate(fields):
            ttk.Label(form, text=label).grid(row=i // 3, column=(i % 3) * 2, sticky="e", padx=2, pady=2)
            if kind == "bool":
                v = tk.BooleanVar(value=default); w = ttk.Checkbutton(form, variable=v)
            elif kind == "choice":
                v = tk.StringVar(value=default); w = ttk.Combobox(form, textvariable=v, values=opts, state="readonly", width=16)
            elif kind == "color":
                v = tk.StringVar(value=default); w = ttk.Frame(form)
                ttk.Entry(w, textvariable=v, width=9).pack(side="left")
                ttk.Button(w, text="...", width=3, command=lambda v=v: self._pick(v)).pack(side="left")
            else:
                v = tk.StringVar(value=default); w = ttk.Entry(form, textvariable=v, width=20)
            w.grid(row=i // 3, column=(i % 3) * 2 + 1, sticky="w", padx=2, pady=2)
            self.vars[k] = v
        bb = ttk.Frame(self); bb.pack(fill="x", padx=6, pady=6)
        ttk.Button(bb, text="Add", command=self._add).pack(side="left")
        ttk.Button(bb, text="Update", command=self._update).pack(side="left", padx=4)
        ttk.Button(bb, text="Delete", command=self._delete).pack(side="left")
        ttk.Button(bb, text="Up", command=lambda: self._move(-1)).pack(side="left", padx=4)
        ttk.Button(bb, text="Down", command=lambda: self._move(1)).pack(side="left")
        ttk.Button(bb, text="Close", command=self.destroy).pack(side="right")
        self._refresh()

    def _pick(self, v):
        c = colorchooser.askcolor(v.get() or "#ff0000", parent=self)[1]
        if c:
            v.set(c)

    def _form(self):
        return {k: v.get() for k, v in self.vars.items()}

    def _sel(self):
        s = self.tree.selection()
        return self.tree.index(s[0]) if s else None

    def _load(self):
        i = self._sel()
        if i is not None:
            for k, v in self.vars.items():
                v.set(self.items[i].get(k, v.get()))

    def _refresh(self):
        self.tree.delete(*self.tree.get_children())
        for it in self.items:
            self.tree.insert("", "end", values=[it.get(f[0], "") for f in self.fields])
        self.on_save(self.items)

    def _add(self):
        d = self._form()
        if not str(d.get(self.fields[0][0], "")).strip():
            return
        self.items.append(d); self._refresh()

    def _update(self):
        i = self._sel()
        if i is not None:
            self.items[i] = self._form(); self._refresh()

    def _delete(self):
        i = self._sel()
        if i is not None:
            del self.items[i]; self._refresh()

    def _move(self, d):
        i = self._sel()
        if i is not None and 0 <= i + d < len(self.items):
            self.items[i], self.items[i + d] = self.items[i + d], self.items[i]
            self._refresh()
            self.tree.selection_set(self.tree.get_children()[i + d])


class App:
    def __init__(self, root, settings=None):
        self.root = root
        self.settings = settings if settings is not None else load_settings()
        root.title(f"Serial Monitor {__version__}")
        root.geometry(self.settings.get("geometry", "1200x750"))
        root.minsize(800, 450)
        fams = set(tkfont.families())
        fam = next((f for f in ("Consolas", "Cascadia Mono", "DejaVu Sans Mono", "Menlo", "Courier New") if f in fams), "TkFixedFont")
        self.mono = tkfont.Font(family=fam, size=int(self.settings.get("font_size", 10)))
        self.style = ttk.Style(root)
        self.base_theme = self.style.theme_use()
        self.theme = THEMES.get(self.settings.get("theme"), THEMES["light"])
        self.dark_var = tk.BooleanVar(value=self.settings.get("theme") == "dark")
        self.alert_limiter = RateLimiter(2.0)
        self._compile_rules()

        self._build_menu()
        self._build_toolbar()
        self.nb = ttk.Notebook(root); self.nb.pack(fill="both", expand=True)
        self.nb.bind("<<NotebookTabChanged>>", lambda e: self.update_title())
        self.tabs = []
        for st in self.settings.get("tabs") or [{}]:
            self.add_tab(st)
        self._apply_style()
        self._bind_keys()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.after(300, self._autoconnect_saved)

    # ----------------------------------------------------------------- UI build
    def _build_menu(self):
        m = tk.Menu(self.root); self.root.config(menu=m)
        f = tk.Menu(m, tearoff=0); m.add_cascade(label="File", menu=f)
        f.add_command(label="New board tab", accelerator="Ctrl+T", command=self.add_tab)
        f.add_command(label="Close tab", accelerator="Ctrl+W", command=self.close_tab)
        f.add_separator()
        f.add_command(label="Save log...", accelerator="Ctrl+S", command=lambda: self.cur().save())
        f.add_command(label="Start/stop CSV logging...", accelerator="Ctrl+Shift+L", command=self.toggle_csv)
        f.add_command(label="Replay log...", command=self.replay_dialog)
        f.add_command(label="Send file...", command=self.send_file_dialog)
        f.add_separator()
        f.add_command(label="Open settings folder", command=self.open_settings_dir)
        f.add_command(label="Exit", command=self.on_close)
        v = tk.Menu(m, tearoff=0); m.add_cascade(label="View", menu=v)
        v.add_command(label="Find...", accelerator="Ctrl+F", command=lambda: self.cur().show_find())
        v.add_command(label="Clear output", accelerator="Ctrl+L", command=lambda: self.cur().clear())
        v.add_command(label="Toggle side panel (plot/hex/dashboard)", accelerator="F6", command=lambda: self.cur().toggle_side())
        v.add_command(label="Plotter", command=lambda: self.show_side(0))
        v.add_command(label="Hex view", command=lambda: self.show_side(1))
        v.add_command(label="Dashboard", command=lambda: self.show_side(2))
        v.add_separator()
        v.add_checkbutton(label="Dark mode", variable=self.dark_var, command=self.toggle_theme)
        v.add_command(label="Larger font", accelerator="Ctrl++", command=lambda: self.font_step(1))
        v.add_command(label="Smaller font", accelerator="Ctrl+-", command=lambda: self.font_step(-1))
        t = tk.Menu(m, tearoff=0); m.add_cascade(label="Tools", menu=t)
        t.add_command(label="Macros...", command=self.edit_macros)
        t.add_command(label="Highlight rules...", command=self.edit_highlights)
        t.add_command(label="Alerts...", command=self.edit_alerts)
        t.add_separator()
        t.add_command(label="Auto-detect baud", command=self.detect_baud)
        t.add_command(label="Reset board", accelerator="Ctrl+R", command=lambda: self.cur().reset_board())
        t.add_command(label="Port info...", command=self.port_info_dialog)
        t.add_command(label="Repeat send...", command=self.repeat_dialog)
        t.add_separator()
        t.add_command(label="Checksum / CRC calculator...", command=self.crc_dialog)
        t.add_command(label="ESP exception decoder...", command=self.esp_dialog)
        h = tk.Menu(m, tearoff=0); m.add_cascade(label="Help", menu=h)
        h.add_command(label="Keyboard shortcuts", command=self.shortcuts)
        h.add_command(label="About", command=lambda: messagebox.showinfo(
            "About", f"Serial Monitor {__version__}\nArduino-style serial monitor, plotter and tools.\n"
                     f"Settings: {settings_path()}", parent=self.root))

    def _build_toolbar(self):
        tb = ttk.Frame(self.root, padding=(4, 2)); tb.pack(fill="x")
        for text, cmd in [("+ Tab", self.add_tab), ("Connect", lambda: self.cur().toggle_connect()),
                          ("Reset board", lambda: self.cur().reset_board()), ("Clear", lambda: self.cur().clear()),
                          ("Find", lambda: self.cur().show_find()), ("CSV log", self.toggle_csv),
                          ("Detect baud", self.detect_baud), ("Side panel", lambda: self.cur().toggle_side()),
                          ("CRC", self.crc_dialog), ("ESP decode", self.esp_dialog)]:
            ttk.Button(tb, text=text, command=cmd).pack(side="left", padx=1)
        ttk.Checkbutton(tb, text="Dark", variable=self.dark_var, command=self.toggle_theme).pack(side="right")

    def _bind_keys(self):
        r = self.root
        r.bind_all("<Control-f>", lambda e: self.cur().show_find())
        r.bind_all("<Control-l>", lambda e: self.cur().clear())
        r.bind_all("<Control-s>", lambda e: self.cur().save())
        r.bind_all("<Control-L>", lambda e: self.toggle_csv())
        r.bind_all("<Control-t>", lambda e: self.add_tab())
        r.bind_all("<Control-w>", lambda e: self.close_tab())
        r.bind_all("<Control-r>", lambda e: self.cur().reset_board())
        r.bind_all("<F3>", lambda e: self.cur().find(1))
        r.bind_all("<Shift-F3>", lambda e: self.cur().find(-1))
        r.bind_all("<F6>", lambda e: self.cur().toggle_side())
        r.bind_all("<Control-plus>", lambda e: self.font_step(1))
        r.bind_all("<Control-equal>", lambda e: self.font_step(1))
        r.bind_all("<Control-minus>", lambda e: self.font_step(-1))

    def shortcuts(self):
        messagebox.showinfo("Shortcuts", "Ctrl+F  Find   (Enter/F3 next, Shift+Enter/Shift+F3 previous)\n"
                            "Ctrl+L  Clear output\nCtrl+S  Save log\nCtrl+Shift+L  Start/stop CSV log\n"
                            "Ctrl+T / Ctrl+W  New / close tab\nCtrl+R  Reset board\nF6  Toggle side panel\n"
                            "Ctrl + / Ctrl -  Font size\nUp/Down in send box: history\n"
                            "Plotter: wheel = X zoom, Ctrl+wheel = Y zoom", parent=self.root)

    # ----------------------------------------------------------------- tabs
    def cur(self):
        try:
            return self.nb.nametowidget(self.nb.select())
        except (KeyError, tk.TclError):
            return self.tabs[0]

    def add_tab(self, state=None):
        t = BoardTab(self.nb, self, state if isinstance(state, dict) else None)
        self.tabs.append(t)
        self.nb.add(t, text=t.title())
        self.nb.select(t)
        return t

    def close_tab(self):
        if len(self.tabs) <= 1:
            return
        t = self.cur()
        t.shutdown(); self.tabs.remove(t); self.nb.forget(t); t.destroy()

    def tab_changed(self, t):
        try:
            self.nb.tab(t, text=t.title())
        except tk.TclError:
            pass
        self.update_title()

    def update_title(self):
        t = self.cur()
        self.root.title(f"Serial Monitor {__version__} \u2013 {t.selected_port() or 'no port'} ({t.state})")

    def show_side(self, i):
        t = self.cur()
        if not t.side_shown:
            t.toggle_side()
        t.side.select(i)

    def _autoconnect_saved(self):
        for t, st in zip(self.tabs, self.settings.get("tabs") or []):
            if isinstance(st, dict) and st.get("connected") and t.selected_port():
                t.connect(quiet=True)

    # ----------------------------------------------------------------- theme
    def toggle_theme(self):
        self.settings["theme"] = "dark" if self.dark_var.get() else "light"
        self.theme = THEMES[self.settings["theme"]]
        self._apply_style()
        self.save()

    def _apply_style(self):
        t, s = self.theme, self.style
        if self.settings.get("theme") == "dark":
            s.theme_use("clam")
            s.configure(".", background=t["bg"], foreground=t["fg"], fieldbackground=t["field"], bordercolor="#555",
                        lightcolor=t["bg"], darkcolor=t["bg"], troughcolor="#3a3a3a", selectbackground=t["sel"],
                        selectforeground="#ffffff", insertcolor=t["fg"])
            s.map("TCombobox", fieldbackground=[("readonly", t["field"])], foreground=[("readonly", t["fg"])])
            s.map("TButton", background=[("active", "#3e3e3e")])
            s.map("TNotebook.Tab", background=[("selected", "#3c3c3c")])
            s.configure("TNotebook.Tab", background=t["bg"], foreground=t["fg"])
            s.configure("Treeview", background=t["text_bg"], fieldbackground=t["text_bg"], foreground=t["fg"])
            self.root.option_add("*TCombobox*Listbox.background", t["field"])
            self.root.option_add("*TCombobox*Listbox.foreground", t["fg"])
        else:
            s.theme_use(self.base_theme)   # dark overrides were stored on 'clam' only
            self.root.option_add("*TCombobox*Listbox.background", "#ffffff")
            self.root.option_add("*TCombobox*Listbox.foreground", "#000000")
        self.root.configure(bg=t["bg"])
        for tab in self.tabs:
            tab.apply_theme(t)

    def font_step(self, d):
        size = max(6, min(32, self.mono.cget("size") + d))
        self.mono.configure(size=size); self.settings["font_size"] = size

    # ----------------------------------------------------------------- rules / macros / alerts
    def _compile_rules(self):
        self._hl = []
        for r in self.settings.get("highlights", []):
            rx = compile_pattern(r.get("pattern", ""), bool(r.get("regex")), bool(r.get("case")))
            if rx:
                self._hl.append(dict(r, _rx=rx))
        self._alerts = []
        for a in self.settings.get("alerts", []):
            rx = compile_pattern(a.get("pattern", ""), bool(a.get("regex")), False)
            if rx:
                self._alerts.append(dict(a, _rx=rx))

    def highlight_rules(self):
        return self._hl

    def edit_macros(self):
        def save(items):
            self.settings["macros"] = items; self.save()
            for t in self.tabs:
                t.rebuild_macros()
        ListEditor(self.root, "Macros", self.settings.get("macros", []),
                   [("name", "Name", "str", "", None), ("command", "Command", "str", "", None),
                    ("ending", "Line ending", "choice", "Newline", list(LINE_ENDINGS)),
                    ("hex", "Hex", "bool", False, None)], save)

    def edit_highlights(self):
        def save(items):
            self.settings["highlights"] = items; self._compile_rules(); self.save()
            for t in self.tabs:
                t.rerender()
        ListEditor(self.root, "Highlight rules", self.settings.get("highlights", []),
                   [("pattern", "Word / regex", "str", "", None), ("color", "Color", "color", "#e53935", None),
                    ("regex", "Regex", "bool", False, None), ("case", "Match case", "bool", False, None)], save)

    def edit_alerts(self):
        def save(items):
            self.settings["alerts"] = items; self._compile_rules(); self.save()
        ListEditor(self.root, "Alerts (rate-limited to one per rule every 2 s)", self.settings.get("alerts", []),
                   [("pattern", "Word / regex", "str", "", None), ("regex", "Regex", "bool", False, None),
                    ("sound", "Sound", "bool", True, None), ("popup", "Popup", "bool", True, None)], save)

    def check_alerts(self, tab, line):
        for i, a in enumerate(self._alerts):
            if a["_rx"].search(line) and self.alert_limiter.allow(i):
                if a.get("sound", True):
                    self.beep()
                if a.get("popup", True):
                    self.popup(f"{tab.selected_port()}: {line[:200]}")

    def beep(self):
        try:
            if sys.platform == "win32":
                import winsound
                winsound.MessageBeep(winsound.MB_ICONEXCLAMATION)
            else:
                self.root.bell()
        except Exception:
            pass

    def popup(self, msg):
        existing = [w for w in self.root.winfo_children() if getattr(w, "_is_alert", False)]
        if len(existing) >= 3:
            existing[0].destroy()
        w = tk.Toplevel(self.root); w._is_alert = True
        w.title("Serial alert"); w.attributes("-topmost", True); w.resizable(False, False)
        ttk.Label(w, text=msg, padding=12, wraplength=420).pack()
        ttk.Button(w, text="OK", command=w.destroy).pack(pady=(0, 8))
        x = self.root.winfo_rootx() + self.root.winfo_width() - 460
        y = self.root.winfo_rooty() + 60 + 90 * len(existing)
        w.geometry(f"+{max(0, x)}+{max(0, y)}")
        w.after(6000, lambda: w.winfo_exists() and w.destroy())

    # ----------------------------------------------------------------- tools
    def toggle_csv(self):
        t = self.cur()
        if t.csv.active:
            t.csv.stop(); t.sysmsg(f"CSV logging stopped ({t.csv.rows} rows) \u2192 {t.csv.path}")
            return
        fn = filedialog.asksaveasfilename(parent=self.root, defaultextension=".csv", filetypes=[("CSV", "*.csv")],
                                          initialfile=f"serial_{time.strftime('%Y%m%d_%H%M%S')}.csv")
        if fn:
            self.start_csv(t, fn)

    def start_csv(self, t, fn):
        try:
            t.csv.start(fn); t.sysmsg(f"CSV logging to {fn}")
        except OSError as e:
            messagebox.showerror("CSV", str(e), parent=self.root)

    def detect_baud(self):
        t = self.cur()
        if t.busy:
            return
        t.detect_baud(lambda res: None)

    def port_info_dialog(self):
        t = self.cur()
        info = port_info(t.selected_port())
        w = tk.Toplevel(self.root); w.title("Port info"); w.transient(self.root)
        if not info:
            ttk.Label(w, text=f"No system info for '{t.selected_port()}'.", padding=12).pack()
        else:
            f = ttk.Frame(w, padding=12); f.pack()
            for i, (k, v) in enumerate(info.items()):
                ttk.Label(f, text=k + ":", font=("TkDefaultFont", 9, "bold")).grid(row=i, column=0, sticky="e", padx=4)
                e = ttk.Entry(f, width=50); e.insert(0, "" if v is None else str(v)); e.configure(state="readonly")
                e.grid(row=i, column=1, sticky="w", pady=1)
        ttk.Button(w, text="Close", command=w.destroy).pack(pady=6)

    def repeat_dialog(self):
        t = self.cur()
        w = tk.Toplevel(self.root); w.title("Repeat send"); w.transient(self.root)
        f = ttk.Frame(w, padding=10); f.pack()
        cmd = tk.StringVar(value=t.repeat_cfg["cmd"] or t.send_var.get())
        iv = tk.StringVar(value=str(t.repeat_cfg["interval"]))
        ttk.Label(f, text="Command:").grid(row=0, column=0, sticky="e"); ttk.Entry(f, textvariable=cmd, width=30).grid(row=0, column=1)
        ttk.Label(f, text="Every (ms):").grid(row=1, column=0, sticky="e"); ttk.Entry(f, textvariable=iv, width=10).grid(row=1, column=1, sticky="w")
        ttk.Label(f, text="Uses this tab's line ending / hex / checksum settings.").grid(row=2, column=0, columnspan=2)
        st = ttk.Label(f, text="running" if t.repeat_job else "stopped"); st.grid(row=3, column=0, columnspan=2)

        def start():
            try:
                t.start_repeat(cmd.get(), int(iv.get())); st.configure(text="running")
            except ValueError:
                st.configure(text="bad interval")
        ttk.Button(f, text="Start", command=start).grid(row=4, column=0, pady=6)
        ttk.Button(f, text="Stop", command=lambda: (t.stop_repeat(), st.configure(text="stopped"))).grid(row=4, column=1)

    def send_file_dialog(self):
        t = self.cur()
        w = tk.Toplevel(self.root); w.title("Send file"); w.transient(self.root)
        f = ttk.Frame(w, padding=10); f.pack(fill="both")
        path, chunk, delay = tk.StringVar(), tk.StringVar(value="64"), tk.StringVar(value="10")
        ttk.Label(f, text="File:").grid(row=0, column=0, sticky="e")
        ttk.Entry(f, textvariable=path, width=40).grid(row=0, column=1)
        ttk.Button(f, text="...", width=3, command=lambda: path.set(filedialog.askopenfilename(parent=w) or path.get())).grid(row=0, column=2)
        ttk.Label(f, text="Chunk bytes (0=4096):").grid(row=1, column=0, sticky="e"); ttk.Entry(f, textvariable=chunk, width=8).grid(row=1, column=1, sticky="w")
        ttk.Label(f, text="Delay between chunks (ms):").grid(row=2, column=0, sticky="e"); ttk.Entry(f, textvariable=delay, width=8).grid(row=2, column=1, sticky="w")
        pb = ttk.Progressbar(f, length=300, maximum=100); pb.grid(row=3, column=0, columnspan=3, pady=6)
        lbl = ttk.Label(f, text=""); lbl.grid(row=4, column=0, columnspan=3)

        def prog(sent, total):
            if w.winfo_exists():
                pb["value"] = 100 * sent / max(total, 1); lbl.configure(text=f"{sent:,} / {total:,} bytes")

        def done(sent, status):
            if w.winfo_exists():
                lbl.configure(text=f"Done: {status} ({sent:,} bytes)")

        def go():
            try:
                c, d = int(chunk.get()), int(delay.get())
            except ValueError:
                lbl.configure(text="bad numbers"); return
            if not os.path.isfile(path.get()):
                lbl.configure(text="choose a file"); return
            t.send_file(path.get(), c, d, prog, done)
        ttk.Button(f, text="Send", command=go).grid(row=5, column=0, pady=4)
        ttk.Button(f, text="Cancel", command=t.file_stop.set).grid(row=5, column=1)

    def replay_dialog(self):
        t = self.cur()
        fn = filedialog.askopenfilename(parent=self.root, filetypes=[("Logs", "*.txt *.log"), ("All", "*.*")])
        if not fn:
            return
        with open(fn, encoding="utf-8", errors="replace") as f:
            entries = load_log(f.read())
        has_ts = any(e[0] is not None for e in entries)
        w = tk.Toplevel(self.root); w.title("Replay"); w.transient(self.root)
        f = ttk.Frame(w, padding=10); f.pack()
        ttk.Label(f, text=f"{len(entries)} lines, " + ("timestamps found: original timing / speed" if has_ts
                                                       else "no timestamps: 20 lines/s \u00d7 speed")).pack()
        sp = tk.StringVar(value="1")
        r = ttk.Frame(f); r.pack(pady=4)
        ttk.Label(r, text="Speed \u00d7").pack(side="left")
        ttk.Combobox(r, textvariable=sp, values=["0.5", "1", "2", "5", "10", "100", "1000"], width=6).pack(side="left")

        def go():
            try:
                s = max(0.01, float(sp.get()))
            except ValueError:
                return
            t.start_replay(entries, s); w.destroy()
        ttk.Button(f, text="Start", command=go).pack(side="left", padx=4)
        ttk.Button(f, text="Stop current", command=t.replay_stop.set).pack(side="left")

    def crc_dialog(self):
        w = tk.Toplevel(self.root); w.title("Checksum / CRC calculator"); w.transient(self.root)
        f = ttk.Frame(w, padding=10); f.pack(fill="both", expand=True)
        inp, mode = tk.StringVar(value="123456789"), tk.StringVar(value="Text")
        ttk.Label(f, text="Input:").grid(row=0, column=0, sticky="e")
        e = ttk.Entry(f, textvariable=inp, width=50, font=self.mono); e.grid(row=0, column=1, columnspan=2, sticky="we")
        ttk.Radiobutton(f, text="Text (UTF-8)", variable=mode, value="Text").grid(row=1, column=1, sticky="w")
        ttk.Radiobutton(f, text="Hex bytes", variable=mode, value="Hex").grid(row=1, column=2, sticky="w")
        outs = {}
        for i, k in enumerate(CHECKSUMS):
            ttk.Label(f, text=k + ":").grid(row=2 + i, column=0, sticky="e")
            o = ttk.Entry(f, width=24, font=self.mono); o.grid(row=2 + i, column=1, sticky="w"); outs[k] = o
        err = ttk.Label(f, text="", foreground="#d32f2f"); err.grid(row=9, column=0, columnspan=3)
        ttk.Label(f, text="To append a checksum to sent data, use the '+chk' selector in a tab's send bar.").grid(row=10, column=0, columnspan=3)

        def calc(*_):
            try:
                d = parse_hex(inp.get()) if mode.get() == "Hex" else inp.get().encode()
                err.configure(text="")
            except ValueError as ex:
                err.configure(text=str(ex)); return
            for k, v in checksum_all(d).items():
                outs[k].delete(0, "end"); outs[k].insert(0, v)
        inp.trace_add("write", calc); mode.trace_add("write", calc); calc()

    def esp_dialog(self):
        t = self.cur()
        w = tk.Toplevel(self.root); w.title("ESP exception decoder"); w.transient(self.root); w.geometry("850x600")
        top = ttk.Frame(w, padding=6); top.pack(fill="x")
        elf = tk.StringVar(value=self.settings.get("esp_elf", ""))
        tool = tk.StringVar(value=self.settings.get("esp_tool", ""))
        ttk.Label(top, text="Sketch .elf:").grid(row=0, column=0, sticky="e")
        ttk.Entry(top, textvariable=elf, width=70).grid(row=0, column=1)
        ttk.Button(top, text="...", width=3, command=lambda: elf.set(filedialog.askopenfilename(
            parent=w, filetypes=[("ELF", "*.elf"), ("All", "*.*")]) or elf.get())).grid(row=0, column=2)
        ttk.Label(top, text="addr2line:").grid(row=1, column=0, sticky="e")
        tcb = ttk.Combobox(top, textvariable=tool, width=68); tcb.grid(row=1, column=1)
        ttk.Button(top, text="...", width=3, command=lambda: tool.set(filedialog.askopenfilename(parent=w) or tool.get())).grid(row=1, column=2)
        tools = find_addr2line()
        tcb["values"] = tools
        info = ttk.Label(top, text=(f"Found {len(tools)} addr2line tool(s)" if tools else
                                    "No addr2line found in: " + "; ".join(addr2line_search_roots()) +
                                    " \u2013 install the ESP32/ESP8266 core or browse manually."), wraplength=780)
        info.grid(row=2, column=0, columnspan=3, sticky="w")
        ttk.Label(w, text="Crash dump (auto-captured from output, or paste):").pack(anchor="w", padx=6)
        dump = tk.Text(w, height=12, font=self.mono); dump.pack(fill="both", expand=True, padx=6)
        dump.insert("1.0", t.last_crash)
        if not tool.get():
            tool.set(guess_tool_for_text(tools, t.last_crash) or "")
        ttk.Label(w, text="Decoded:").pack(anchor="w", padx=6)
        out = tk.Text(w, height=12, font=self.mono); out.pack(fill="both", expand=True, padx=6)

        def decode():
            text = dump.get("1.0", "end")
            addrs = extract_backtrace_addresses(text)
            out.delete("1.0", "end")
            if not addrs:
                out.insert("1.0", "No code addresses found in the dump."); return
            self.settings["esp_elf"], self.settings["esp_tool"] = elf.get(), tool.get()
            try:
                res = run_addr2line(tool.get(), elf.get(), addrs)
            except Exception as e:
                res = f"Addresses: {' '.join(addrs)}\n\nCould not decode: {e}"
            out.insert("1.0", res)
        ttk.Button(w, text="Decode", command=decode).pack(pady=6)

    def open_settings_dir(self):
        d = os.path.dirname(settings_path())
        os.makedirs(d, exist_ok=True)
        if sys.platform == "win32":
            os.startfile(d)  # noqa
        else:
            messagebox.showinfo("Settings", d, parent=self.root)

    # ----------------------------------------------------------------- lifecycle
    def save(self):
        s = self.settings
        try:
            s["geometry"] = self.root.geometry()
            s["tabs"] = [t.get_state() for t in self.tabs]
        except tk.TclError:
            pass
        try:
            save_settings(s)
        except OSError:
            pass

    def on_close(self):
        self.save()
        for t in self.tabs:
            t.shutdown()
        self.root.destroy()


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description="Serial Monitor")
    ap.add_argument("--autoexit", type=int, help="exit after N ms (testing)")
    ap.add_argument("--port", help="connect first tab to this port")
    ap.add_argument("--baud", type=int)
    ap.add_argument("--csv", help="start CSV logging to this file")
    ap.add_argument("--readout", action="append", default=[], help="add dashboard readout")
    ap.add_argument("--report", help="write a JSON status report on exit (testing)")
    a = ap.parse_args(argv)
    root = tk.Tk()
    app = App(root)
    if a.port:
        t = app.tabs[0]
        t.port_var.set(a.port)
        if a.baud:
            t.baud_var.set(str(a.baud))
        t.reset_var.set(False)
        root.after(100, t.connect)
    if a.csv:
        root.after(200, lambda: app.start_csv(app.tabs[0], a.csv))
    for r in a.readout:
        root.after(1500, lambda r=r: app.tabs[0].dashboard.add_readout(r))

    errors = []
    root.report_callback_exception = lambda et, ev, tb: (errors.append(f"{et.__name__}: {ev}"),
                                                          __import__("traceback").print_exception(et, ev, tb))
    if a.autoexit:
        def finish():
            if a.report:
                t = app.tabs[0]
                rep = {"state": t.state, "rx": t.worker.rx, "lines": len(t.lines), "series": t.plotter.labels(),
                       "plot_x": t.plotter.store.x, "csv_rows": t.csv.rows,
                       "readouts": {k: v[3].n for k, v in t.dashboard.readouts.items()},
                       "text_lines": int(t.text.index("end-1c").split(".")[0]), "errors": errors}
                with open(a.report, "w") as f:
                    json.dump(rep, f)
            app.on_close()
        root.after(a.autoexit, finish)
    root.mainloop()
