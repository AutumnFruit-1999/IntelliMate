#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

if [ ! -d "node_modules" ]; then
  echo "首次启动，安装依赖..."
  npm install
fi

echo "启动 IntelliMate WebChat (http://localhost:5173)"
npx vite --host
