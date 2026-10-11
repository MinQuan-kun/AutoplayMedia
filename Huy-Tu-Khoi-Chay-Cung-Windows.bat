@echo off
chcp 65001 >nul
title Huy Tu Dong Khoi Chay Cung Windows
set "STARTUP_FOLDER=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"

if exist "%STARTUP_FOLDER%\SmartSignageAutostart.bat" (
    del /f /q "%STARTUP_FOLDER%\SmartSignageAutostart.bat"
    echo Đã hủy tính năng tự động khởi chạy cùng Windows thành công!
) else (
    echo Chưa có cấu hình tự khởi chạy trong Windows Startup.
)

pause
