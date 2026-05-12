@echo off
title devops-ai 文档生成服务
chcp 65001 >nul

set APP_NAME=devops-ai-bootstrap-1.0.0.jar
set APP_DIR=%~dp0
set JAR_PATH=%APP_DIR%%APP_NAME%

if not exist "%JAR_PATH%" (
    set JAR_PATH=%APP_DIR%\%APP_NAME%
)

:: 后台模式
if /i "%1"=="--daemon" goto :daemon
if /i "%1"=="-d" goto :daemon

:foreground
    call :check_env
    call :print_header
    java -Xms1g -Xmx1g ^
        -Dfile.encoding=UTF-8 ^
        -jar "%JAR_PATH%"
    if %ERRORLEVEL% neq 0 (
        echo.
        echo [错误] 服务异常退出，错误码：%ERRORLEVEL%
        echo         查看日志：%APP_DIR%logs\devops-ai.log
        pause
    )
    goto :eof

:daemon
    call :check_env
    echo 启动后台服务...
    start /B "" java -Xms1g -Xmx1g ^
        -Dfile.encoding=UTF-8 ^
        -jar "%JAR_PATH%" ^
        > "%APP_DIR%logs\console.log" 2>&1
    echo 服务已在后台运行
    echo 查看日志：%APP_DIR%logs\devops-ai.log
    echo 停止服务：taskkill /f /im java.exe （请谨慎，会终止所有 Java 进程）
    goto :eof

:check_env
    where java >nul 2>&1
    if %ERRORLEVEL% neq 0 (
        echo [错误] 未找到 Java 运行环境，请安装 JDK 8+
        pause
        exit /b 1
    )
    if not exist "%JAR_PATH%" (
        echo [错误] 未找到 JAR 包：%JAR_PATH%
        pause
        exit /b 1
    )
    if not exist "%APP_DIR%data" mkdir "%APP_DIR%data"
    if not exist "%APP_DIR%output" mkdir "%APP_DIR%output"
    if not exist "%APP_DIR%logs" mkdir "%APP_DIR%logs"
    goto :eof

:print_header
    echo =============================================
    echo   devops-ai 文档生成服务 v1.0.0
    echo   启动中...
    echo =============================================
    echo JAR: %JAR_PATH%
    echo 日志: %APP_DIR%logs\devops-ai.log
    echo 数据: %APP_DIR%data
    echo 输出: %APP_DIR%output
    echo.
    echo 访问地址：http://localhost:8070
    echo 停止服务：关闭此窗口即可（前台模式）
    echo 后台模式：start.bat --daemon
    echo =============================================
    echo.
    goto :eof
