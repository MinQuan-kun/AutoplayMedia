@echo off
chcp 65001 >nul
title Kich hoat Tu Dong Khoi Chay Cung Windows
cd /d "%~dp0"

set "STARTUP_FOLDER=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "TARGET_BAT=%~dp0Chay-SmartSignage-Windows.bat"
set "TARGET_VBS=%~dp0Chay-SmartSignage-An-Cua-So.vbs"

echo Dang cai dat che do Kiosk tu dong khoi chay cung Windows...

:: Tao file batch kich hoat trong thu muc Startup
(
echo @echo off
echo timeout /t 3 /nobreak ^>nul
echo wscript.exe "%TARGET_VBS%"
) > "%STARTUP_FOLDER%\SmartSignageAutostart.bat"

if exist "%STARTUP_FOLDER%\SmartSignageAutostart.bat" (
    echo ========================================================
    echo THÀNH CÔNG: Đã đăng ký tự động bật cùng Windows!
    echo Mỗi khi bật máy tính, phần mềm sẽ tự mở toàn màn hình.
    echo ========================================================
) else (
    echo [LỖI] Không thể tạo tệp khởi động trong: %STARTUP_FOLDER%
)

pause
