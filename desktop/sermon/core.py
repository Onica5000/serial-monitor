"""GUI-independent logic: serial worker, parsing, checksums, logging, replay, filters, ESP decoding, settings."""
import codecs
import csv
import datetime
import glob
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import zlib
from collections import deque

import serial
import serial.tools.list_ports

BAUD_RATES = [300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 74880, 115200,
              230400, 250000, 500000, 1000000, 2000000]
LINE_ENDINGS = {
    "No line ending": b"",
    "Newline": b"\n",
    "Carriage return": b"\r",
    "Both NL & CR": b"\r\n",
}
MAX_LINES = 10000


def timestamp():
    return datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]


# --------------------------------------------------------------------------- line assembly
class LineAssembler:
    """Incremental UTF-8 decoder + splitter treating \\r\\n, \\r and \\n as one line break each."""

    def __init__(self):
        self._dec = codecs.getincrementaldecoder("utf-8")(errors="replace")
        self.partial = ""
        self.partial_ts = None
        self._pending_cr = False

    def feed(self, data, now=None):
        text = self._dec.decode(data)
        out = []
        for ch in text:
            if self._pending_cr:
                self._pending_cr = False
                if ch == "\n":
                    continue
            if ch == "\r" or ch == "\n":
                out.append((self.partial_ts or now or timestamp(), self.partial))
                self.partial, self.partial_ts = "", None
                self._pending_cr = ch == "\r"
            else:
                if not self.partial:
                    self.partial_ts = now or timestamp()
                self.partial += ch
        return out


# --------------------------------------------------------------------------- serial worker
class SerialWorker:
    """Owns one serial port + reader thread. Talks to the GUI only via the `events` queue.

    Events: ("data", [(ts, line), ...], partial, partial_ts, nbytes, raw_bytes)
            ("lost", message)
    Other producers (file sender, replay, baud detection) post their own events to the same queue.
    """

    def __init__(self, events=None):
        self.events = events if events is not None else queue.Queue()
        self.ser = None
        self._thread = None
        self._stop = threading.Event()
        self.rx = 0
        self.tx = 0
        self.port = None
        self.baud = None
        self._lock = threading.Lock()

    @property
    def is_open(self):
        return self.ser is not None and self.ser.is_open

    def open(self, port, baud, reset=True):
        self.close()
        s = serial.Serial()
        s.port, s.baudrate, s.timeout = port, baud, 0.05
        s.write_timeout = 2
        if not reset:
            s.dtr = False   # set before open so the driver never asserts them
            s.rts = False
        s.open()
        if reset:
            self._pulse(s)
        try:
            s.reset_input_buffer()
        except (serial.SerialException, OSError):
            pass
        self.ser, self.port, self.baud = s, port, baud
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, args=(s,), daemon=True)
        self._thread.start()

    @staticmethod
    def _pulse(s):
        try:
            s.dtr = False
            s.rts = False
            time.sleep(0.1)
            s.dtr = True
            s.rts = True
        except (serial.SerialException, OSError):
            pass

    def reset_board(self):
        """Pulse DTR/RTS low->high (Arduino-style auto reset). ESP boards: EN via RTS."""
        if not self.is_open:
            raise serial.SerialException("port not open")
        s = self.ser
        try:
            s.dtr = False
            s.rts = True     # ESP: EN low
            time.sleep(0.1)
            s.rts = False
            s.dtr = True     # AVR: DTR edge resets via cap
            time.sleep(0.05)
            s.dtr = False
            s.dtr = True
        except (serial.SerialException, OSError) as e:
            raise serial.SerialException(str(e))

    def set_dtr(self, value):
        if self.is_open:
            self.ser.dtr = bool(value)

    def set_rts(self, value):
        if self.is_open:
            self.ser.rts = bool(value)

    def _run(self, s):
        asm = LineAssembler()
        try:
            while not self._stop.is_set():
                n = s.in_waiting or 1
                data = s.read(min(n, 65536))
                if not data:
                    continue
                self.rx += len(data)
                lines = asm.feed(data)
                self.events.put(("data", lines, asm.partial, asm.partial_ts, len(data), data))
        except (serial.SerialException, OSError, TypeError, AttributeError) as e:
            if not self._stop.is_set():
                self.events.put(("lost", str(e) or "device disconnected"))
                try:
                    s.close()
                except Exception:
                    pass

    def write(self, data):
        if not self.is_open:
            raise serial.SerialException("port not open")
        with self._lock:
            n = self.ser.write(data)
            self.ser.flush()
        self.tx += n or 0
        return n

    def send_line(self, text, ending="Newline", as_hex=False, checksum=None, checksum_as_text=False):
        self.write(build_payload(text, ending, as_hex, checksum, checksum_as_text))

    def close(self):
        self._stop.set()
        s, self.ser = self.ser, None
        if s is not None:
            try:
                s.close()
            except Exception:
                pass
        if self._thread is not None and self._thread is not threading.current_thread():
            self._thread.join(timeout=1.0)
        self._thread = None


