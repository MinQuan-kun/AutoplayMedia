Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "E:\1.Important\Kotlin"
WshShell.Run "cmd /c Chay-SmartSignage-Windows.bat", 0, False
