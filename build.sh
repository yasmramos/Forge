#!/bin/bash

# Forge Build System - Build Script

echo "🔨 Building Forge Build System..."

# Clean previous builds
echo "🧹 Cleaning previous builds..."
rm -rf target/

# Compile with Maven
echo "⚡ Compiling with Maven..."
mvn clean compile

if [ $? -eq 0 ]; then
    echo "✅ Compilation successful!"
    
    # Create fat JAR
    echo "📦 Creating executable JAR..."
    mvn package
    
    if [ $? -eq 0 ]; then
        echo "🎉 Forge Build System built successfully!"
        echo ""
        echo "To run Forge:"
        echo "  java -jar target/forge-build-system-1.0.0-SNAPSHOT.jar [command]"
        echo ""
        echo "Commands:"
        echo "  forge build           - Build the project"
        echo "  forge build incremental - Incremental build"
        echo "  forge clean           - Clean build artifacts"
        echo "  forge init            - Initialize new project"
        echo "  forge info            - Show system info"
        echo "  forge help            - Show help"
        echo ""
    else
        echo "❌ JAR packaging failed!"
        exit 1
    fi
else
    echo "❌ Compilation failed!"
    exit 1
fi