def build_payload(text, ending="Newline", as_hex=False, checksum=None, checksum_as_text=False):
    body = parse_hex(text) if as_hex else text.encode("utf-8")
    if checksum and checksum != "None":
        ck = checksum_bytes(body, checksum)
        body += ck.hex().upper().encode() if checksum_as_text else ck
    return body + LINE_ENDINGS[ending]


def port_info(device):
    for p in serial.tools.list_ports.comports():
        if p.device == device:
            return {
                "device": p.device, "description": p.description, "manufacturer": p.manufacturer,
                "product": p.product, "vid:pid": f"{p.vid:04X}:{p.pid:04X}" if p.vid is not None else None,
                "serial_number": p.serial_number, "hwid": p.hwid, "location": p.location,
                "interface": p.interface,
            }
    return None


# --------------------------------------------------------------------------- hex
_HEX_TOKEN = re.compile(r"(?:0x)?([0-9a-fA-F]+)")


def parse_hex(s):
    """'0A FF 1B', '0x0a,0xff', '0AFF1B', '0a-ff' -> bytes. Raises ValueError."""
    s = s.strip()
    if not s:
        return b""
    out = bytearray()
    for tok in re.split(r"[\s,;:\-]+", s):
        if not tok:
            continue
        if tok[:2].lower() == "0x":
            tok = tok[2:]
        if not tok or not re.fullmatch(r"[0-9a-fA-F]+", tok):
            raise ValueError(f"invalid hex token: {tok!r}")
        if len(tok) % 2:
            if len(tok) == 1:
                tok = "0" + tok
            else:
                raise ValueError(f"odd-length hex token: {tok!r}")
        out += bytes.fromhex(tok)
    return bytes(out)


def hexdump_row(offset, chunk):
    hx = " ".join(f"{b:02X}" for b in chunk)
    hx = hx[:23] + " " + hx[23:] if len(chunk) > 8 else hx
    asc = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
    return f"{offset:08X}  {hx:<48}  |{asc}|"


def hexdump(data, offset=0):
    return [hexdump_row(offset + i, data[i:i + 16]) for i in range(0, len(data), 16)]


# --------------------------------------------------------------------------- checksums
def sum8(d):
    return sum(d) & 0xFF


def xor8(d):
    x = 0
    for b in d:
        x ^= b
    return x


def crc8(d, poly=0x07, init=0x00):
    c = init
    for b in d:
        c ^= b
        for _ in range(8):
            c = ((c << 1) ^ poly) & 0xFF if c & 0x80 else (c << 1) & 0xFF
    return c


def crc16_modbus(d):
    c = 0xFFFF
    for b in d:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return c


def crc16_ccitt_false(d):
    c = 0xFFFF
    for b in d:
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x1021) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    return c


def crc32(d):
    return zlib.crc32(d) & 0xFFFFFFFF


# name -> (func, nbytes, byteorder used when appending)
CHECKSUMS = {
    "SUM8": (sum8, 1, "big"),
    "XOR": (xor8, 1, "big"),
    "CRC-8": (crc8, 1, "big"),
    "CRC-16/MODBUS": (crc16_modbus, 2, "little"),   # Modbus RTU sends low byte first
    "CRC-16/CCITT-FALSE": (crc16_ccitt_false, 2, "big"),
    "CRC-32": (crc32, 4, "little"),
}


def checksum_all(d):
    return {k: f"0x{f(d):0{n * 2}X}" for k, (f, n, _) in CHECKSUMS.items()}


def checksum_bytes(d, name):
    f, n, order = CHECKSUMS[name]
    return f(d).to_bytes(n, order)


# --------------------------------------------------------------------------- plot parsing
_NUM = re.compile(r"^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$")


