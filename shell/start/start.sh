#!/bin/bash

set -e

BASE_DIR="/opt/analyzer-coder"

echo "========================================="
echo " Analyzer Coder Infrastructure Starting"
echo "========================================="

cd ${BASE_DIR}

echo "[1/5] 检查 Docker..."

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker 未安装"
    exit 1
fi

echo "[2/5] 检查 Docker Compose..."

if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD="docker-compose"
else
    echo "Docker Compose 未安装"
    exit 1
fi

echo "[3/5] 创建数据目录..."

mkdir -p data/mysql
mkdir -p data/qdrant
mkdir -p data/neo4j/{data,logs,import,plugins}
mkdir -p backup/mysql

echo "[4/5] 启动服务..."

${COMPOSE_CMD} up -d

echo "[5/5] 等待服务启动..."

sleep 10

echo ""
echo "========================================="
echo " Service Status"
echo "========================================="

${COMPOSE_CMD} ps

echo ""
echo "========================================="
echo " Access Information"
echo "========================================="

SERVER_IP=$(hostname -I | awk '{print $1}')

echo "MySQL:"
echo "  ${SERVER_IP}:3806"

echo ""
echo "Qdrant:"
echo "  http://${SERVER_IP}:6333/dashboard"

echo ""
echo "Neo4j:"
echo "  http://${SERVER_IP}:7474"
echo "  Bolt: ${SERVER_IP}:7687"

echo ""
echo "========================================="
echo " Analyzer Coder Started Successfully"
echo "========================================="