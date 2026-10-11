#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    char exePath[MAX_PATH];
    GetModuleFileNameA(NULL, exePath, MAX_PATH);
    char *lastSlash = strrchr(exePath, '\\');
    if (lastSlash) {
        *(lastSlash + 1) = '\0';
    } else {
        strcpy(exePath, ".\\");
    }

    char javaExe[MAX_PATH] = "";
    char candidate[MAX_PATH];

    // 1. Kiểm tra runtime đóng gói đi kèm thư mục ứng dụng
    snprintf(candidate, sizeof(candidate), "%sruntime\\bin\\javaw.exe", exePath);
    if (GetFileAttributesA(candidate) != INVALID_FILE_ATTRIBUTES) {
        strncpy(javaExe, candidate, sizeof(javaExe));
    }
    // 2. Kiểm tra đường dẫn JBR trên máy
    else if (GetFileAttributesA("F:\\Androi_app\\jbr\\bin\\javaw.exe") != INVALID_FILE_ATTRIBUTES) {
        strncpy(javaExe, "F:\\Androi_app\\jbr\\bin\\javaw.exe", sizeof(javaExe));
    }
    // 3. Kiểm tra biến môi trường JAVA_HOME
    else {
        const char *javaHome = getenv("JAVA_HOME");
        if (javaHome && strlen(javaHome) > 0) {
            snprintf(candidate, sizeof(candidate), "%s\\bin\\javaw.exe", javaHome);
            if (GetFileAttributesA(candidate) != INVALID_FILE_ATTRIBUTES) {
                strncpy(javaExe, candidate, sizeof(javaExe));
            }
        }
    }

    // 4. Kiểm tra java trong PATH hệ thống
    if (javaExe[0] == '\0') {
        if (SearchPathA(NULL, "javaw.exe", NULL, MAX_PATH, candidate, NULL) > 0) {
            strncpy(javaExe, candidate, sizeof(javaExe));
        } else {
            strncpy(javaExe, "javaw.exe", sizeof(javaExe));
        }
    }

    // Xây dựng câu lệnh thực thi JVM
    char cmdLine[8192];
    snprintf(cmdLine, sizeof(cmdLine),
        "\"%s\" --add-modules javafx.controls,javafx.media -Dfile.encoding=UTF-8 -classpath \"%slib\\*\" com.signage.desktop.MainApplicationKt %s",
        javaExe, exePath, lpCmdLine);

    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    if (!CreateProcessA(NULL, cmdLine, NULL, NULL, FALSE, 0, NULL, exePath, &si, &pi)) {
        char msg[1024];
        snprintf(msg, sizeof(msg),
            "Không thể khởi động Smart Signage Player!\n\n"
            "Máy tính cần có Java 21 Runtime (JRE/JDK 21) để thực thi.\n"
            "Đường dẫn đã thử: %s", javaExe);
        MessageBoxA(NULL, msg, "Smart Signage - Thông báo", MB_ICONERROR | MB_OK);
        return 1;
    }

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return 0;
}
