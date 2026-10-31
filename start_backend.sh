#!/usr/bin/env bash
# 设备性能监控 · 后端自启动脚本（沙箱常驻版）
#
# 作用：
#   1. 拉起 Flask 后端（127.0.0.1:5000）
#   2. 用 ssh 连 localhost.run 开一条公网隧道（无需下载额外二进制）
#   3. 把当前公网地址写回 GitHub 的 backend-host 分支（backend-host.txt）
#
# 客户端与管理端入口页（https://sitong-zhang.github.io/DevicePerfMonitor/admin.html）
# 都会读取 backend-host.txt 来「自动发现」后端，因此沙箱重启后只要重新跑一次本脚本，
# 地址就会自动更新，用户永远只需收藏那个固定的 Pages 入口，不需要记任何变化的地址。
#
# 用法：
#   bash start_backend.sh
#   ADMIN_PASSWORD=你的密码 bash start_backend.sh
#
set -u
REPO="sitong-zhang/DevicePerfMonitor"
PORT=5000
PASS="${ADMIN_PASSWORD:-admin123}"
ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "==> [1/3] 检查本地后端 ($PORT)…"
if curl -s -o /dev/null --max-time 3 "http://127.0.0.1:$PORT/health"; then
  echo "    后端已在运行"
else
  echo "    启动后端…"
  cd "$ROOT/backend"
  setsid env DEV_MODE=1 APP_NAME=PulseGuard ADMIN_PASSWORD="$PASS" \
    python3 -m flask --app app run --host 127.0.0.1 --port "$PORT" >/tmp/backend.log 2>&1 < /dev/null &
  cd "$ROOT"
  for i in $(seq 1 30); do
    curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/health" && break
    sleep 1
  done
  curl -s -o /dev/null -w "    本地 /health=%{http_code}\n" --max-time 5 "http://127.0.0.1:$PORT/health"
fi

echo "==> [2/3] 检查隧道…"
if grep -qoE "https://[a-z0-9.-]+\.lhr\.life" /tmp/tunnel.log 2>/dev/null \
   && curl -s -o /dev/null --max-time 5 "$(grep -oE 'https://[a-z0-9.-]+\.lhr\.life' /tmp/tunnel.log | head -1)/health"; then
  echo "    隧道已在运行"
else
  echo "    启动隧道（localhost.run）…"
  setsid ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -o ServerAliveInterval=30 -R 80:127.0.0.1:$PORT nokey@localhost.run -p 22 \
    >/tmp/tunnel.log 2>&1 < /dev/null &
  sleep 14
fi

echo "==> [3/3] 提取地址并写回 backend-host.txt…"
URL=$(tr -d '\0' < /tmp/tunnel.log | grep -oE "https://[a-z0-9.-]+\.lhr\.life" | head -1)
if [ -z "$URL" ]; then
  echo "    WARN 未拿到隧道地址，请查看 /tmp/tunnel.log"; exit 1
fi
SHA=$(gh api "repos/$REPO/contents/backend-host.txt?ref=backend-host" --jq '.sha' 2>/dev/null)
CONTENT=$(printf '%s' "$URL" | base64 -w0)
if gh api -X PUT "repos/$REPO/contents/backend-host.txt" \
     -f message="chore: auto-update backend host -> $URL" \
     -f content="$CONTENT" ${SHA:+-f sha="$SHA"} -f branch=backend-host >/dev/null 2>&1; then
  echo "    OK 后端已上线并被自动发现： $URL"
else
  echo "    WARN 后端已起，但写回 backend-host.txt 失败（检查 gh 登录/权限）。当前 URL: $URL"
fi
printf '%s' "$URL" > /tmp/backend-url.txt
curl -s -o /dev/null -w "    公网 /admin=%{http_code}\n" --max-time 10 "$URL/admin"
echo "完成。管理端入口（永久固定，自动发现）："
echo "    https://sitong-zhang.github.io/DevicePerfMonitor/admin.html"
