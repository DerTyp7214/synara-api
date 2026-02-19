#!/bin/sh

set -e

NODES=${NODES:-6}
PORT_START=${PORT_START:-7000}
REPLICAS=${REPLICAS:-1}
MAXMEMORY=${MAXMEMORY:-2gb}
MAXMEMORY_POLICY=${MAXMEMORY_POLICY:-allkeys-lfu}
HOST=${HOST:-127.0.0.1}

if [ "$NODES" -lt 3 ]; then
  echo "Error: NODES must be at least 3 for a Redis Cluster."
  exit 1
fi

# Clean up
rm -f /data/nodes.conf
rm -rf /data/node-*

# Start Redis instances
for i in $(seq 1 $NODES); do
  PORT=$((PORT_START + i - 1))
  BUS_PORT=$((PORT + 10000))

  mkdir -p /data/node-$i

  cat <<EOF > /data/node-$i/redis.conf
port $PORT
cluster-enabled yes
cluster-config-file nodes.conf
cluster-node-timeout 5000
appendonly yes
dir /data/node-$i
bind 0.0.0.0
protected-mode no
cluster-announce-ip $HOST
cluster-announce-port $PORT
cluster-announce-bus-port $BUS_PORT
maxmemory $MAXMEMORY
maxmemory-policy $MAXMEMORY_POLICY
EOF

  echo "Starting Redis node $i on port $PORT..."
  redis-server /data/node-$i/redis.conf &
done

# Wait for all nodes to start
sleep 5

# Create the cluster
HOSTS=""
for i in $(seq 1 $NODES); do
  PORT=$((PORT_START + i - 1))
  HOSTS="$HOSTS $HOST:$PORT"
done

echo "Creating cluster with hosts: $HOSTS"
echo "yes" | redis-cli --cluster create $HOSTS --cluster-replicas $REPLICAS

# Keep the container running
tail -f /dev/null