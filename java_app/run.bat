@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java JDK (javac) not found in PATH.
    echo Please install JDK 17+ or 21+ from https://adoptium.net
    pause
    exit /b 1
)

if not exist "out" mkdir out

echo [NexusVault] Compiling Java sources...
set "SOURCES="
for /r "src" %%f in (*.java) do (
    set "SOURCES=!SOURCES! "%%f""
)
javac -d out %SOURCES%
if errorlevel 1 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

set WEB_PORT=%1
if "%WEB_PORT%"=="" set WEB_PORT=8090
set DB_PORT=%2
if "%DB_PORT%"=="" set DB_PORT=8088
set DB_FILE=%3
if "%DB_FILE%"=="" set DB_FILE=nexusvault.db

echo [NexusVault] Launching Web UI at http://127.0.0.1:%WEB_PORT% ...
start "" http://127.0.0.1:%WEB_PORT%
java -cp out com.nexusvault.Main %WEB_PORT% %DB_PORT% %DB_FILE%
pause
