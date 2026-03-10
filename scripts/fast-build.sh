#!/bin/bash
# Fast build script for development - skips slow analysis tasks
# Usage: ./scripts/fast-build.sh [test|compile|check]

set -e

cd "$(dirname "$0")/.."

MODE="${1:-compile}"

case "$MODE" in
  compile)
    # Fastest: just compile, no tests or analysis
    echo "⚡ Fast compile (no tests, no analysis)..."
    ./gradlew classes testClasses -x detekt -x koverVerify --parallel
    ;;
  test)
    # Run tests without analysis
    echo "🧪 Running tests (no analysis)..."
    ./gradlew test -x detekt -x koverVerify --parallel
    ;;
  check)
    # Compile + tests, skip code coverage
    echo "✅ Quick check (compile + tests, no coverage)..."
    ./gradlew check -x koverVerify --parallel
    ;;
  full)
    # Full build (same as ./gradlew build)
    echo "🔨 Full build..."
    ./gradlew build --parallel
    ;;
  *)
    echo "Usage: $0 [compile|test|check|full]"
    echo "  compile - Just compile (fastest)"
    echo "  test    - Compile + run tests"
    echo "  check   - Compile + tests + detekt (no coverage)"
    echo "  full    - Full build with coverage"
    exit 1
    ;;
esac

echo "✨ Done!"
