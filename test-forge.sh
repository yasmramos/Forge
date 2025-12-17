#!/bin/bash

# Test script for Forge Build System
# This script compiles Forge and runs it against the demo project

echo "🚀 Forge Build System Test Script"
echo "=================================="

# Navigate to Forge directory
cd /workspace/Forge

# Compile Forge (we need to do this manually since Maven is not available)
echo "📦 Compiling Forge Build System..."

# Create classpath for compilation
CLASSPATH="target/classes"
if [ -d "lib" ]; then
    for jar in lib/*.jar; do
        CLASSPATH="$CLASSPATH:$jar"
    done
fi

# Compile Java files
javac -cp "$CLASSPATH" -d target/classes \
    src/main/java/io/github/yasmramos/forge/*.java \
    src/main/java/io/github/yasmramos/forge/**/*.java

if [ $? -eq 0 ]; then
    echo "✅ Forge compiled successfully!"
else
    echo "❌ Forge compilation failed!"
    exit 1
fi

# Test against demo project
echo ""
echo "🧪 Testing Forge against demo project..."
echo "========================================"

cd /workspace/demo-project

# Run Forge on the demo project
echo "Running Forge build on demo project..."
java -cp "/arget/classes"workspace/Forge/t io.github.yasmramos.forge.Forge

echo ""
echo "🎉 Test complete!"
echo ""
echo "To manually test:"
echo "1. cd /workspace/Forge"
echo "2. java -cp target/classes io.github.yasmramos.forge.Forge"