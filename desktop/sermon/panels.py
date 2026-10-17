"""Plotter, hex view and dashboard panels (tkinter only)."""
import time
import tkinter as tk
from tkinter import ttk

from .core import SeriesStore, RunningStats, hexdump_row

PALETTE = ["#1f77b4", "#d62728", "#2ca02c", "#ff7f0e", "#9467bd", "#8c564b", "#e377c2",
           "#17becf", "#bcbd22", "#7f7f7f"]


class Plotter(ttk.Frame):
    FPS = 25

    def __init__(self, master, theme):
        super().__init__(master)
        self.theme = theme
        self.store = SeriesStore(500)
        self.visible = {}
        self.paused = False
        self.dirty = False
        self.xzoom = 1.0
        self.yzoom = 1.0
        self._legend_built = []

        bar = ttk.Frame(self); bar.pack(fill="x", pady=2)
        ttk.Label(bar, text="Window:").pack(side="left")
        self.win_var = tk.StringVar(value="500")
        sp = ttk.Spinbox(bar, from_=50, to=20000, increment=50, textvariable=self.win_var, width=7,
                         command=self._apply_window)
        sp.pack(side="left"); sp.bind("<Return>", lambda e: self._apply_window())
        self.auto_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(bar, text="Auto Y", variable=self.auto_var, command=self.mark).pack(side="left", padx=6)
        ttk.Label(bar, text="Min").pack(side="left")
        self.ymin_var = tk.StringVar(value="0")
        ttk.Entry(bar, textvariable=self.ymin_var, width=7).pack(side="left")
        ttk.Label(bar, text="Max").pack(side="left")
        self.ymax_var = tk.StringVar(value="1023")
        ttk.Entry(bar, textvariable=self.ymax_var, width=7).pack(side="left")
        self.pause_btn = ttk.Button(bar, text="Pause", width=7, command=self.toggle_pause)
        self.pause_btn.pack(side="left", padx=6)
        ttk.Button(bar, text="+", width=3, command=lambda: self.zoom(0.8)).pack(side="left")
        ttk.Button(bar, text="-", width=3, command=lambda: self.zoom(1.25)).pack(side="left")
        ttk.Button(bar, text="Reset zoom", command=self.reset_zoom).pack(side="left", padx=2)
        ttk.Button(bar, text="Clear", command=self.clear).pack(side="left", padx=2)

        self.legend = ttk.Frame(self); self.legend.pack(fill="x")
        self.canvas = tk.Canvas(self, highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)
        self.canvas.bind("<Configure>", lambda e: self.mark())
        self.canvas.bind("<MouseWheel>", self._wheel)
        self.canvas.bind("<Button-4>", lambda e: self._wheel(e, 120))
        self.canvas.bind("<Button-5>", lambda e: self._wheel(e, -120))
        self.apply_theme(theme)
        self.after(1000 // self.FPS, self._tick)

    # data
    def add(self, values):
        if self.paused or not values:
            return
        self.store.add(values)
        self.dirty = True

    def mark(self):
        self.dirty = True

    def clear(self):
        self.store.clear(); self.visible.clear(); self._rebuild_legend(); self.mark()

    def labels(self):
        return list(self.store.order)

    def color(self, label):
        try:
            return PALETTE[self.store.order.index(label) % len(PALETTE)]
        except ValueError:
            return PALETTE[0]

    def _apply_window(self):
        try:
            n = max(10, int(self.win_var.get()))
        except ValueError:
            return
        self.store.set_window(n); self.mark()

    def toggle_pause(self):
        self.paused = not self.paused
        self.pause_btn.configure(text="Resume" if self.paused else "Pause")

    def zoom(self, f, axis="x"):
        if axis == "x":
            self.xzoom = min(1.0, max(0.02, self.xzoom * f))
        else:
            self.yzoom = min(50, max(0.02, self.yzoom * f))
        self.mark()

    def reset_zoom(self):
        self.xzoom = self.yzoom = 1.0; self.mark()

    def _wheel(self, e, delta=None):
        d = delta if delta is not None else e.delta
        f = 0.8 if d > 0 else 1.25
        self.zoom(f, "y" if (e.state & 0x4) else "x")  # Ctrl+wheel = Y zoom

    def apply_theme(self, theme):
        self.theme = theme
        self.canvas.configure(bg=theme["plot_bg"]); self.mark()

    def _rebuild_legend(self):
        for w in self.legend.winfo_children():
            w.destroy()
        for label in self.store.order:
            var = tk.BooleanVar(value=self.visible.get(label, True))
            self.visible.setdefault(label, True)
            f = ttk.Frame(self.legend); f.pack(side="left", padx=4)
            tk.Label(f, text="\u25a0", fg=self.color(label), bg=self.theme["bg"]).pack(side="left")
            ttk.Checkbutton(f, text=label, variable=var,
                            command=lambda l=label, v=var: (self.visible.__setitem__(l, v.get()), self.mark())).pack(side="left")
        self._legend_built = list(self.store.order)

    def _tick(self):
        try:
            if self._legend_built != self.store.order:
                self._rebuild_legend()
            if self.dirty and self.winfo_ismapped():
                self.dirty = False
                self.redraw()
        finally:
            self.after(1000 // self.FPS, self._tick)

    def redraw(self):
        c = self.canvas
        c.delete("all")
        w, h = c.winfo_width(), c.winfo_height()
        if w < 50 or h < 50:
            return
        L, R, T, B = 60, 10, 10, 25
        pw, ph = w - L - R, h - T - B
        xmax = self.store.x
        span = max(10, int(self.store.window * self.xzoom))
        xmin = xmax - span
        vis = [l for l in self.store.order if self.visible.get(l, True)]
        pts = {l: [(x, y) for x, y in self.store.series[l] if x > xmin] for l in vis}
        if self.auto_var.get():
            ys = [y for p in pts.values() for _, y in p]
            if ys:
                lo, hi = min(ys), max(ys)
            else:
                lo, hi = 0.0, 1.0
            if hi - lo < 1e-9:
                lo, hi = lo - 1, hi + 1
            pad = (hi - lo) * 0.05
            lo, hi = lo - pad, hi + pad
        else:
            try:
                lo, hi = float(self.ymin_var.get()), float(self.ymax_var.get())
            except ValueError:
                lo, hi = 0.0, 1.0
            if hi <= lo:
                hi = lo + 1
        mid, half = (lo + hi) / 2, (hi - lo) / 2 * self.yzoom
        lo, hi = mid - half, mid + half
        fg, grid = self.theme["fg"], self.theme["grid"]
        for i in range(6):
            yv = lo + (hi - lo) * i / 5
            yp = T + ph - ph * i / 5
            c.create_line(L, yp, L + pw, yp, fill=grid)
            c.create_text(L - 4, yp, text=f"{yv:.4g}", anchor="e", fill=fg, font=("TkDefaultFont", 8))
        for i in range(6):
            xv = xmin + span * i / 5
            xp = L + pw * i / 5
            c.create_line(xp, T, xp, T + ph, fill=grid)
            c.create_text(xp, T + ph + 4, text=str(int(xv)), anchor="n", fill=fg, font=("TkDefaultFont", 8))
        c.create_rectangle(L, T, L + pw, T + ph, outline=fg)
        sx, sy = pw / span, ph / (hi - lo)
        for l, p in pts.items():
            if len(p) < 2:
                continue
            step = max(1, len(p) // (pw * 2))
            p = p[::step]
            coords = []
            for x, y in p:
                yy = T + ph - (y - lo) * sy
                coords += (L + (x - xmin) * sx, min(max(yy, T - 2), T + ph + 2))
            c.create_line(*coords, fill=self.color(l), width=1.5)
        if self.paused:
            c.create_text(L + pw - 5, T + 5, text="PAUSED", anchor="ne", fill="#d62728")


class HexView(ttk.Frame):
    MAX_ROWS = 5000

    def __init__(self, master, font, theme):
        super().__init__(master)
        bar = ttk.Frame(self); bar.pack(fill="x")
        self.enabled = tk.BooleanVar(value=True)
        ttk.Checkbutton(bar, text="Capture", variable=self.enabled).pack(side="left")
        ttk.Button(bar, text="Clear", command=self.clear).pack(side="left", padx=4)
        self.text = tk.Text(self, font=font, wrap="none", state="disabled")
        ys = ttk.Scrollbar(self, command=self.text.yview)
        self.text.configure(yscrollcommand=ys.set)
        ys.pack(side="right", fill="y"); self.text.pack(fill="both", expand=True)
        self.offset = 0
        self.pending = bytearray()
        self.queue = bytearray()
        self.apply_theme(theme)
        self.after(100, self._tick)

    def apply_theme(self, t):
        self.text.configure(bg=t["text_bg"], fg=t["text_fg"], insertbackground=t["text_fg"])

    def add(self, data):
        if self.enabled.get() and data:
            self.queue += data
            if len(self.queue) > 16 * self.MAX_ROWS:   # drop oldest if UI can't keep up
                drop = len(self.queue) - 16 * self.MAX_ROWS
                drop -= drop % 16
                del self.queue[:drop]; self.offset += drop

    def clear(self):
        self.text.configure(state="normal"); self.text.delete("1.0", "end"); self.text.configure(state="disabled")
        self.offset, self.pending, self.queue = 0, bytearray(), bytearray()

    def _tick(self):
        try:
            if self.queue:
                self._render()
        finally:
            self.after(100, self._tick)

    def _render(self):
        t = self.text
        data = self.pending + self.queue
        self.queue = bytearray()
        t.configure(state="normal")
        if self.pending:
            t.delete("hp", "end-1c")
        full = len(data) - len(data) % 16
        rows = [hexdump_row(self.offset + i, data[i:i + 16]) for i in range(0, full, 16)]
        self.offset += full
        self.pending = bytearray(data[full:])
        if rows:
            t.insert("end-1c", "\n".join(rows) + "\n")
        t.mark_set("hp", "end-1c"); t.mark_gravity("hp", "left")
        if self.pending:
            t.insert("end-1c", hexdump_row(self.offset, self.pending) + "\n")
        n = int(t.index("end-1c").split(".")[0])
        if n > self.MAX_ROWS:
            t.delete("1.0", f"{n - self.MAX_ROWS}.0")
        t.configure(state="disabled")
        t.see("end")


class Dashboard(ttk.Frame):
    def __init__(self, master, plotter, theme):
        super().__init__(master)
        self.plotter = plotter
        self.theme = theme
        self.readouts = {}   # label -> (frame, value_lbl, stats_lbl, RunningStats)
        bar = ttk.Frame(self); bar.pack(fill="x", pady=2)
        ttk.Label(bar, text="Series:").pack(side="left")
        self.sel = ttk.Combobox(bar, width=24, postcommand=self._fill)
        self.sel.pack(side="left", padx=2)
        ttk.Button(bar, text="Add readout", command=lambda: self.add_readout(self.sel.get())).pack(side="left")
        ttk.Button(bar, text="Reset stats", command=self.reset_stats).pack(side="left", padx=4)
        self.grid_frame = ttk.Frame(self); self.grid_frame.pack(fill="both", expand=True)
        self.after(200, self._tick)

    def _fill(self):
        self.sel["values"] = self.plotter.labels()

    def resolve(self, key):
        """Accept a label or a 1-based series index."""
        labels = self.plotter.labels()
        if key in labels:
            return key
        if key.isdigit() and 1 <= int(key) <= len(labels):
            return labels[int(key) - 1]
        return key

    def add_readout(self, key):
        key = (key or "").strip()
        if not key:
            return
        label = self.resolve(key)
        if label in self.readouts:
            return
        f = tk.Frame(self.grid_frame, bd=1, relief="groove", padx=10, pady=6, bg=self.theme["bg"])
        i = len(self.readouts)
        f.grid(row=i // 3, column=i % 3, sticky="nsew", padx=6, pady=6)
        self.grid_frame.columnconfigure(i % 3, weight=1)
        hdr = tk.Frame(f, bg=self.theme["bg"]); hdr.pack(fill="x")
        tk.Label(hdr, text=label, font=("TkDefaultFont", 10, "bold"), bg=self.theme["bg"],
                 fg=self.plotter.color(label)).pack(side="left")
        tk.Button(hdr, text="\u00d7", relief="flat", command=lambda: self.remove(label), bg=self.theme["bg"],
                  fg=self.theme["fg"]).pack(side="right")
        v = tk.Label(f, text="--", font=("TkDefaultFont", 28, "bold"), bg=self.theme["bg"], fg=self.theme["fg"])
        v.pack()
        s = tk.Label(f, text="", bg=self.theme["bg"], fg=self.theme["fg"]); s.pack()
        self.readouts[label] = (f, v, s, RunningStats())

    def remove(self, label):
        r = self.readouts.pop(label, None)
        if r:
            r[0].destroy()
            for i, (f, *_rest) in enumerate(self.readouts.values()):
                f.grid(row=i // 3, column=i % 3)

    def reset_stats(self):
        for r in self.readouts.values():
            r[3].reset()

    def feed(self, values):
        if not self.readouts:
            return
        for label, v in values:
            r = self.readouts.get(label)
            if r:
                r[3].add(v)

    def _tick(self):
        try:
            for key in [k for k in self.readouts if k.isdigit()]:   # index given before series existed
                lbl = self.resolve(key)
                if lbl != key and lbl not in self.readouts:
                    self.remove(key); self.add_readout(lbl)
            for label, (f, v, s, st) in self.readouts.items():
                if st.n:
                    v.configure(text=f"{st.last:.6g}")
                    s.configure(text=f"min {st.min:.4g}   max {st.max:.4g}   avg {st.avg:.4g}   n={st.n}")
        finally:
            self.after(200, self._tick)

    def apply_theme(self, t):
        self.theme = t
        labels = list(self.readouts)
        for l in labels:
            self.remove(l)
        for l in labels:
            self.add_readout(l)