def parse_plot_values(line):
    """Parse a line the way the Arduino IDE 2 plotter does.

    Fields separated by comma/space/tab. A field 'label:value' (or 'label=value') is named;
    a bare number gets the label 'value N' where N is its 1-based field position.
    Non-numeric fields are ignored. Returns list of (label, float).
    """
    out = []
    line = line.strip()
    if not line:
        return out
    # Normalise ' : ' spacing so 'temp: 23' works
    line = re.sub(r"\s*([:=])\s*", r"\1", line)
    idx = 0
    for field in re.split(r"[,\t ;]+", line):
        if not field:
            continue
        idx += 1
        m = re.match(r"^(.+?)[:=](.+)$", field)
        if m and _NUM.match(m.group(2)):
            out.append((m.group(1), float(m.group(2))))
        elif _NUM.match(field):
            out.append((f"value {idx}", float(field)))
    return out


class SeriesStore:
    """Rolling plot data: label -> deque[(x, y)], x is a global sample counter."""

    def __init__(self, window=500):
        self.window = window
        self.series = {}
        self.order = []
        self.x = 0

    def add(self, values):
        if not values:
            return
        self.x += 1
        for label, v in values:
            d = self.series.get(label)
            if d is None:
                d = self.series[label] = deque(maxlen=max(self.window, 10))
                self.order.append(label)
            d.append((self.x, v))

    def set_window(self, n):
        self.window = n
        for k, d in self.series.items():
            self.series[k] = deque(d, maxlen=max(n, 10))

    def clear(self):
        self.series.clear(); self.order.clear(); self.x = 0


class RunningStats:
    def __init__(self):
        self.reset()

    def reset(self):
        self.n, self.total, self.min, self.max, self.last = 0, 0.0, None, None, None

    def add(self, v):
        self.n += 1; self.total += v; self.last = v
        self.min = v if self.min is None else min(self.min, v)
        self.max = v if self.max is None else max(self.max, v)

    @property
    def avg(self):
        return self.total / self.n if self.n else None


# --------------------------------------------------------------------------- baud detection
def score_readability(data):
    """0..1 score: printable ASCII ratio, bonus for line breaks, penalty if nothing received."""
    if not data:
        return 0.0
    printable = sum(1 for b in data if 32 <= b < 127 or b in (9, 10, 13))
    ratio = printable / len(data)
    nl = data.count(b"\n") + data.count(b"\r")
    nl_bonus = 0.15 if nl and len(data) / max(nl, 1) < 200 else 0.0
    size_factor = min(1.0, len(data) / 16)  # a couple of stray bytes isn't convincing
    zero_pen = 0.3 * (data.count(0) / len(data))
    return max(0.0, min(1.0, (ratio * 0.85 + nl_bonus - zero_pen) * size_factor))


DETECT_RATES = [9600, 115200, 57600, 19200, 38400, 74880, 230400, 4800, 250000, 500000, 2400, 1200]


def detect_baud(port, rates=None, window=0.6, reset=False, opener=None, progress=None, stop=None):
    """Try each rate, read for `window` seconds, score. Returns list of (score, rate, nbytes) best first."""
    rates = rates or DETECT_RATES
    opener = opener or serial.Serial
    results = []
    for i, r in enumerate(rates):
        if stop is not None and stop.is_set():
            break
        if progress:
            progress(i, len(rates), r)
        try:
            s = opener()
            s.port, s.baudrate, s.timeout = port, r, 0.05
            if not reset:
                s.dtr = False; s.rts = False
            s.open()
            try:
                s.reset_input_buffer()
                buf = bytearray()
                end = time.monotonic() + window
                while time.monotonic() < end:
                    buf += s.read(4096)
            finally:
                s.close()
        except (serial.SerialException, OSError):
            results.append((0.0, r, 0))
            continue
        results.append((score_readability(bytes(buf)), r, len(buf)))
    results.sort(key=lambda t: (-t[0], -t[2]))
    return results


