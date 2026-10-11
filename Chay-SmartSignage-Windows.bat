@echo off
title Smart Signage Player (Windows Desktop)
cd /d "%~dp0"

if not defined JAVA_HOME (
    if exist "F:\Androi_app\jbr\bin\java.exe" (
        set "JAVA_HOME=F:\Androi_app\jbr"
    )
)

echo Dang khoi dong Smart Signage Player tren Windows...
call "desktop\build\install\desktop\bin\desktop.bat"
