@echo off
cd /d "%~dp0"
python -m pip install -r requirements.txt || goto :err
python -m PyInstaller --onefile --windowed --name SerialMonitor serial_monitor.py || goto :err
echo.
echo Built dist\SerialMonitor.exe
pause
exit /b 0
:err
echo Build failed.
pause
exit /b 1