# --------------------------------------------------------------------------- CSV logging
class CsvLogger:
    """timestamp, raw, <numeric columns...>. New labels add columns; header rewritten on stop."""

    def __init__(self):
        self.path = None
        self.f = None
        self.writer = None
        self.columns = []
        self._header_cols = []
        self.rows = 0

    @property
    def active(self):
        return self.f is not None

    def start(self, path):
        self.stop()
        self.path = path
        self.f = open(path, "w", newline="", encoding="utf-8")
        self.writer = csv.writer(self.f)
        self.columns, self.rows = [], 0
        self._header_written = False

    def write(self, ts, line, values):
        if not self.f:
            return
        if not self._header_written:
            for label, _ in values:
                if label not in self.columns:
                    self.columns.append(label)
            self.writer.writerow(["timestamp", "raw"] + self.columns)
            self._header_cols = list(self.columns)
            self._header_written = True
        else:
            for label, _ in values:
                if label not in self.columns:
                    self.columns.append(label)
        d = dict(values)
        self.writer.writerow([ts, line] + [("%g" % d[c]) if c in d else "" for c in self.columns])
        self.rows += 1

    def stop(self):
        if not self.f:
            return
        self.f.close()
        self.f = None
        if self.columns != self._header_cols and self.path:
            with open(self.path, newline="", encoding="utf-8") as f:
                rows = list(csv.reader(f))
            if rows:
                rows[0] = ["timestamp", "raw"] + self.columns
            with open(self.path, "w", newline="", encoding="utf-8") as f:
                csv.writer(f).writerows(rows)


# --------------------------------------------------------------------------- replay
_TS_LINE = re.compile(r"^(\d{1,2}):(\d{2}):(\d{2})\.(\d{3}) -> ?(.*)$")


def parse_log_line(line):
    """'HH:MM:SS.mmm -> text' -> (seconds, text); otherwise (None, line). '--- sys ---' -> None."""
    line = line.rstrip("\r\n")
    if line.startswith("--- ") and line.endswith(" ---"):
        return None
    m = _TS_LINE.match(line)
    if m:
        h, mi, s, ms, text = m.groups()
        return int(h) * 3600 + int(mi) * 60 + int(s) + int(ms) / 1000.0, text
    return None, line


def load_log(text):
    """Returns list of (relative_seconds_or_None, text). Handles midnight wrap."""
    out, base, prev, offset = [], None, None, 0.0
    for raw in text.splitlines():
        r = parse_log_line(raw)
        if r is None:
            continue
        t, txt = r
        if t is not None:
            if prev is not None and t + offset < prev - 1:
                offset += 86400
            t += offset
            if base is None:
                base = t
            prev = t
            out.append((t - base, txt))
        else:
            out.append((None, txt))
    return out


def replay_schedule(entries, speed=1.0, line_interval=0.05):
    """Yield (delay_before_seconds, text). Timestamped logs use original timing / speed."""
    prev = None
    for t, txt in entries:
        if t is None:
            d = line_interval / speed
        else:
            d = 0.0 if prev is None else max(0.0, (t - prev) / speed)
            prev = t
        yield d, txt


# --------------------------------------------------------------------------- highlight / filter
def compile_pattern(pattern, regex=False, case=False):
    if not pattern:
        return None
    flags = 0 if case else re.IGNORECASE
    try:
        return re.compile(pattern if regex else re.escape(pattern), flags)
    except re.error:
        return None


def highlight_spans(line, rules):
    """rules: list of dicts {pattern, color, regex, case, compiled?}. Returns [(start, end, color)]."""
    spans = []
    for r in rules:
        rx = r.get("_rx") or compile_pattern(r.get("pattern", ""), r.get("regex", False), r.get("case", False))
        if rx is None:
            continue
        for m in rx.finditer(line):
            if m.end() > m.start():
                spans.append((m.start(), m.end(), r.get("color", "#ff0000")))
    return spans


def line_passes_filter(line, rx, mode="show"):
    if rx is None:
        return True
    hit = rx.search(line) is not None
    return hit if mode == "show" else not hit


class RateLimiter:
    def __init__(self, interval=2.0):
        self.interval = interval
        self.last = {}

    def allow(self, key, now=None):
        now = time.monotonic() if now is None else now
        if now - self.last.get(key, -1e9) >= self.interval:
            self.last[key] = now
            return True
        return False


# --------------------------------------------------------------------------- ESP decoder
_BT = re.compile(r"Backtrace:\s*(.*)", re.I)
_REG = re.compile(r"\b(PC|MEPC|RA|EPC\d|EXCVADDR|epc\d)\s*[:=]\s*(0x[0-9a-fA-F]{8})")
_STACK8266 = re.compile(r"^\s*[0-9a-fA-F]{8}:\s+((?:[0-9a-fA-F]{8}\s*)+)")


def is_code_addr(a):
    return 0x40000000 <= a < 0x50000000 or 0x42000000 <= a < 0x44000000


