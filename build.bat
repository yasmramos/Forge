@echo off
REM Forge Build System - Build Script for Windows

echo 🔨 Building Forge Build System...

REM Clean previous builds
echo 🧹 Cleaning previous builds...
if exist target rmdir /s /q target

REM Compile with Maven
echo ⚡ Compiling with Maven...
mvn clean compile

if %ERRORLEVEL% EQU 0 (
    echo ✅ Compilation successful!
    
    REM Create fat JAR
    echo 📦 Creating executable JAR...
    mvn package
    
    if %ERRORLEVEL% EQU 0 (
        echo 🎉 Forge Build System built successfully!
        echo.
        echo To run Forge:
        echo   java -jar target\forge-build-system-1.0.0-SNAPSHOT.jar [command]
        echo.
        echo Commands:
        echo   forge build           - Build the project
        echo   forge build incremental - Incremental build
        echo   forge clean           - Clean build artifacts
        echo   forge init            - Initialize new project
        echo   forge info            - Show system info
        echo   forge help            - Show help
        echo.
    ) else (
        echo ❌ JAR packaging failed!
        exit /b 1
    )
) else (
    echo ❌ Compilation failed!
    exit /b 1
)