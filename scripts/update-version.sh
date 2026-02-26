#!/bin/bash

# Script to update plugin version across all files
# Usage: ./scripts/update-version.sh <new-version>

set -e

if [ $# -eq 0 ]; then
    echo "Usage: $0 <new-version>"
    echo "Example: $0 0.1.1"
    exit 1
fi

NEW_VERSION="$1"

# Validate version format (semver)
if ! [[ $NEW_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]]; then
    echo "Error: Version must be in semver format (e.g., 0.1.1)"
    exit 1
fi

echo "Updating plugin version to $NEW_VERSION..."

# Update gradle.properties
sed -i.bak "s/^pluginVersion = .*/pluginVersion = $NEW_VERSION/" gradle.properties

# Update CHANGELOG.md
if [ -f "CHANGELOG.md" ]; then
    TODAY=$(date +%Y-%m-%d)
    sed -i.bak "/^# Changelog/a\\
\\
## [$NEW_VERSION] - $TODAY\\
\\
### Added\\
- \\
\\
### Changed\\
- \\
\\
### Fixed\\
- \\
" CHANGELOG.md
fi

# Clean up backups
rm -f gradle.properties.bak
rm -f CHANGELOG.md.bak 2>/dev/null || true

echo "Version updated successfully to $NEW_VERSION"
