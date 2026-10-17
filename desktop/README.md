# Serial Monitor

Arduino-IDE-style serial monitor + plotter + tools for Windows (Python, tkinter, pyserial).

## Run from source
    python -m pip install pyserial
    python serial_monitor.py

## Build the Windows .exe
Run `build.bat` -> `dist\SerialMonitor.exe` (single file, no console).

## Layout
- `serial_monitor.py` - entry point (PyInstaller script)
- `sermon/core.py` - serial worker, parsing, CRCs, CSV, replay, filters, ESP decoder, settings (no GUI)
- `sermon/panels.py` - plotter, hex view, dashboard
- `sermon/tab.py` - one board session tab
- `sermon/app.py` - main window, menus, dialogs

## Features
Tabs per board (Ctrl+T / Ctrl+W); port list with auto-refresh; Arduino baud list; reset-on-connect
(DTR pulse) option; DTR/RTS toggles, Reset board, Port info; auto-reconnect after unplug; auto-detect baud.
Output with timestamps, autoscroll, 10,000-line buffer, highlight rules, show/hide filter (regex),
find (Ctrl+F, F3/Shift+F3), Save log (Ctrl+S), Clear (Ctrl+L), CSV logging (Ctrl+Shift+L).
Side panel (F6): Plotter (`1 2 3`, `a:1,b:2`; legend show/hide, window, auto/fixed Y, pause,
wheel = X zoom, Ctrl+wheel = Y zoom), Hex view, Dashboard readouts (min/max/avg).
Send: line ending, history, send as hex (`0A FF 1B`), append checksum, macros, repeat timer, send file.
Tools: CRC calculator (SUM8, XOR, CRC-8, CRC-16/MODBUS, CRC-16/CCITT-FALSE, CRC-32), ESP exception
decoder (finds addr2line under `%LOCALAPPDATA%\Arduino15\packages`), alerts (sound/popup), session replay.
Dark mode. Settings: `%APPDATA%\SerialMonitor\settings.json`.
