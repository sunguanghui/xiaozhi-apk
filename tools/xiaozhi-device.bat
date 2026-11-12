@echo off
rem XiaoZhi box control tool. Put app-debug.apk next to this file.
rem Double-click: opens the interactive menu (asks for the box IP first).
rem Command line: xiaozhi-device.bat start [-Device 192.168.1.20]
rem Commands: install / start / stop / boot-on / boot-off / status / uninstall
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0xiaozhi-device.ps1" %*