def extract_backtrace_addresses(text):
    """Find code addresses in an ESP32/ESP8266/RISC-V crash dump. Ordered, deduplicated."""
    found = []

    def add(a):
        if is_code_addr(a) and a not in found:
            found.append(a)

    for line in text.splitlines():
        for name, val in _REG.findall(line):
            if name.upper() != "EXCVADDR":
                add(int(val, 16))
        m = _BT.search(line)
        if m:
            for pc in re.findall(r"(0x[0-9a-fA-F]{8})(?::0x[0-9a-fA-F]{8})?", m.group(1)):
                add(int(pc, 16))
        m = _STACK8266.match(line)
        if m:
            for w in m.group(1).split():
                add(int(w, 16))
    return ["0x%08x" % a for a in found]


def looks_like_crash(line):
    return bool(re.search(r"Backtrace:|Guru Meditation|Exception \(\d+\)|>>>stack>>>|abort\(\) was called|MEPC\s*:", line))


ADDR2LINE_NAMES = ["xtensa-esp32-elf-addr2line", "xtensa-esp32s2-elf-addr2line", "xtensa-esp32s3-elf-addr2line",
                   "xtensa-esp-elf-addr2line", "xtensa-lx106-elf-addr2line", "riscv32-esp-elf-addr2line"]


def addr2line_search_roots():
    roots = []
    if sys.platform == "win32":
        la = os.environ.get("LOCALAPPDATA")
        if la:
            roots.append(os.path.join(la, "Arduino15", "packages"))
        up = os.environ.get("USERPROFILE")
        if up:
            roots.append(os.path.join(up, ".platformio", "packages"))
    else:
        home = os.path.expanduser("~")
        roots += [os.path.join(home, ".arduino15", "packages"),
                  os.path.join(home, "Library", "Arduino15", "packages"),
                  os.path.join(home, ".platformio", "packages")]
    return roots


def find_addr2line(roots=None):
    roots = roots if roots is not None else addr2line_search_roots()
    found = []
    ext = ".exe" if sys.platform == "win32" else ""
    for root in roots:
        if not os.path.isdir(root):
            continue
        for name in ADDR2LINE_NAMES:
            for p in glob.glob(os.path.join(root, "**", "bin", name + ext), recursive=True):
                if p not in found:
                    found.append(p)
    for name in ADDR2LINE_NAMES:
        p = shutil.which(name)
        if p and p not in found:
            found.append(p)
    return found


def guess_tool_for_text(tools, text):
    """Prefer riscv tool for RISC-V dumps (MEPC), lx106 for ESP8266 dumps."""
    want = "riscv" if "MEPC" in text else ("lx106" if (">>>stack>>>" in text or "Exception (" in text) else "esp32")
    for t in tools:
        if want in os.path.basename(t):
            return t
    return tools[0] if tools else None


def run_addr2line(tool, elf, addrs, timeout=20):
    if not tool or not os.path.isfile(tool):
        raise FileNotFoundError("addr2line tool not found: %s" % tool)
    if not elf or not os.path.isfile(elf):
        raise FileNotFoundError("ELF file not found: %s" % elf)
    if not addrs:
        return "No code addresses found."
    kw = {}
    if sys.platform == "win32":
        kw["creationflags"] = 0x08000000  # CREATE_NO_WINDOW
    r = subprocess.run([tool, "-pfiaC", "-e", elf] + list(addrs), capture_output=True, text=True,
                       timeout=timeout, **kw)
    return (r.stdout + ("\n" + r.stderr if r.stderr.strip() else "")).strip()


# --------------------------------------------------------------------------- settings
def config_dir():
    if sys.platform == "win32":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif sys.platform == "darwin":
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "SerialMonitor")


DEFAULT_SETTINGS = {
    "geometry": "1200x750", "theme": "light", "macros": [], "highlights": [], "alerts": [],
    "tabs": [{}], "font_size": 10,
}


def settings_path():
    return os.environ.get("SERIALMONITOR_CONFIG") or os.path.join(config_dir(), "settings.json")


def load_settings(path=None):
    path = path or settings_path()
    s = json.loads(json.dumps(DEFAULT_SETTINGS))
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            s.update(data)
    except (OSError, ValueError):
        pass
    return s


def save_settings(s, path=None):
    path = path or settings_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(s, f, indent=2)
    os.replace(tmp, path)
