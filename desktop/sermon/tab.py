"""One board session: port/baud/worker + output, plotter, hex, dashboard."""
import datetime
import os
import queue
import threading
import time
import tkinter as tk
from collections import deque
from tkinter import ttk, filedialog, messagebox

import serial
import serial.tools.list_ports

from .core import (BAUD_RATES, LINE_ENDINGS, MAX_LINES, CHECKSUMS, SerialWorker, CsvLogger, parse_plot_values,
                   highlight_spans, line_passes_filter, compile_pattern, looks_like_crash, detect_baud,
                   replay_schedule, timestamp)
from .panels import Plotter, HexView, Dashboard


class BoardTab(ttk.Frame):
    def __init__(self, master, app, state=None):
        super().__init__(master)
        self.app = app
        st = state or {}
        self.events = queue.Queue()
        self.worker = SerialWorker(self.events)
        self.csv = CsvLogger()
        self.lines = deque(maxlen=MAX_LINES)
        self.partial, self.partial_ts = "", None
        self.partial_open = False
        self.state = "disconnected"
        self.lost_port = None
        self.port_map = {}
        self.history, self.hist_idx = [], 0
        self.filter_rx = None
        self.repeat_job = None
        self.repeat_cfg = {"cmd": "", "interval": 1000}
        self.file_stop = threading.Event()
        self.replay_stop = threading.Event()
        self.crash_buf, self.crash_capturing, self.last_crash = [], 0, ""
        self.busy = None   # "detect" / "file" / "replay"
        font = app.mono

        # connection bar
        top = ttk.Frame(self, padding=(4, 4, 4, 0)); top.pack(fill="x")
        ttk.Label(top, text="Port:").pack(side="left")
        self.port_var = tk.StringVar(value=st.get("port", ""))
        self.port_cb = ttk.Combobox(top, textvariable=self.port_var, width=34)
        self.port_cb.pack(side="left", padx=2)
        ttk.Button(top, text="\u21bb", width=3, command=self.refresh_ports).pack(side="left")
        ttk.Label(top, text="Baud:").pack(side="left", padx=(6, 0))
        self.baud_var = tk.StringVar(value=str(st.get("baud", 9600)))
        bcb = ttk.Combobox(top, textvariable=self.baud_var, values=[str(b) for b in BAUD_RATES], width=8, state="readonly")
        bcb.pack(side="left", padx=2)
        bcb.bind("<<ComboboxSelected>>", self.on_baud_change)
        self.conn_btn = ttk.Button(top, text="Connect", command=self.toggle_connect)
        self.conn_btn.pack(side="left", padx=4)
        self.reset_var = tk.BooleanVar(value=st.get("reset", True))
        ttk.Checkbutton(top, text="Reset on connect", variable=self.reset_var).pack(side="left")
        self.reconn_var = tk.BooleanVar(value=st.get("autoreconnect", True))
        ttk.Checkbutton(top, text="Auto-reconnect", variable=self.reconn_var).pack(side="left", padx=4)
        ttk.Separator(top, orient="vertical").pack(side="left", fill="y", padx=4)
        self.dtr_var, self.rts_var = tk.BooleanVar(value=True), tk.BooleanVar(value=True)
        ttk.Checkbutton(top, text="DTR", variable=self.dtr_var, command=lambda: self._line("dtr")).pack(side="left")
        ttk.Checkbutton(top, text="RTS", variable=self.rts_var, command=lambda: self._line("rts")).pack(side="left")
        ttk.Button(top, text="Reset board", command=self.reset_board).pack(side="left", padx=4)

        # send bar
        sb = ttk.Frame(self, padding=(4, 4, 4, 0)); sb.pack(fill="x")
        self.send_var = tk.StringVar()
        self.send_entry = ttk.Entry(sb, textvariable=self.send_var, font=font)
        self.send_entry.pack(side="left", fill="x", expand=True)
        self.send_entry.bind("<Return>", lambda e: self.send())
        self.send_entry.bind("<KP_Enter>", lambda e: self.send())
        self.send_entry.bind("<Up>", lambda e: self.hist(-1))
        self.send_entry.bind("<Down>", lambda e: self.hist(1))
        self.hex_var = tk.BooleanVar(value=st.get("send_hex", False))
        ttk.Checkbutton(sb, text="Send as hex", variable=self.hex_var).pack(side="left", padx=4)
        ttk.Label(sb, text="+chk").pack(side="left")
        self.ck_var = tk.StringVar(value=st.get("checksum", "None"))
        ttk.Combobox(sb, textvariable=self.ck_var, values=["None"] + list(CHECKSUMS), width=18,
                     state="readonly").pack(side="left", padx=2)
        self.ck_text_var = tk.BooleanVar(value=st.get("checksum_text", False))
        ttk.Checkbutton(sb, text="as hex text", variable=self.ck_text_var).pack(side="left")
        self.le_var = tk.StringVar(value=st.get("line_ending", "Newline"))
        ttk.Combobox(sb, textvariable=self.le_var, values=list(LINE_ENDINGS), width=15,
                     state="readonly").pack(side="left", padx=4)
        ttk.Button(sb, text="Send", command=self.send).pack(side="left")

        # macro bar
        self.macro_bar = ttk.Frame(self, padding=(4, 2)); self.macro_bar.pack(fill="x")

        # find bar (hidden)
        self.find_bar = ttk.Frame(self, padding=(4, 2))
        ttk.Label(self.find_bar, text="Find:").pack(side="left")
        self.find_var = tk.StringVar()
        self.find_entry = ttk.Entry(self.find_bar, textvariable=self.find_var, width=30)
        self.find_entry.pack(side="left", padx=2)
        self.find_entry.bind("<Return>", lambda e: self.find(1))
        self.find_entry.bind("<Shift-Return>", lambda e: self.find(-1))
        self.find_entry.bind("<Escape>", lambda e: self.hide_find())
        self.find_var.trace_add("write", lambda *a: self.find(0))
        self.find_case = tk.BooleanVar(value=False)
        ttk.Checkbutton(self.find_bar, text="Match case", variable=self.find_case, command=lambda: self.find(0)).pack(side="left")
        self.find_regex = tk.BooleanVar(value=False)
        ttk.Checkbutton(self.find_bar, text="Regex", variable=self.find_regex, command=lambda: self.find(0)).pack(side="left")
        ttk.Button(self.find_bar, text="\u25b2 Prev", command=lambda: self.find(-1)).pack(side="left", padx=2)
        ttk.Button(self.find_bar, text="\u25bc Next", command=lambda: self.find(1)).pack(side="left")
        self.find_info = ttk.Label(self.find_bar, text=""); self.find_info.pack(side="left", padx=6)
        ttk.Button(self.find_bar, text="\u00d7", width=3, command=self.hide_find).pack(side="right")

        # filter bar
        fb = ttk.Frame(self, padding=(4, 2)); fb.pack(fill="x")
        self._fb = fb
        ttk.Label(fb, text="Filter:").pack(side="left")
        self.filter_var = tk.StringVar(value=st.get("filter", ""))
        fe = ttk.Entry(fb, textvariable=self.filter_var, width=30); fe.pack(side="left", padx=2)
        self.filter_mode = tk.StringVar(value=st.get("filter_mode", "Show matching"))
        ttk.Combobox(fb, textvariable=self.filter_mode, values=["Show matching", "Hide matching"], width=14,
                     state="readonly").pack(side="left")
        self.filter_regex = tk.BooleanVar(value=st.get("filter_regex", False))
        ttk.Checkbutton(fb, text="Regex", variable=self.filter_regex).pack(side="left", padx=4)
        for v in (self.filter_var, self.filter_mode, self.filter_regex):
            v.trace_add("write", lambda *a: self._filter_changed())
        self.csv_lbl = ttk.Label(fb, text=""); self.csv_lbl.pack(side="right")

        # main paned area
        pw = ttk.PanedWindow(self, orient="horizontal"); pw.pack(fill="both", expand=True, padx=4, pady=2)
        of = ttk.Frame(pw)
        self.text = tk.Text(of, font=font, wrap="char", state="disabled", undo=False)
        ys = ttk.Scrollbar(of, command=self.text.yview)
        self.text.configure(yscrollcommand=ys.set)
        ys.pack(side="right", fill="y"); self.text.pack(side="left", fill="both", expand=True)
        self.text.bind("<1>", lambda e: self.text.focus_set())
        pw.add(of, weight=3)
        self.side = ttk.Notebook(pw)
        self.plotter = Plotter(self.side, app.theme)
        self.hexview = HexView(self.side, font, app.theme)
        self.dashboard = Dashboard(self.side, self.plotter, app.theme)
        self.side.add(self.plotter, text="Plotter")
        self.side.add(self.hexview, text="Hex")
        self.side.add(self.dashboard, text="Dashboard")
        pw.add(self.side, weight=2)
        self.pw = pw
        self.side_shown = True
        if not st.get("side_panel", True):
            self.toggle_side()
        for r in st.get("readouts", []):
            self.dashboard.add_readout(r)

        # bottom
        bot = ttk.Frame(self, padding=(4, 2)); bot.pack(fill="x")
        self.auto_var = tk.BooleanVar(value=st.get("autoscroll", True))
        ttk.Checkbutton(bot, text="Autoscroll", variable=self.auto_var).pack(side="left")
        self.ts_var = tk.BooleanVar(value=st.get("timestamps", False))
        ttk.Checkbutton(bot, text="Show timestamp", variable=self.ts_var, command=self.rerender).pack(side="left", padx=6)
        self.status = tk.StringVar()
        ttk.Label(bot, textvariable=self.status).pack(side="left", padx=10)
        ttk.Button(bot, text="Clear output", command=self.clear).pack(side="right")
        ttk.Button(bot, text="Save log...", command=self.save).pack(side="right", padx=4)

        self.apply_theme(app.theme)
        self.rebuild_macros()
        self._filter_changed()
        self.refresh_ports()
        self.after(30, self.poll)
        self.after(2000, self.auto_refresh)
        self.after(1000, self.try_reconnect)

    # ----------------------------------------------------------------- state
    def get_state(self):
        return {"port": self.port_var.get(), "baud": int(self.baud_var.get()), "line_ending": self.le_var.get(),
                "reset": self.reset_var.get(), "autoreconnect": self.reconn_var.get(),
                "autoscroll": self.auto_var.get(), "timestamps": self.ts_var.get(), "send_hex": self.hex_var.get(),
                "checksum": self.ck_var.get(), "checksum_text": self.ck_text_var.get(),
                "filter": self.filter_var.get(), "filter_mode": self.filter_mode.get(),
                "filter_regex": self.filter_regex.get(), "side_panel": self.side_shown,
                "readouts": list(self.dashboard.readouts), "connected": self.state == "connected"}

    def title(self):
        p = self.selected_port()
        return (os.path.basename(p) if p else "New") + (" \u25cf" if self.state == "connected" else "")

    def apply_theme(self, t):
        self.text.configure(bg=t["text_bg"], fg=t["text_fg"], insertbackground=t["text_fg"],
                            selectbackground=t["sel"])
        self.text.tag_configure("sys", foreground=t["sys"])
        self.text.tag_configure("found", background=t["found"])
        self.text.tag_configure("cur", background=t["cur"])
        self.text.tag_raise("cur")
        self.plotter.apply_theme(t); self.hexview.apply_theme(t); self.dashboard.apply_theme(t)

    def toggle_side(self):
        if self.side_shown:
            self.pw.forget(self.side)
        else:
            self.pw.add(self.side, weight=2)
        self.side_shown = not self.side_shown

    # ----------------------------------------------------------------- ports
    def refresh_ports(self):
        cur = self.port_var.get()
        self.port_map, vals = {}, []
        for p in sorted(serial.tools.list_ports.comports(), key=lambda p: p.device):
            label = f"{p.device} - {p.description}" if p.description and p.description != "n/a" else p.device
            self.port_map[label] = p.device
            vals.append(label)
        if list(self.port_cb["values"]) != vals:
            self.port_cb["values"] = vals
        if not cur and vals:
            self.port_var.set(vals[0])
        elif cur and cur not in self.port_map:   # map saved bare device to its label
            for lbl, dev in self.port_map.items():
                if dev == cur:
                    self.port_var.set(lbl)

    def auto_refresh(self):
        if self.state != "connected":
            self.refresh_ports()
        self.after(2000, self.auto_refresh)

    def selected_port(self):
        v = self.port_var.get().strip()
        return self.port_map.get(v, v.split(" - ")[0].strip())

    # ----------------------------------------------------------------- connection
    def toggle_connect(self):
        if self.state in ("connected", "lost"):
            self.disconnect("Disconnected")
        else:
            self.connect()

    def connect(self, quiet=False):
        if self.busy == "detect":
            return False
        port = self.selected_port()
        if not port:
            if not quiet:
                messagebox.showwarning("Serial Monitor", "Select or type a port first.", parent=self)
            return False
        try:
            self.worker.open(port, int(self.baud_var.get()), reset=self.reset_var.get())
        except (serial.SerialException, OSError, ValueError) as e:
            if not quiet:
                self.sysmsg(f"Could not open {port}: {e}")
            return False
        self.state, self.lost_port = "connected", port
        self.dtr_var.set(True); self.rts_var.set(True)
        if not self.reset_var.get():
            self.dtr_var.set(False); self.rts_var.set(False)
        self.conn_btn.configure(text="Disconnect")
        self.sysmsg(f"Connected to {port} at {self.baud_var.get()} baud")
        self.app.tab_changed(self)
        return True

    def disconnect(self, msg=None):
        self.stop_repeat()
        self.worker.close()
        self.state = "disconnected"
        self.conn_btn.configure(text="Connect")
        if msg:
            self.sysmsg(msg)
        self.app.tab_changed(self)

    def on_baud_change(self, _e=None):
        if self.state == "connected":
            self.worker.close(); self.state = "disconnected"
            self.connect()

    def try_reconnect(self):
        if self.state == "lost" and self.reconn_var.get() and self.lost_port:
            avail = {p.device for p in serial.tools.list_ports.comports()}
            if self.lost_port in avail or os.path.exists(self.lost_port):
                if self.connect(quiet=True):
                    self.sysmsg("Reconnected")
        self.after(1000, self.try_reconnect)

    def _line(self, which):
        try:
            if which == "dtr":
                self.worker.set_dtr(self.dtr_var.get())
            else:
                self.worker.set_rts(self.rts_var.get())
        except (serial.SerialException, OSError) as e:
            self.sysmsg(f"Could not set {which.upper()}: {e}")

    def reset_board(self):
        if self.state != "connected":
            self.sysmsg("Not connected"); return
        try:
            self.worker.reset_board()
            self.dtr_var.set(True); self.rts_var.set(False)
            self.sysmsg("Board reset (DTR/RTS pulse)")
        except (serial.SerialException, OSError) as e:
            self.sysmsg(f"Reset failed: {e}")

    # ----------------------------------------------------------------- output
    def sysmsg(self, msg):
        t = self.text
        t.configure(state="normal")
        if self.partial_open:
            t.delete("partial_start", "end-1c"); self.partial_open = False
        t.insert("end-1c", f"--- {msg} ---\n", "sys")
        t.configure(state="disabled")
        if self.auto_var.get():
            t.see("end")

    def poll(self):
        new, got = [], False
        deadline = time.monotonic() + 0.03
        try:
            while time.monotonic() < deadline:
                ev = self.events.get_nowait()
                kind = ev[0]
                if kind == "data":
                    new.extend(ev[1]); self.partial, self.partial_ts = ev[2], ev[3]; got = True
                    if len(ev) > 5:
                        self.hexview.add(ev[5])
                elif kind == "lost":
                    if got:
                        self.process(new); new, got = [], False
                    self.stop_repeat()
                    self.worker.close()
                    self.state = "lost"
                    self.conn_btn.configure(text="Connect")
                    self.sysmsg(f"Connection lost: {ev[1]}" + (" (waiting to reconnect)" if self.reconn_var.get() else ""))
                    self.app.tab_changed(self)
                else:
                    self.handle_aux(ev)
        except queue.Empty:
            pass
        if got:
            self.process(new)
        self.update_status()
        self.after(15 if not self.events.empty() else 40, self.poll)

    def process(self, new):
        """Handle completed lines: store, plot, csv, alerts, crash capture, render."""
        for ts, line in new:
            self.lines.append((ts, line))
            vals = parse_plot_values(line)
            if vals:
                self.plotter.add(vals)
                self.dashboard.feed(vals)
            if self.csv.active:
                self.csv.write(ts, line, vals)
            self.app.check_alerts(self, line)
            if looks_like_crash(line):
                if not self.crash_capturing:
                    self.crash_buf = []
                self.crash_capturing = 40
            if self.crash_capturing:
                self.crash_buf.append(line)
                self.crash_capturing -= 1
                if "Backtrace:" in line or "<<<stack<<<" in line:
                    self.crash_capturing = min(self.crash_capturing, 2)
                if not self.crash_capturing:
                    self.last_crash = "\n".join(self.crash_buf)
                    self.sysmsg("Crash dump captured \u2013 Tools > ESP Exception Decoder")
        self.render(new[-MAX_LINES:])

    def _fmt(self, ts, line):
        return f"{ts} -> {line}" if self.ts_var.get() else line

    def _insert_lines(self, items):
        t = self.text
        rules = self.app.highlight_rules()
        if not rules:
            buf = "".join(self._fmt(ts, l) + "\n" for ts, l in items
                          if line_passes_filter(l, self.filter_rx, self._fmode()))
            if buf:
                t.insert("end-1c", buf)
            return
        for ts, l in items:
            if not line_passes_filter(l, self.filter_rx, self._fmode()):
                continue
            prefix = f"{ts} -> " if self.ts_var.get() else ""
            spans = highlight_spans(l, rules)
            if not spans:
                t.insert("end-1c", prefix + l + "\n"); continue
            colors = [None] * len(l)
            for s, e, c in spans:
                for i in range(s, e):
                    colors[i] = c
            t.insert("end-1c", prefix)
            i = 0
            while i < len(l):
                j = i
                while j < len(l) and colors[j] == colors[i]:
                    j += 1
                c = colors[i]
                if c:
                    tag = "hl_" + c.lstrip("#")
                    t.tag_configure(tag, foreground=c)
                    t.insert("end-1c", l[i:j], tag)
                else:
                    t.insert("end-1c", l[i:j])
                i = j
            t.insert("end-1c", "\n")

    def _fmode(self):
        return "show" if self.filter_mode.get().startswith("Show") else "hide"

    def render(self, new):
        t = self.text
        t.configure(state="normal")
        if self.partial_open:
            t.delete("partial_start", "end-1c")
        self._insert_lines(new)
        t.mark_set("partial_start", "end-1c"); t.mark_gravity("partial_start", "left")
        if self.partial and self.filter_rx is None:
            t.insert("end-1c", (f"{self.partial_ts} -> " if self.ts_var.get() else "") + self.partial)
            self.partial_open = True
        else:
            self.partial_open = False
        n = int(t.index("end-1c").split(".")[0])
        if n > MAX_LINES:
            t.delete("1.0", f"{n - MAX_LINES}.0")
        t.configure(state="disabled")
        if self.auto_var.get():
            t.see("end")

    def rerender(self):
        t = self.text
        t.configure(state="normal"); t.delete("1.0", "end"); self.partial_open = False
        self._insert_lines(list(self.lines))
        t.configure(state="disabled")
        self.render([])

    def _filter_changed(self):
        self.filter_rx = compile_pattern(self.filter_var.get(), self.filter_regex.get(), False)
        self.rerender()

    def clear(self):
        self.lines.clear()
        self.text.configure(state="normal"); self.text.delete("1.0", "end"); self.text.configure(state="disabled")
        self.partial_open = False
        self.partial = ""

    def save(self):
        fn = filedialog.asksaveasfilename(parent=self, defaultextension=".txt",
                                          filetypes=[("Text", "*.txt"), ("All", "*.*")],
                                          initialfile=f"serial_{datetime.datetime.now():%Y%m%d_%H%M%S}.txt")
        if fn:
            with open(fn, "w", encoding="utf-8", newline="") as f:
                f.write(self.text.get("1.0", "end-1c"))
            self.sysmsg(f"Saved {fn}")

    # ----------------------------------------------------------------- find
    def show_find(self):
        self.find_bar.pack(fill="x", before=self._fb)
        self.find_entry.focus_set(); self.find_entry.select_range(0, "end")

    def hide_find(self):
        self.find_bar.pack_forget()
        self.text.tag_remove("found", "1.0", "end"); self.text.tag_remove("cur", "1.0", "end")

    def find(self, direction):
        t = self.text
        pat = self.find_var.get()
        t.tag_remove("found", "1.0", "end"); t.tag_remove("cur", "1.0", "end")
        if not pat:
            self.find_info.configure(text=""); return
        kw = dict(nocase=not self.find_case.get(), regexp=self.find_regex.get())
        count, idx, n = tk.IntVar(), "1.0", 0
        try:
            while n < 5000:
                pos = t.search(pat, idx, stopindex="end", count=count, **kw)
                if not pos or count.get() == 0:
                    break
                end = f"{pos}+{count.get()}c"
                t.tag_add("found", pos, end); idx = end; n += 1
        except tk.TclError:
            self.find_info.configure(text="bad regex"); return
        if not n:
            self.find_info.configure(text="no matches"); return
        if direction == 0:
            start, back = "end", True
        else:
            cur = getattr(self, "_find_pos", None) or ("1.0" if direction > 0 else "end")
            start, back = (f"{cur}+1c", False) if direction > 0 else (cur, True)
        pos = t.search(pat, start, backwards=back, count=count, **kw) or t.search(pat, "1.0" if not back else "end", backwards=back, count=count, **kw)
        if pos:
            self._find_pos = pos
            t.tag_add("cur", pos, f"{pos}+{count.get()}c")
            t.see(pos)
            if direction:
                self.auto_var.set(False)
        self.find_info.configure(text=f"{n}{'+' if n >= 5000 else ''} matches")

    # ----------------------------------------------------------------- send
    def send_text(self, s, ending=None, as_hex=None):
        if self.state != "connected":
            self.sysmsg("Not connected"); return False
        try:
            self.worker.send_line(s, ending or self.le_var.get(), self.hex_var.get() if as_hex is None else as_hex,
                                  self.ck_var.get(), self.ck_text_var.get())
        except ValueError as e:
            self.sysmsg(f"Bad hex: {e}"); return False
        except (serial.SerialException, OSError) as e:
            self.sysmsg(f"Send failed: {e}"); return False
        return True

    def send(self):
        s = self.send_var.get()
        if self.send_text(s):
            if s and (not self.history or self.history[-1] != s):
                self.history.append(s)
            self.hist_idx = len(self.history)
            self.send_var.set("")

    def hist(self, d):
        if not self.history:
            return "break"
        self.hist_idx = max(0, min(len(self.history), self.hist_idx + d))
        self.send_var.set(self.history[self.hist_idx] if self.hist_idx < len(self.history) else "")
        self.send_entry.icursor("end")
        return "break"

    def rebuild_macros(self):
        for w in self.macro_bar.winfo_children():
            w.destroy()
        macros = self.app.settings.get("macros", [])
        if not macros:
            self.macro_bar.pack_forget() if False else None
            ttk.Label(self.macro_bar, text="Macros: (none \u2013 Tools > Macros...)", foreground="#888").pack(side="left")
            return
        ttk.Label(self.macro_bar, text="Macros:").pack(side="left")
        for m in macros:
            ttk.Button(self.macro_bar, text=m.get("name", "?"),
                       command=lambda m=m: self.send_text(m.get("command", ""), m.get("ending", "Newline"),
                                                          m.get("hex", False))).pack(side="left", padx=2)

    # repeat send
    def start_repeat(self, cmd, interval):
        self.stop_repeat()
        self.repeat_cfg = {"cmd": cmd, "interval": max(10, int(interval))}

        def tick():
            if self.state != "connected" or not self.send_text(self.repeat_cfg["cmd"]):
                self.repeat_job = None
                self.sysmsg("Repeat send stopped"); return
            self.repeat_job = self.after(self.repeat_cfg["interval"], tick)
        self.repeat_job = self.after(0, tick)

    def stop_repeat(self):
        if self.repeat_job:
            self.after_cancel(self.repeat_job); self.repeat_job = None

    # file send (thread)
    def send_file(self, path, chunk, delay_ms, progress_cb, done_cb):
        if self.state != "connected":
            self.sysmsg("Not connected"); return False
        self.file_stop.clear()
        self._file_cbs = (progress_cb, done_cb)
        worker, q, stop = self.worker, self.events, self.file_stop

        def run():
            try:
                total = os.path.getsize(path)
                sent = 0
                with open(path, "rb") as f:
                    while not stop.is_set():
                        b = f.read(chunk if chunk > 0 else 4096)
                        if not b:
                            break
                        worker.write(b)
                        sent += len(b)
                        q.put(("file_progress", sent, total))
                        if delay_ms > 0:
                            time.sleep(delay_ms / 1000)
                q.put(("file_done", sent, "cancelled" if stop.is_set() else "ok"))
            except Exception as e:
                q.put(("file_done", 0, f"error: {e}"))
        threading.Thread(target=run, daemon=True).start()
        return True

    # replay (thread)
    def start_replay(self, entries, speed):
        self.replay_stop.set()
        self.replay_stop = stop = threading.Event()
        q = self.events
        self.sysmsg(f"Replaying {len(entries)} lines at {speed:g}x")

        def run():
            batch, acc = [], 0.0
            for d, txt in replay_schedule(entries, speed):
                if stop.is_set():
                    break
                acc += d
                if acc >= 0.01:
                    if batch:
                        q.put(("data", batch, "", None, 0, b"")); batch = []
                    stop.wait(acc); acc = 0.0
                batch.append((timestamp(), txt))
                if len(batch) >= 500:
                    q.put(("data", batch, "", None, 0, b"")); batch = []
            if batch:
                q.put(("data", batch, "", None, 0, b""))
            q.put(("replay_done", stop.is_set()))
        threading.Thread(target=run, daemon=True).start()

    # baud detect (thread)
    def detect_baud(self, done_cb):
        port = self.selected_port()
        if not port:
            self.sysmsg("Select a port first"); return
        was = self.state == "connected"
        if was:
            self.disconnect()
        self.busy = "detect"
        self.sysmsg(f"Detecting baud on {port}...")
        q = self.events
        self._detect_cb = (done_cb, was)

        def run():
            res = detect_baud(port, reset=self.reset_var.get(),
                              progress=lambda i, n, r: q.put(("detect_progress", i, n, r)))
            q.put(("detect_done", res))
        threading.Thread(target=run, daemon=True).start()

    def handle_aux(self, ev):
        kind = ev[0]
        if kind == "file_progress":
            self._file_cbs[0](ev[1], ev[2])
        elif kind == "file_done":
            self._file_cbs[1](ev[1], ev[2])
            self.sysmsg(f"File send {ev[2]} ({ev[1]} bytes)")
        elif kind == "replay_done":
            self.sysmsg("Replay " + ("stopped" if ev[1] else "finished"))
        elif kind == "detect_progress":
            self.status.set(f"Detecting... trying {ev[3]} ({ev[1] + 1}/{ev[2]})")
        elif kind == "detect_done":
            self.busy = None
            res = ev[1]
            cb, was = self._detect_cb
            if res and res[0][0] >= 0.5:
                best = res[0][1]
                self.baud_var.set(str(best))
                self.sysmsg(f"Detected baud: {best} (score {res[0][0]:.2f})")
            else:
                self.sysmsg("Baud detection inconclusive (no readable data)")
            cb(res)
            if was:
                self.connect()

    def update_status(self):
        st = {"connected": "Connected", "disconnected": "Disconnected", "lost": "Connection lost"}[self.state]
        if self.busy == "detect":
            return
        self.status.set(f"{st} | {self.worker.port if self.state != 'disconnected' else self.selected_port() or '-'}"
                        f" @ {self.baud_var.get()} | RX {self.worker.rx:,} B | TX {self.worker.tx:,} B")
        self.csv_lbl.configure(text=f"CSV: {os.path.basename(self.csv.path)} ({self.csv.rows} rows)" if self.csv.active else "")

    def shutdown(self):
        self.stop_repeat(); self.file_stop.set(); self.replay_stop.set()
        self.csv.stop()
        self.worker.close()
