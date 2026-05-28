#!/usr/bin/env bash
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

cleanup() {
  echo ""
  echo -e "${YELLOW}正在关闭服务...${NC}"
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null
    wait "$BACKEND_PID" 2>/dev/null || true
    echo -e "${GREEN}后端已停止${NC}"
  fi
  if [ -n "$FRONTEND_PID" ] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
    kill "$FRONTEND_PID" 2>/dev/null
    wait "$FRONTEND_PID" 2>/dev/null || true
    echo -e "${GREEN}前端已停止${NC}"
  fi
  exit 0
}
trap cleanup SIGINT SIGTERM

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  IntelliMate 启动脚本${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

echo -e "${YELLOW}[1/3] 构建后端 Maven 模块...${NC}"
mvn install -DskipTests -q
echo -e "${GREEN}  ✓ 后端构建完成${NC}"

echo -e "${YELLOW}[2/3] 启动后端 (intellimate-gateway)...${NC}"
mvn spring-boot:run -pl intellimate-gateway &
BACKEND_PID=$!
echo -e "${GREEN}  ✓ 后端启动中 (PID: $BACKEND_PID)${NC}"

sleep 3

echo -e "${YELLOW}[3/3] 启动前端 (intellimate-web)...${NC}"
cd "$PROJECT_ROOT/intellimate-web"
if [ ! -d "node_modules" ]; then
  echo "  首次启动，安装前端依赖..."
  npm install
fi
npx vite --host &
FRONTEND_PID=$!
echo -e "${GREEN}  ✓ 前端启动中 (PID: $FRONTEND_PID)${NC}"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  后端: http://localhost:3007${NC}"
echo -e "${GREEN}  前端: http://localhost:5173${NC}"
echo -e "${GREEN}  按 Ctrl+C 停止所有服务${NC}"
echo -e "${GREEN}========================================${NC}"

wait
