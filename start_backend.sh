#!/usr/bin/env bash
# 设备性能监控 · 后端自启动 + 自愈脚本（沙箱常驻版）
#
# 作用：
#   1. 拉起 Flask 后端（127.0.0.1:5000），本地不可达才重启（自愈）
#   2. 通过 localhost.run 开公网隧道（免费，地址每次重连会变）
#   3. 把当前公网地址写回 GitHub 的 backend-host 分支（backend-host.txt）
#   4. 公网不可达（隧道断了 / 502）就自动重连并更新 -> 真正自进化 / 自愈
#
# 客户端与管理端入口页（https://sitong-zhang.github.io/DevicePerfMonitor/admin.html）
# 都读取 backend-host.txt 来「自动发现」后端：所以隧道地址变不变无所谓，用户永远
# 只收藏那个固定的 Pages 入口。沙箱重启后只要重跑一次本脚本，地址就自动恢复。
#
# 用法：
#   bash start_backend.sh [管理端密码]
#   ADMIN_PASSWORD=xxx bash start_backend.sh
#
set -u
REPO="sitong-zhang/DevicePerfMonitor"
PORT=5000
PASS="${1:-${ADMIN_PASSWORD:-admin123}}"
ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "==> [1/3] 后端自愈"
if curl -s -o /dev/null --max-time 3 "http://127.0.0.1:$PORT/health"; then
  echo "    后端已在运行"
else
  echo "    启动后端…"
  pkill -f "flask --app app run" 2>/dev/null; sleep 1
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

# 读取当前 discovery 地址
CUR=$(gh api "repos/$REPO/contents/backend-host.txt?ref=backend-host" --jq '.content' 2>/dev/null \
      | base64 -d 2>/dev/null | tr -d '[:space:]')

echo "==> [2/3] 隧道自愈"
# 注意：必须用 http_code 判断而不是 curl 退出码——隧道断了时域名仍解析、
# 握手成功但返回 502，curl 不加 -f 照样退出 0（1.0.49 前就是这里误判成
# 「可达」，于是永远不重连，用户侧一直「网络错误 (502)」）。
probe() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" 2>/dev/null; }
if [ -n "$CUR" ] && [ "$(probe "$CUR/health")" = "200" ]; then
  echo "    公网可达：$CUR（无需重启隧道）"
else
  echo "    公网不可达（$(probe "$CUR/health")），重连隧道…"
  SELF=$$
  # 杀掉旧隧道：过滤掉脚本自身 bash 进程，避免 pkill 自杀
  for p in $(pgrep -f "localhost[.]run"); do
    [ "$p" != "$SELF" ] && kill "$p" 2>/dev/null
  done
  sleep 1
  setsid ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ServerAliveInterval=30 \
    -R 80:127.0.0.1:$PORT nokey@localhost.run -p 22 >/tmp/tunnel.log 2>&1 < /dev/null &
  sleep 14
  CUR=$(tr -d '\0' < /tmp/tunnel.log | grep -oE "https://[a-z0-9.-]+\.lhr\.life" | head -1)
fi

echo "==> [3/3] 写回 backend-host.txt"
if [ -n "$CUR" ]; then
  SHA=$(gh api "repos/$REPO/contents/backend-host.txt?ref=backend-host" --jq '.sha' 2>/dev/null)
  CONTENT=$(printf '%s' "$CUR" | base64 -w0)
  if gh api -X PUT "repos/$REPO/contents/backend-host.txt" \
       -f message="chore: auto-update backend host -> $CUR" \
       -f content="$CONTENT" ${SHA:+-f sha="$SHA"} -f branch=backend-host >/dev/null 2>&1; then
    echo "    OK -> $CUR"
  else
    echo "    WARN 写回失败（检查 gh 登录 / 写权限）"
  fi
  sleep 2
  curl -s -o /dev/null -w "    公网 /admin=%{http_code}\n" --max-time 10 "$CUR/admin"
else
  echo "    WARN 未拿到隧道地址，请查看 /tmp/tunnel.log"
fi
echo "完成。管理端入口（永久固定，自动发现）："
echo "    https://sitong-zhang.github.io/DevicePerfMonitor/admin.html"
