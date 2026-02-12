@echo off
REM YaCy BLOB Optimizer - Offline Defragmentation and Deduplication Tool
REM For optimizing RWI (text.index*.blob) files without running YaCy
REM
REM Usage:
REM   optimize.bat [OPTIONS]
REM
REM Options:
REM   --index-dir DIR           (required) Path to BLOB index directory
REM   --blob-pattern PATTERN    (optional) BLOB filename pattern (default: text.index*.blob)
REM   --max-file-size SIZE      (optional) Max output file size in bytes (default: 2GB)
REM   --output-dir DIR          (optional) Output directory (default: same as index-dir)
REM   --help                    Show this help message
REM
REM Example:
REM   optimize.bat --index-dir .\DATA\INDEX\freeworld\SEGMENTS\default
REM
REM Requirements:
REM   - Java 21+ installed and in PATH
REM   - YaCy compiled (yacycore.jar present in lib\)
REM

setlocal enabledelayedexpansion

REM Get directory
set YACY_DIR=%~dp0..
set LIBDIR=%YACY_DIR%\lib
set CLASSPATH=%LIBDIR%\yacycore.jar

echo.
echo ============================================================
echo    YaCy BLOB Optimizer - Offline Defragmenter
echo ============================================================
echo.

REM Check for help
for %%A in (%*) do (
    if "%%A"=="--help" goto :show_help
    if "%%A"=="-h" goto :show_help
)

REM Check Java
echo [INFO] Checking Java installation...
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Please install Java 21+ and add it to PATH
    pause
    exit /b 1
)

REM Check jar
if not exist "%CLASSPATH%" (
    echo [ERROR] yacycore.jar not found in %LIBDIR%
    echo [ERROR] Please run: ant (to compile YaCy)
    pause
    exit /b 1
)

echo [INFO] Using: %CLASSPATH%
echo.

REM Execute
echo [INFO] Starting YaCy BLOB Optimizer...
echo.

java -Xms1024m -Xmx8192m -cp "%CLASSPATH%" net.yacy.tools.BlobOptimizer %*

if errorlevel 1 (
    echo.
    echo [ERROR] Optimization failed!
    pause
    exit /b 1
)

echo.
echo [INFO] Optimization completed successfully!
goto :end

:show_help
echo Usage:
echo   optimize.bat [OPTIONS]
echo.
echo Options:
echo   --index-dir DIR           Required: Path to BLOB index directory
echo   --blob-pattern PATTERN    Optional: BLOB filename pattern (default: text.index*.blob)
echo   --max-file-size SIZE      Optional: Max output file size in bytes (default: 2GB)
echo   --output-dir DIR          Optional: Output directory (default: same as index-dir)
echo   --help                    Show this help message
echo.
echo Example:
echo   optimize.bat --index-dir .\DATA\INDEX\freeworld\SEGMENTS\default
echo.
echo Requirements:
echo   - Java 21+ installed and in PATH
echo   - YaCy compiled (yacycore.jar present in lib\)
echo.

:end
pause
