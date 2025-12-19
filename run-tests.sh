#!/bin/bash

echo "🧪 Forge Build System - Comprehensive Test Suite"
echo "==============================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    local color=$1
    local message=$2
    echo -e "${color}${message}${NC}"
}

print_status $BLUE "📋 Test Categories:"
echo "  1. Unit Tests (core components)"
echo "  2. Integration Tests (full workflow)"
echo "  3. Performance Tests (benchmarking)"
echo "  4. Validation Tests (demo project)"
echo ""

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    print_status $YELLOW "⚠️  Maven not found. Attempting to compile tests manually..."
    
    # Create test classpath
    CLASSPATH="src/main/java:."
    
    # Add JAR dependencies if they exist
    if [ -d "lib" ]; then
        for jar in lib/*.jar; do
            if [ -f "$jar" ]; then
                CLASSPATH="$CLASSPATH:$jar"
            fi
        done
    fi
    
    print_status $BLUE "🔨 Compiling test classes..."
    
    # Compile test files
    javac -cp "$CLASSPATH" -d target/test-classes \
        src/test/java/io/github/yasmramos/forge/**/*.java
    
    if [ $? -eq 0 ]; then
        print_status $GREEN "✅ Test compilation successful!"
    else
        print_status $RED "❌ Test compilation failed!"
        exit 1
    fi
    
    print_status $BLUE "🧪 Running test runner..."
    
    # Run the test runner
    java -cp "target/classes:target/test-classes:lib/*" \
        io.github.yasmramos.forge.ForgeTestRunner
    
else
    print_status $BLUE "🚀 Running tests with Maven..."
    
    # Run tests with Maven
    mvn test
    
    if [ $? -eq 0 ]; then
        print_status $GREEN "✅ All Maven tests passed!"
    else
        print_status $RED "❌ Some Maven tests failed!"
        exit 1
    fi
fi

echo ""
print_status $BLUE "📊 Test Execution Summary"
echo "========================"

# Display test results if available
if [ -d "target/surefire-reports" ]; then
    print_status $GREEN "📋 Test reports available in: target/surefire-reports/"
    
    # Show summary if possible
    if [ -f "target/surefire-reports/*.txt" ]; then
        echo ""
        print_status $BLUE "📈 Recent test results:"
        for report in target/surefire-reports/*.txt; do
            if [ -f "$report" ]; then
                echo "  - $(basename "$report")"
            fi
        done
    fi
fi

# Run specific test categories if Maven is available
if command -v mvn &> /dev/null; then
    echo ""
    print_status $BLUE "🔍 Running specific test categories..."
    
    echo ""
    print_status $YELLOW "Running Unit Tests..."
    mvn test -Dtest="*Test" -DfailIfNoTests=false
    
    echo ""
    print_status $YELLOW "Running Integration Tests..."
    mvn test -Dtest="*IntegrationTest" -DfailIfNoTests=false
    
    echo ""
    print_status $YELLOW "Running Performance Tests..."
    mvn test -Dtest="*PerformanceTest" -DfailIfNoTests=false
    
    echo ""
    print_status $YELLOW "Running Validation Tests..."
    mvn test -Dtest="*ValidationTest" -DfailIfNoTests=false
fi

echo ""
print_status $GREEN "🎉 Test suite execution completed!"
echo ""
print_status $BLUE "📝 Next Steps:"
echo "  1. Review test reports in target/surefire-reports/"
echo "  2. Check coverage reports if available"
echo "  3. Run performance benchmarks"
echo "  4. Validate with real projects"