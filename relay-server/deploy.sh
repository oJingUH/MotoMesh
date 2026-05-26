#!/bin/bash
# MotoMesh Relay — Deploy to Fly.io
# Run this from the relay-server/ directory
set -e

echo "=== MotoMesh Relay Deploy ==="
echo ""

# Check if flyctl is installed
if ! which flyctl >/dev/null 2>&1; then
    echo "Installing flyctl..."
    curl -fsSL https://fly.io/install.sh | sh
    export FLYCTL_INSTALL="$HOME/.fly"
    export PATH="$FLYCTL_INSTALL/bin:$PATH"
fi

# Check auth
if ! flyctl auth whoami >/dev/null 2>&1; then
    echo "Logging in to Fly.io..."
    echo "  (Opens a browser — sign in with GitHub/Google)"
    flyctl auth login
fi

# Launch
echo ""
echo "Launching relay on Fly.io..."
flyctl launch --copy-config --no-deploy 2>/dev/null || true

echo ""
echo "Deploying..."
flyctl deploy

echo ""
echo "=== Done! ==="
echo "Relay URL:"
flyctl info | grep Hostname
echo ""
echo "Dashboard: http://<hostname>:8080"
echo "Relay port: 60005"
echo ""
echo "Set each phone's Relay host to the hostname above."