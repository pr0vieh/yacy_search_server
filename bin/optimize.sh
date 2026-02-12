#!/bin/bash
#
# YaCy BLOB Optimizer - Offline Defragmentation and Deduplication Tool
# For optimizing RWI (text.index*.blob) files without running YaCy
#
# Usage:
#   ./optimize.sh [OPTIONS]
#
# Options:
#   --index-dir DIR           (required) Path to BLOB index directory
#   --blob-pattern PATTERN    (optional) BLOB filename pattern (default: text.index*.blob)
#   --max-file-size SIZE      (optional) Max output file size in bytes (default: 2GB)
#   --output-dir DIR          (optional) Output directory (default: same as index-dir)
#   --help                    Show this help message
#
# Example:
#   ./optimize.sh --index-dir ./DATA/INDEX/freeworld/SEGMENTS/default
#
# Requirements:
#   - Java 21+ installed and in PATH
#   - YaCy compiled (yacycore.jar present in lib/)
#

set -e

# Get script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
YACY_DIR="$(dirname "$SCRIPT_DIR")"
LIBDIR="$YACY_DIR/lib"
CLASSPATH="$LIBDIR/yacycore.jar"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Print with color
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# Check Java
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java not found. Please install Java 21+ and add it to PATH"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | grep -oP '(?<=version ")[^"]+' | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 21 ]; then
        print_warn "Java version $JAVA_VERSION detected. YaCy recommends Java 21+"
    fi
}

# Check yacycore.jar
check_jar() {
    if [ ! -f "$CLASSPATH" ]; then
        print_error "yacycore.jar not found in $LIBDIR"
        print_error "Please run: ant (to compile YaCy)"
        exit 1
    fi
    print_info "Using: $CLASSPATH"
}

# Show help
show_help() {
    head -n 35 "$0" | tail -n 34
}

# Main
main() {
    if [ $# -eq 0 ]; then
        show_help
        exit 0
    fi
    
    # Check for help flag
    for arg in "$@"; do
        if [ "$arg" == "--help" ] || [ "$arg" == "-h" ]; then
            show_help
            exit 0
        fi
    done
    
    # Validate environment
    check_java
    check_jar
    
    # Build command
    JAVA_CMD="java -Xms1024m -Xmx8192m -cp $CLASSPATH net.yacy.tools.BlobOptimizer"
    
    print_info "Starting YaCy BLOB Optimizer..."
    print_info "Command: $JAVA_CMD $@"
    echo ""
    
    # Execute
    if $JAVA_CMD "$@"; then
        print_info "Optimization completed successfully!"
        exit 0
    else
        print_error "Optimization failed!"
        exit 1
    fi
}

# Run main
main "$@"
