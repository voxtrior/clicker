@echo off
chcp 65001 >nul
echo ========================================================
echo   自动点击器高级版 (com.xundao.clicker) 一键编译脚本
echo ========================================================

where gradle >nul 2>nul
if %errorlevel% equ 0 (
    echo [1/2] 检测到系统 Gradle，开始编译 Debug APK...
    call gradle assembleDebug
) else if exist gradlew.bat (
    echo [1/2] 使用 Gradle Wrapper 编译 Debug APK...
    call gradlew.bat assembleDebug
) else (
    echo [提示] 未检测到命令行 gradle，请直接使用 Android Studio 打开本文件夹，点击菜单 Build -^> Build Bundle(s) / APK(s) -^> Build APK(s) 即可！
    pause
    exit /b 1
)

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "com.xundao.clicker_debug.apk" >nul
    echo [2/2] 编译成功！已输出安装文件：com.xundao.clicker_debug.apk
) else (
    echo [错误] 编译未生成 APK，请检查 JDK 17 与 ANDROID_HOME 环境变量配置。
)
pause
