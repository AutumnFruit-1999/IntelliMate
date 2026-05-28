#!/usr/bin/env bash
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

BACKEND_PORT="${INTELLIMATE_PORT:-3007}"
FRONTEND_PORT=5173
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  echo ""
  echo -e "${YELLOW}正在关闭服务...${NC}"
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null
    wait "$BACKEND_PID" 2>/dev/null || true
    echo -e "${GREEN}  ✓ 后端已停止${NC}"
  fi
  if [ -n "$FRONTEND_PID" ] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
    kill "$FRONTEND_PID" 2>/dev/null
    wait "$FRONTEND_PID" 2>/dev/null || true
    echo -e "${GREEN}  ✓ 前端已停止${NC}"
  fi
  exit 0
}
trap cleanup SIGINT SIGTERM

check_command() {
  if ! command -v "$1" &>/dev/null; then
    echo -e "${RED}✗ 未找到命令: $1${NC}"
    echo -e "  请先安装 $1 后再运行此脚本"
    exit 1
  fi
}

wait_for_port() {
  local port=$1
  local timeout=${2:-30}
  local elapsed=0
  while ! nc -z localhost "$port" 2>/dev/null; do
    if [ $elapsed -ge $timeout ]; then
      echo -e "${RED}✗ 等待端口 $port 超时 (${timeout}s)${NC}"
      return 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  return 0
}

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  IntelliMate 一键启动${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

echo -e "${YELLOW}[0/4] 检查环境...${NC}"
check_command java
check_command mvn
check_command node
check_command npm
JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}')
NODE_VER=$(node --version)
echo -e "${GREEN}  ✓ Java $JAVA_VER | Node $NODE_VER${NC}"

echo -e "${YELLOW}[1/4] 构建后端 (Maven)...${NC}"
if ! mvn clean install -DskipTests -q; then
  echo -e "${RED}✗ Maven 构建失败${NC}"
  exit 1
fi
echo -e "${GREEN}  ✓ 后端构建完成${NC}"

echo -e "${YELLOW}[2/4] 启动后端 (port: $BACKEND_PORT)...${NC}"
mvn spring-boot:run -pl intellimate-gateway -q &
BACKEND_PID=$!
echo -e "  等待后端就绪..."
if wait_for_port "$BACKEND_PORT" 30; then
  echo -e "${GREEN}  ✓ 后端已就绪 (PID: $BACKEND_PID)${NC}"
else
  echo -e "${RED}✗ 后端启动失败，请检查日志${NC}"
  kill "$BACKEND_PID" 2>/dev/null || true
  exit 1
fi

echo -e "${YELLOW}[3/4] 启动前端 (port: $FRONTEND_PORT)...${NC}"
cd "$PROJECT_ROOT/intellimate-web"
if [ ! -d "node_modules" ]; then
  echo "  首次启动，安装前端依赖..."
  npm install --silent
fi
npx vite --host &
FRONTEND_PID=$!
if wait_for_port "$FRONTEND_PORT" 15; then
  echo -e "${GREEN}  ✓ 前端已就绪 (PID: $FRONTEND_PID)${NC}"
else
  echo -e "${RED}✗ 前端启动失败${NC}"
  cleanup
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  [4/4] 全部就绪!${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "  后端: ${CYAN}http://localhost:$BACKEND_PORT${NC}"
echo -e "  前端: ${CYAN}http://localhost:$FRONTEND_PORT${NC}"
echo -e "  按 ${YELLOW}Ctrl+C${NC} 停止所有服务"
echo ""

wait
