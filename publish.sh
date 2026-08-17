#!/bin/bash
# Local Release and Publish Script for Genvex Assistant
# This script compiles, packages, and pushes your changes, including the built app.jar,
# to GitHub directly, bypassing the need for GitHub Actions/workflows.

# Exit immediately if a command exits with a non-zero status
set -e

echo "=== Packaging Genvex Assistant locally ==="

# Set paths to use our installed JDK and Maven
export PATH="$HOME/.jdk/jdk-17.0.18/jdk-17.0.18+8/Contents/Home/bin:$HOME/.maven/maven-3.10.0-rc-1/bin:$PATH"

# 1. Compile and build the shadow/fat JAR
echo "Building project with Maven..."
mvn clean package

# 2. Extract version from ha_addon/config.json
if ! command -v jq &> /dev/null; then
    echo "Error: jq is required to parse config.json. Please install jq (e.g., brew install jq)."
    exit 1
fi

VERSION=$(jq -r '.version' ha_addon/config.json)
echo "Detected Version: v$VERSION"

# 3. Copy the compiled JAR into the ha_addon directory
echo "Copying target/genvex-integration-${VERSION}.jar to ha_addon/app.jar..."
cp "target/genvex-integration-${VERSION}.jar" ha_addon/app.jar

# 4. Git operations
echo "Staging files for commit..."
git add ADDRESS_MAP.md README.md demo_image.png "Screenshot 2026-08-17 at 22.28.32.png" publish.sh pom.xml src/ \
    ha_addon/app.jar ha_addon/config.json ha_addon/run.sh \
    ha_addon/README.md ha_addon/CHANGELOG.md

# Prompt user to confirm and commit
echo "Checking git status..."
git status --short

read -p "Do you want to commit these changes and push to GitHub? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    # Create commit
    git commit -m "Build and release package v${VERSION} locally" || echo "No changes to commit"
    
    # Push to main
    echo "Pushing to GitHub..."
    git push origin main
    
    # Optionally tag the release locally and push the tag
    read -p "Do you want to create and push git tag v${VERSION}? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        # Delete tag if it already exists locally and remotely to overwrite
        git tag -d "v${VERSION}" 2>/dev/null || true
        git push --delete origin "v${VERSION}" 2>/dev/null || true
        
        git tag -a "v${VERSION}" -m "Release v${VERSION}"
        git push origin "v${VERSION}"
        echo "Tag v${VERSION} pushed successfully!"
    fi
    
    echo "=== Publish Complete! ==="
else
    echo "Release cancelled. Changes are staged but not committed/pushed."
fi
