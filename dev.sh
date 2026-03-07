#!/bin/bash

# Function to kill child processes
cleanup() {
    echo ">>> Stopping Synara Services..."
    # Kill the background jobs
    jobs -p | xargs -r kill 2>/dev/null
    exit
}

trap cleanup SIGINT SIGTERM EXIT

echo ">>> Building Synara (Proxy & Server)..."
./gradlew :proxy:installDist :server:installDist -Pkotlin.daemon.jvmargs="--enable-native-access=ALL-UNNAMED"

if [ ! -f .env ]; then
    echo "!!! .env file not found. Please create it."
    exit 1
fi

echo ">>> Loading environment variables..."
# Properly load .env file stripping quotes
while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^#.*$ ]] && continue
    [[ -z "$line" ]] && continue
    if [[ "$line" =~ ^([^=]+)=(.*)$ ]]; then
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        # Strip double quotes
        value="${value%\"}"
        value="${value#\"}"
        # Strip single quotes
        value="${value%\'}"
        value="${value#\'}"
        export "$key=$value"
    fi
done < .env

echo ">>> Starting Proxy..."
./proxy/build/install/proxy/bin/proxy &

echo ">>> Starting Server..."
./server/build/install/server/bin/server &

echo ">>> Services are running. Press Ctrl+C to stop."
wait
