#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PulseGuard 后端服务
==================

职责：
  - 邮箱验证码发送（真实 SMTP，默认 QQ 邮箱）
  - 账号体系：注册（邮箱验证码 + 手机号 + 账号 + 密码 + 昵称）/ 登录
  - 用户端数据同步：每小时自动 + 关键操作实时上报
  - 管理端 REST API：密码锁（密码由 ADMIN_PASSWORD 环境变量注入，仓库不记录）、用户列表、禁用功能 / 限制 / 单独公告 / 全局公告
  - 可选 Web 管理页（/admin），同样需要管理密码（环境变量注入）

存储：单文件 SQLite（默认 backend/data/app.db），零外部依赖（仅需 Flask）。
部署：见 README.md（Docker / 任意 Python 主机）。

所有接口返回 JSON：{ "ok": true/false, "msg": "...", ... }
认证：用户接口在 Header 带 `Authorization: Bearer <user_token>` 或 query `?token=`；
      管理接口用 `Authorization: Bearer <admin_token>`。
"""

import os
import re
import secrets
import shutil
import sqlite3
import smtplib
import ssl
import subprocess
import threading
import hashlib
import json
import time
from datetime import datetime, timedelta
from email.message import EmailMessage

from flask import (
    Flask,
    request,
    jsonify,
    g,
    session,
    render_template,
    redirect,
    url_for,
    send_from_directory,
)

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("DB_PATH", os.path.join(BASE_DIR, "data", "app.db"))
PORT = int(os.environ.get("PORT", "5000"))
# 管理密码只从环境变量 / 部署机密注入，源码中不存放明文（仓库公开）。
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "")
APP_NAME = os.environ.get("APP_NAME", "PulseGuard")

SMTP_HOST = os.environ.get("SMTP_HOST", "smtp.qq.com")
SMTP_PORT = int(os.environ.get("SMTP_PORT", "465"))
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASS = os.environ.get("SMTP_PASS", "")
SMTP_FROM = os.environ.get("SMTP_FROM", SMTP_USER)

# 开发模式：SMTP 不通时把验证码回显到响应，方便本地联调（生产务必设为 0）
DEV_MODE = os.environ.get("DEV_MODE", "0") == "1"

# 验证码有效期（秒）
CODE_TTL = int(os.environ.get("CODE_TTL", "300"))
# 验证码重发冷却（秒）
CODE_COOLDOWN = int(os.environ.get("CODE_COOLDOWN", "60"))

# ---------------------------------------------------------------------------
# 账号数据持久化（私有 Git 仓库）
# ---------------------------------------------------------------------------
# 后端托管在没有持久磁盘的环境（GitHub Actions runner）上：进程一重启，
# 本地 SQLite 就随虚拟机一起消失 —— 账号也就"查无此人"了。
# 解决办法：把 app.db 同步到一个**私有**数据仓库（里面是用户邮箱等隐私，
# 绝对不能放公开仓库）。启动时 pull 回来，运行中由托管流程定时 push。
DATA_REPO = os.environ.get("DATA_REPO", "")                  # 形如 owner/repo
DATA_TOKEN = os.environ.get("DATA_TOKEN", "")                # 具备 repo 权限的令牌
DATA_SYNC_TOKEN = os.environ.get("DATA_SYNC_TOKEN", "")      # /internal/sync 调用令牌
_DATA_DIR = "/tmp/devperf-data-repo"

app = Flask(__name__, template_folder=os.path.join(BASE_DIR, "templates"))
app.secret_key = os.environ.get("SESSION_SECRET", secrets.token_hex(16))

# 简单 CORS，便于 App / Web 跨域调用（生产可按需收紧）
@app.after_request
def _cors(resp):
    resp.headers["Access-Control-Allow-Origin"] = "*"
    resp.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization"
    resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    return resp


# ---------------------------------------------------------------------------
# 数据库
# ---------------------------------------------------------------------------
def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


# ---------------------------------------------------------------------------
# 数据持久化：启动时拉回 / 运行中推送
# ---------------------------------------------------------------------------
def _git(*args, cwd=None):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)


def _data_enabled() -> bool:
    """是否启用私有仓库持久化（本地路径模式不需要令牌）。"""
    return bool(DATA_REPO) and (DATA_REPO.startswith("/") or bool(DATA_TOKEN))


def _repo_url() -> str:
    # DATA_REPO 以 "/" 开头时按本地路径处理（便于在沙箱里验证同步流程）
    if DATA_REPO.startswith("/"):
        return DATA_REPO
    return f"https://x-access-token:{DATA_TOKEN}@github.com/{DATA_REPO}.git"


_push_lock = threading.Lock()


def push_async(reason: str = "") -> None:
    """后台线程推送数据（低频关键写操作后调用，避免阻塞请求）。"""
    if not _data_enabled():
        return

    def _run():
        with _push_lock:
            ok, msg = data_push()
            print(f"[data] 异步推送（{reason}）：ok={ok} {msg}")

    threading.Thread(target=_run, daemon=True).start()


def data_pull() -> bool:
    """启动时从私有数据仓库取回 app.db。失败不阻塞启动（降级为本地空库）。"""
    if not _data_enabled():
        print("[data] 未配置 DATA_REPO/DATA_TOKEN，仅使用本地 SQLite（重启即丢）")
        return False
    try:
        os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
        if os.path.isdir(_DATA_DIR):
            shutil.rmtree(_DATA_DIR, ignore_errors=True)
        r = _git("clone", "--depth", "1", _repo_url(), _DATA_DIR)
        if r.returncode != 0:
            print(f"[data] 数据仓库 clone 失败：{r.stderr.strip()[:200]}")
            return False
        _git("config", "user.name", "pulseguard-data-bot", cwd=_DATA_DIR)
        _git("config", "user.email", "pulseguard-data-bot@users.noreply.github.com", cwd=_DATA_DIR)
        src = os.path.join(_DATA_DIR, "app.db")
        if os.path.exists(src):
            shutil.copyfile(src, DB_PATH)
            print(f"[data] 已从私有仓库恢复 app.db（{os.path.getsize(src) / 1024:.1f} KB）")
        else:
            print("[data] 数据仓库中还没有 app.db，本次从空库启动")
        return True
    except Exception as e:  # noqa: BLE001
        print(f"[data] 数据恢复异常：{e}")
        return False


def data_push() -> tuple[bool, str]:
    """把当前 app.db 提交并推送到私有数据仓库（无变化则不产生提交）。"""
    if not _data_enabled():
        return False, "未配置数据仓库"
    if not os.path.isdir(_DATA_DIR):
        return False, "数据仓库未初始化（启动拉取失败）"
    try:
        # 先把 WAL 落盘，保证推的是完整数据
        conn = sqlite3.connect(DB_PATH)
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.close()
        shutil.copyfile(DB_PATH, os.path.join(_DATA_DIR, "app.db"))
        _git("add", "app.db", cwd=_DATA_DIR)
        if not _git("status", "--porcelain", cwd=_DATA_DIR).stdout.strip():
            return True, "无变化"
        stamp = datetime.now().isoformat(timespec="seconds")
        _git("commit", "-m", f"chore(data): 后端数据快照 {stamp}", cwd=_DATA_DIR)
        r = _git("push", "origin", "HEAD", cwd=_DATA_DIR)
        if r.returncode != 0:
            # 远端已有新提交（多实例漂移）→ 拉回后以本地库为准再推一次
            _git("pull", "--rebase", "-X", "ours", "origin", "HEAD", cwd=_DATA_DIR)
            shutil.copyfile(DB_PATH, os.path.join(_DATA_DIR, "app.db"))
            _git("add", "app.db", cwd=_DATA_DIR)
            _git("commit", "-m", f"chore(data): 后端数据快照 {stamp}（重推）", cwd=_DATA_DIR)
            r = _git("push", "origin", "HEAD", cwd=_DATA_DIR)
        if r.returncode != 0:
            return False, r.stderr.strip()[:200]
        return True, f"已推送（{os.path.getsize(DB_PATH) / 1024:.1f} KB）"
    except Exception as e:  # noqa: BLE001
        return False, str(e)[:200]


def init_db():
    conn = db()
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS users (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            account         TEXT UNIQUE NOT NULL,
            email           TEXT UNIQUE NOT NULL,
            phone           TEXT NOT NULL,
            nickname        TEXT NOT NULL,
            pw_hash         TEXT NOT NULL,
            pw_salt         TEXT NOT NULL,
            disabled_features TEXT NOT NULL DEFAULT '[]',
            status          TEXT NOT NULL DEFAULT 'normal',  -- normal | restricted | disabled
            restriction_note TEXT NOT NULL DEFAULT '',
            created_at      INTEGER NOT NULL,
            last_sync_at    INTEGER
        );
        CREATE TABLE IF NOT EXISTS codes (
            email           TEXT PRIMARY KEY,
            code            TEXT NOT NULL,
            created_at      INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS user_tokens (
            token           TEXT PRIMARY KEY,
            user_id         INTEGER NOT NULL,
            created_at      INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS admin_tokens (
            token           TEXT PRIMARY KEY,
            created_at      INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS ops (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id         INTEGER NOT NULL,
            type            TEXT NOT NULL,
            detail          TEXT NOT NULL DEFAULT '',
            created_at      INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS snapshots (
            user_id         INTEGER PRIMARY KEY,
            payload         TEXT NOT NULL,
            updated_at      INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id)
        );
        CREATE TABLE IF NOT EXISTS announcements (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            scope           TEXT NOT NULL,   -- global | user
            user_id         INTEGER,
            title           TEXT NOT NULL,
            content         TEXT NOT NULL,
            created_at      INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id)
        );
        """
    )
    conn.commit()
    conn.close()


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def now_ms():
    return int(time.time() * 1000)


def hash_pw(password: str, salt: bytes) -> str:
    h = hashlib.sha256()
    h.update(salt)
    h.update(password.encode("utf-8"))
    return h.hexdigest()


def new_salt() -> bytes:
    return secrets.token_bytes(16)


def is_email(s: str) -> bool:
    return bool(re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", s or ""))


def is_phone(s: str) -> bool:
    return bool(re.match(r"^\+?\d{6,15}$", (s or "").strip()))


def json_ok(**kw):
    return jsonify({"ok": True, **kw})


def json_err(msg, code=400):
    return jsonify({"ok": False, "msg": msg}), code


def _read_logo_file() -> bytes | None:
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "assets", "brand_logo_email.png")
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError:
        return None


# 必须在进程启动（模块加载）时读入内存：托管环境（GitHub Actions）里，
# 后端启动后"发布官方地址"步骤会把工作区切到 backend-host 分支，
# assets/logo 文件随之从磁盘消失；若发送时才读盘，线上邮件就会丢 logo。
_LOGO_BYTES = _read_logo_file()


def _load_logo() -> bytes | None:
    """返回启动时缓存的品牌 logo 字节（不再依赖磁盘文件）。"""
    return _LOGO_BYTES


def send_code_email(email: str, code: str) -> bool:
    """发送验证码邮件。**始终**发送网页版（HTML）邮件：内嵌品牌 logo（官方仓库外链，
    不依赖本地文件）、卡片式排版、大字验证码；text/plain 仅作客户端降级备份。
    SMTP 未配置或发送失败返回 False。"""
    if not SMTP_USER or not SMTP_PASS:
        app.logger.warning("SMTP 未配置，无法发送验证码邮件（email=%s）", email)
        return False
    try:
        minutes = CODE_TTL // 60
        # logo 以 CID 内嵌进邮件（multipart/related）——外链图会被 QQ 等邮箱
        # 客户端拦截显示为破图，内嵌图任何客户端都能直接显示
        logo = _load_logo()
        html = f"""\
<div style="margin:0;padding:0;background:#f4f6fb;">
  <div style="max-width:520px;margin:0 auto;padding:28px 16px;font-family:'PingFang SC','Microsoft YaHei',Helvetica,Arial,sans-serif;">
    <div style="background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 18px rgba(31,50,110,.10);">
      <div style="text-align:center;padding:28px 24px 12px;background:#ffffff;">
        <img src="cid:brand-logo" alt="分子智能科技 MOLECULE TECHNOLOGY"
             width="180" style="display:block;margin:0 auto;border:0;">
      </div>
      <div style="padding:8px 32px 32px;">
        <h2 style="margin:0 0 14px;font-size:19px;color:#1f326e;text-align:center;">
          {APP_NAME} 登录验证码
        </h2>
        <p style="margin:0 0 18px;font-size:14px;line-height:1.7;color:#555;text-align:center;">
          您正在验证邮箱，本次验证码为：
        </p>
        <div style="margin:0 auto 20px;padding:14px 0;text-align:center;background:#eef2ff;border-radius:12px;">
          <span style="font-size:34px;font-weight:700;letter-spacing:10px;color:#2f54eb;">{code}</span>
        </div>
        <p style="margin:0 0 6px;font-size:13px;line-height:1.7;color:#888;text-align:center;">
          验证码 <b style="color:#1f326e;">{minutes} 分钟</b>内有效，请勿泄露给他人。
        </p>
        <p style="margin:0;font-size:13px;line-height:1.7;color:#888;text-align:center;">
          如非本人操作，请忽略本邮件。
        </p>
      </div>
      <div style="padding:16px 24px;background:#f7f9ff;border-top:1px solid #e8edf8;text-align:center;">
        <p style="margin:0;font-size:12px;color:#9aa5c0;">
          分子智能科技有限公司 · MOLECULE TECHNOLOGY<br>本邮件由 {APP_NAME} 系统自动发送，请勿回复
        </p>
      </div>
    </div>
  </div>
</div>"""
        msg = EmailMessage()
        msg["Subject"] = f"【{APP_NAME}】您的验证码"
        msg["From"] = SMTP_FROM
        msg["To"] = email
        # 纯文本仅作降级备份；HTML 是主展示（与网页版一致的卡片式设计）
        msg.set_content(
            f"【{APP_NAME}】登录验证码\n\n"
            f"您的验证码为：{code}（{minutes} 分钟内有效）\n"
            f"如非本人操作，请忽略本邮件。\n\n"
            f"分子智能科技有限公司 MOLECULE TECHNOLOGY"
        )
        msg.add_alternative(html, subtype="html")
        if logo is not None:
            # 关键：必须用 add_related（multipart/related + inline），
            # 用 add_attachment 会变成 multipart/mixed + attachment，
            # QQ 邮箱会当成附件收下、正文 cid 位置留白（表现为"没有 logo"）。
            msg.get_payload()[1].add_related(
                logo, maintype="image", subtype="png",
                filename="brand_logo.png",
                cid="<brand-logo>", disposition="inline",
            )
        ctx = ssl.create_default_context()
        with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, context=ctx) as server:
            server.login(SMTP_USER, SMTP_PASS)
            server.send_message(msg)
        return True
    except Exception as e:  # noqa: BLE001
        app.logger.error("发送验证码邮件失败：%s", e)
        return False


def get_bearer():
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[7:].strip()
    return request.args.get("token") or request.form.get("token")


def current_user():
    token = get_bearer()
    if not token:
        return None
    conn = db()
    row = conn.execute(
        "SELECT u.* FROM user_tokens t JOIN users u ON u.id=t.user_id WHERE t.token=?",
        (token,),
    ).fetchone()
    conn.close()
    return row


def require_user():
    u = current_user()
    if not u:
        return None, json_err("未登录或登录已失效", 401)
    return u, None


def current_admin():
    token = get_bearer()
    if not token:
        return None
    conn = db()
    row = conn.execute(
        "SELECT 1 FROM admin_tokens WHERE token=?", (token,)
    ).fetchone()
    conn.close()
    return row is not None


def require_admin():
    if current_admin():
        return True, None
    if session.get("admin"):
        return True, None
    return False, json_err("管理端未授权", 401)


def user_public(u) -> dict:
    return {
        "id": u["id"],
        "account": u["account"],
        "email": u["email"],
        "phone": u["phone"],
        "nickname": u["nickname"],
        "status": u["status"],
        "restriction_note": u["restriction_note"] or "",
        "created_at": u["created_at"],
        "last_sync_at": u["last_sync_at"],
    }


# ---------------------------------------------------------------------------
# 公开接口：验证码 / 注册 / 登录
# ---------------------------------------------------------------------------
@app.route("/health", methods=["GET"])
def health():
    # 供平台健康检查（Render / 容器编排）探测存活
    return jsonify(ok=True), 200


@app.route("/api/send_code", methods=["POST", "OPTIONS"])
def send_code():
    if request.method == "OPTIONS":
        return json_ok()
    data = request.get_json(silent=True) or {}
    email = (data.get("email") or "").strip()
    if not is_email(email):
        return json_err("邮箱格式不正确")

    conn = db()
    last = conn.execute(
        "SELECT created_at FROM codes WHERE email=?", (email,)
    ).fetchone()
    if last and now_ms() - last["created_at"] < CODE_COOLDOWN * 1000:
        conn.close()
        return json_err(f"请求过于频繁，请 {CODE_COOLDOWN} 秒后再试")

    code = f"{secrets.randbelow(1000000):06d}"
    conn.execute(
        "INSERT INTO codes(email, code, created_at) VALUES(?,?,?) "
        "ON CONFLICT(email) DO UPDATE SET code=excluded.code, created_at=excluded.created_at",
        (email, code, now_ms()),
    )
    conn.commit()
    conn.close()

    ok = send_code_email(email, code)
    if not ok and not DEV_MODE:
        # 生产：SMTP 失败不应泄露验证码，但提示用户检查邮件配置
        return json_err("验证码邮件发送失败，请稍后重试或联系管理员")

    resp = {"ok": True, "msg": "验证码已发送，请查收邮件"}
    if DEV_MODE:
        # 仅开发模式回显，便于联调
        resp["dev_code"] = code
    return jsonify(resp)


@app.route("/api/register", methods=["POST", "OPTIONS"])
def register():
    if request.method == "OPTIONS":
        return json_ok()
    data = request.get_json(silent=True) or {}
    email = (data.get("email") or "").strip()
    code = (data.get("code") or "").strip()
    phone = (data.get("phone") or "").strip()
    account = (data.get("account") or "").strip()
    password = data.get("password") or ""
    nickname = (data.get("nickname") or "").strip()

    # 全字段必填校验
    if not is_email(email):
        return json_err("请填写正确的邮箱")
    if not code:
        return json_err("请填写邮箱验证码")
    if not is_phone(phone):
        return json_err("请填写正确的手机号")
    if len(account) < 3:
        return json_err("账号至少 3 个字符")
    if len(password) < 6:
        return json_err("密码至少 6 位")
    if not nickname:
        return json_err("请填写昵称")

    conn = db()
    c = conn.execute("SELECT code, created_at FROM codes WHERE email=?", (email,)).fetchone()
    if not c or c["code"] != code:
        conn.close()
        return json_err("验证码错误")
    if now_ms() - c["created_at"] > CODE_TTL * 1000:
        conn.close()
        return json_err("验证码已过期，请重新获取")

    # 唯一性
    if conn.execute("SELECT 1 FROM users WHERE email=?", (email,)).fetchone():
        conn.close()
        return json_err("该邮箱已被注册")
    if conn.execute("SELECT 1 FROM users WHERE account=?", (account,)).fetchone():
        conn.close()
        return json_err("该账号已被注册")

    salt = new_salt()
    pw_hash = hash_pw(password, salt)
    cur = conn.execute(
        "INSERT INTO users(account, email, phone, nickname, pw_hash, pw_salt, created_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (account, email, phone, nickname, pw_hash, salt.hex(), now_ms()),
    )
    user_id = cur.lastrowid
    # 消费验证码
    conn.execute("DELETE FROM codes WHERE email=?", (email,))
    # 发用户 token
    token = secrets.token_hex(32)
    conn.execute(
        "INSERT INTO user_tokens(token, user_id, created_at) VALUES(?,?,?)",
        (token, user_id, now_ms()),
    )
    conn.commit()
    conn.close()
    # 注册是低频但最关键的操作：立刻异步落仓，避免"刚注册完后端就重启"导致账号丢失
    push_async("注册新账号")
    return json_ok(token=token, user=user_public(_reload_user(user_id)))


@app.route("/api/login", methods=["POST", "OPTIONS"])
def login():
    if request.method == "OPTIONS":
        return json_ok()
    data = request.get_json(silent=True) or {}
    account = (data.get("account") or "").strip()
    password = data.get("password") or ""
    if not account or not password:
        return json_err("请填写账号和密码")

    conn = db()
    u = conn.execute(
        "SELECT * FROM users WHERE account=? OR email=?", (account, account)
    ).fetchone()
    if not u:
        conn.close()
        return json_err("账号不存在")
    if u["status"] == "disabled":
        conn.close()
        note = (u["restriction_note"] or "").strip()
        return json_err("账号已被管理员禁用" + (f"：{note}" if note else ""), 403)
    salt = bytes.fromhex(u["pw_salt"])
    if hash_pw(password, salt) != u["pw_hash"]:
        conn.close()
        return json_err("密码错误")
    token = secrets.token_hex(32)
    conn.execute(
        "INSERT INTO user_tokens(token, user_id, created_at) VALUES(?,?,?)",
        (token, u["id"], now_ms()),
    )
    conn.commit()
    conn.close()
    return json_ok(token=token, user=user_public(u))


def _reload_user(uid):
    conn = db()
    u = conn.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    conn.close()
    return u


# ---------------------------------------------------------------------------
# 用户接口：资料 / 同步 / 事件 / 公告 / 功能开关
# ---------------------------------------------------------------------------
@app.route("/api/me", methods=["GET"])
def me():
    u, err = require_user()
    if err:
        return err
    conn = db()
    ann = conn.execute(
        "SELECT id, scope, title, content, created_at FROM announcements "
        "WHERE scope='global' OR (scope='user' AND user_id=?) ORDER BY created_at DESC",
        (u["id"],),
    ).fetchall()
    conn.close()
    disabled = json.loads(u["disabled_features"] or "[]")
    return json_ok(
        user=user_public(u),
        disabled_features=disabled,
        announcements=[dict(a) for a in ann],
    )


@app.route("/api/sync", methods=["POST", "OPTIONS"])
def sync():
    if request.method == "OPTIONS":
        return json_ok()
    u, err = require_user()
    if err:
        return err
    if u["status"] == "disabled":
        return json_err("账号已被禁用，无法同步", 403)
    if "sync" in json.loads(u["disabled_features"] or "[]"):
        return json_err("数据同步已被管理员关闭", 403)
    data = request.get_json(silent=True) or {}
    payload = json.dumps(data.get("payload", {}), ensure_ascii=False)
    conn = db()
    conn.execute(
        "INSERT INTO snapshots(user_id, payload, updated_at) VALUES(?,?,?) "
        "ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at",
        (u["id"], payload, now_ms()),
    )
    conn.execute("UPDATE users SET last_sync_at=? WHERE id=?", (now_ms(), u["id"]))
    conn.commit()
    conn.close()
    # 响应直接携带管控状态：客户端每次同步即完成一次回拉，管理端改动秒级生效
    return json_ok(
        msg="同步成功",
        status=u["status"],
        restriction_note=u["restriction_note"] or "",
        disabled_features=json.loads(u["disabled_features"] or "[]"),
    )


@app.route("/api/event", methods=["POST", "OPTIONS"])
def event():
    if request.method == "OPTIONS":
        return json_ok()
    u, err = require_user()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    etype = (data.get("type") or "").strip()
    detail = str(data.get("detail", ""))[:2000]
    if not etype:
        return json_err("缺少事件类型")
    conn = db()
    conn.execute(
        "INSERT INTO ops(user_id, type, detail, created_at) VALUES(?,?,?,?)",
        (u["id"], etype, detail, now_ms()),
    )
    conn.execute("UPDATE users SET last_sync_at=? WHERE id=?", (now_ms(), u["id"]))
    conn.commit()
    conn.close()
    return json_ok(msg="已记录")


@app.route("/api/announcements", methods=["GET"])
def announcements():
    u, err = require_user()
    if err:
        return err
    conn = db()
    ann = conn.execute(
        "SELECT id, scope, title, content, created_at FROM announcements "
        "WHERE scope='global' OR (scope='user' AND user_id=?) ORDER BY created_at DESC",
        (u["id"],),
    ).fetchall()
    conn.close()
    return json_ok(announcements=[dict(a) for a in ann])


@app.route("/api/features", methods=["GET"])
def features():
    u, err = require_user()
    if err:
        return err
    return json_ok(disabled_features=json.loads(u["disabled_features"] or "[]"))


# ---------------------------------------------------------------------------
# 管理端接口
# ---------------------------------------------------------------------------
@app.route("/api/admin/login", methods=["POST", "OPTIONS"])
def admin_login():
    if request.method == "OPTIONS":
        return json_ok()
    data = request.get_json(silent=True) or {}
    pwd = data.get("password") or ""
    # 未配置管理密码时一律拒绝登录，避免空密码被绕过
    if not ADMIN_PASSWORD or pwd != ADMIN_PASSWORD:
        return json_err("密码错误", 403)
    token = secrets.token_hex(32)
    conn = db()
    conn.execute(
        "INSERT INTO admin_tokens(token, created_at) VALUES(?,?)", (token, now_ms())
    )
    conn.commit()
    conn.close()
    return json_ok(token=token)


@app.route("/api/admin/stats", methods=["GET"])
def admin_stats():
    ok, err = require_admin()
    if err:
        return err
    conn = db()
    total = conn.execute("SELECT COUNT(*) c FROM users").fetchone()["c"]
    synced_1h = conn.execute(
        "SELECT COUNT(*) c FROM users WHERE last_sync_at > ?",
        (now_ms() - 3600 * 1000,),
    ).fetchone()["c"]
    restricted = conn.execute(
        "SELECT COUNT(*) c FROM users WHERE status != 'normal'"
    ).fetchone()["c"]
    conn.close()
    return json_ok(total=total, synced_1h=synced_1h, restricted=restricted)


@app.route("/api/admin/users", methods=["GET"])
def admin_users():
    ok, err = require_admin()
    if err:
        return err
    conn = db()
    rows = conn.execute(
        "SELECT id, account, email, phone, nickname, status, created_at, last_sync_at, disabled_features "
        "FROM users ORDER BY created_at DESC"
    ).fetchall()
    out = []
    for r in rows:
        op_count = conn.execute(
            "SELECT COUNT(*) c FROM ops WHERE user_id=?", (r["id"],)
        ).fetchone()["c"]
        d = dict(r)
        d["op_count"] = op_count
        d["disabled_features"] = json.loads(r["disabled_features"] or "[]")
        out.append(d)
    conn.close()
    return json_ok(users=out)


@app.route("/api/admin/users/<int:uid>", methods=["GET"])
def admin_user_detail(uid):
    ok, err = require_admin()
    if err:
        return err
    conn = db()
    u = conn.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    if not u:
        conn.close()
        return json_err("用户不存在", 404)
    snapshot = conn.execute(
        "SELECT payload, updated_at FROM snapshots WHERE user_id=?", (uid,)
    ).fetchone()
    ops = conn.execute(
        "SELECT id, type, detail, created_at FROM ops WHERE user_id=? ORDER BY created_at DESC LIMIT 200",
        (uid,),
    ).fetchall()
    anns = conn.execute(
        "SELECT id, title, content, created_at FROM announcements WHERE scope='user' AND user_id=? ORDER BY created_at DESC",
        (uid,),
    ).fetchall()
    conn.close()
    return json_ok(
        user=user_public(u),
        disabled_features=json.loads(u["disabled_features"] or "[]"),
        restriction_note=u["restriction_note"],
        snapshot=json.loads(snapshot["payload"]) if snapshot else None,
        snapshot_updated_at=snapshot["updated_at"] if snapshot else None,
        operations=[dict(o) for o in ops],
        announcements=[dict(a) for a in anns],
    )


# 可被管理端禁用的功能清单（key 与用户端 SyncManager.isFeatureDisabled 一致）
FEATURE_LABELS = {
    "overlay": "悬浮窗",
    "sync": "数据同步",
    "alert": "告警提醒",
    "announcement": "公告展示",
    "deep_monitor": "深度监测",
    "background_collect": "后台采集",
    "feedback": "意见反馈",
    "auto_update": "自动更新",
}


@app.route("/api/admin/users/<int:uid>/disable_feature", methods=["POST", "OPTIONS"])
def admin_disable_feature(uid):
    if request.method == "OPTIONS":
        return json_ok()
    ok, err = require_admin()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    feature = (data.get("feature") or "").strip()
    disabled = bool(data.get("disabled", True))
    if not feature:
        return json_err("缺少功能名")
    conn = db()
    u = conn.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    if not u:
        conn.close()
        return json_err("用户不存在", 404)
    cur = set(json.loads(u["disabled_features"] or "[]"))
    if disabled:
        cur.add(feature)
    else:
        cur.discard(feature)
    conn.execute(
        "UPDATE users SET disabled_features=? WHERE id=?",
        (json.dumps(sorted(cur), ensure_ascii=False), uid),
    )
    feature_label = FEATURE_LABELS.get(feature, feature)
    conn.execute(
        "INSERT INTO ops(user_id, type, detail, created_at) VALUES(?,?,?,?)",
        (uid, "admin_disable_feature",
         f"{'禁用' if disabled else '恢复'}了「{feature_label}」功能", now_ms()),
    )
    conn.commit()
    conn.close()
    return json_ok(disabled_features=sorted(cur))


@app.route("/api/admin/users/<int:uid>/set_status", methods=["POST", "OPTIONS"])
def admin_set_status(uid):
    if request.method == "OPTIONS":
        return json_ok()
    ok, err = require_admin()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    status = (data.get("status") or "").strip()
    note = (data.get("note") or "").strip()
    if status not in ("normal", "restricted", "disabled"):
        return json_err("非法状态")
    conn = db()
    u = conn.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
    if not u:
        conn.close()
        return json_err("用户不存在", 404)
    conn.execute(
        "UPDATE users SET status=?, restriction_note=? WHERE id=?", (status, note, uid)
    )
    status_label = {"normal": "正常", "restricted": "受限", "disabled": "禁用"}[status]
    note_part = f"，说明：{note}" if note else ""
    conn.execute(
        "INSERT INTO ops(user_id, type, detail, created_at) VALUES(?,?,?,?)",
        (uid, "admin_set_status", f"将账号状态改为「{status_label}」{note_part}", now_ms()),
    )
    conn.commit()
    conn.close()
    return json_ok(status=status)


@app.route("/api/admin/users/<int:uid>/announce", methods=["POST", "OPTIONS"])
def admin_user_announce(uid):
    if request.method == "OPTIONS":
        return json_ok()
    ok, err = require_admin()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    title = (data.get("title") or "").strip()
    content = (data.get("content") or "").strip()
    if not title or not content:
        return json_err("标题和内容均必填")
    conn = db()
    if not conn.execute("SELECT 1 FROM users WHERE id=?", (uid,)).fetchone():
        conn.close()
        return json_err("用户不存在", 404)
    conn.execute(
        "INSERT INTO announcements(scope, user_id, title, content, created_at) VALUES(?,?,?,?,?)",
        ("user", uid, title, content, now_ms()),
    )
    conn.commit()
    conn.close()
    return json_ok(msg="已向该用户发送公告")


@app.route("/api/admin/announce", methods=["POST", "OPTIONS"])
def admin_global_announce():
    if request.method == "OPTIONS":
        return json_ok()
    ok, err = require_admin()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    title = (data.get("title") or "").strip()
    content = (data.get("content") or "").strip()
    if not title or not content:
        return json_err("标题和内容均必填")
    conn = db()
    conn.execute(
        "INSERT INTO announcements(scope, user_id, title, content, created_at) VALUES(?,?,?,?,?)",
        ("global", None, title, content, now_ms()),
    )
    conn.commit()
    conn.close()
    return json_ok(msg="已发布全局公告")


@app.route("/api/admin/announcements", methods=["GET"])
def admin_announcements():
    ok, err = require_admin()
    if err:
        return err
    conn = db()
    rows = conn.execute(
        "SELECT a.*, u.account FROM announcements a LEFT JOIN users u ON u.id=a.user_id "
        "ORDER BY created_at DESC LIMIT 200"
    ).fetchall()
    conn.close()
    return json_ok(announcements=[dict(r) for r in rows])


# ---------------------------------------------------------------------------
# Web 管理页（/admin）：密码同样只从环境变量注入
# ---------------------------------------------------------------------------
@app.route("/admin", methods=["GET", "POST"])
def admin_page():
    if request.method == "POST":
        pwd = request.form.get("password", "")
        if ADMIN_PASSWORD and pwd == ADMIN_PASSWORD:
            session["admin"] = True
            return redirect(url_for("admin_page"))
        return render_template("admin.html", error="密码错误", authed=False, app_name=APP_NAME)
    if not session.get("admin"):
        return render_template("admin.html", error=None, authed=False, app_name=APP_NAME)
    return render_template("admin.html", error=None, authed=True, app_name=APP_NAME)


@app.route("/admin/logout", methods=["GET"])
def admin_logout():
    session.pop("admin", None)
    return redirect(url_for("admin_page"))


@app.route("/admin/api/users", methods=["GET"])
def admin_page_users():
    if not session.get("admin"):
        return json_err("未授权", 401)
    # 直接复用 REST 逻辑
    return admin_users()


@app.route("/healthz", methods=["GET"])
def healthz():
    return json_ok(status="up")


@app.route("/internal/sync", methods=["POST", "GET"])
def internal_sync():
    """托管环境专用：把 SQLite 推送到私有数据仓库。

    GitHub Actions 的 runner 没有持久磁盘，靠定时任务周期性调用本接口
    把账号数据落仓；启动时的取回见 [data_pull]。
    若配置了 DATA_SYNC_TOKEN，则必须在 Header `X-Sync-Token` 或 `?token=` 带上。
    """
    tok = request.headers.get("X-Sync-Token") or request.args.get("token") or ""
    if DATA_SYNC_TOKEN and tok != DATA_SYNC_TOKEN:
        return json_err("未授权", 401)
    ok, msg = data_push()
    return jsonify({"ok": ok, "msg": msg})


@app.route("/internal/sync/status", methods=["GET"])
def internal_sync_status():
    """自检用：返回持久化配置与当前库大小，便于排查"账号丢失"。"""
    size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    try:
        conn = db()
        users = conn.execute("SELECT COUNT(*) c FROM users").fetchone()["c"]
        conn.close()
    except Exception:  # noqa: BLE001
        users = -1
    return json_ok(
        data_repo=DATA_REPO or None,
        repo_ready=os.path.isdir(_DATA_DIR),
        db_bytes=size,
        users=users,
    )


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    # 顺序很重要：先取回上次的数据，再建表（否则空库会把旧库覆盖掉）
    data_pull()
    init_db()
    print(f"[PulseGuard backend] ADMIN_PASSWORD={'*'*len(ADMIN_PASSWORD)} "
          f"DEV_MODE={DEV_MODE} SMTP_USER={SMTP_USER} "
          f"DATA_REPO={DATA_REPO or '未配置'}")
    app.run(host="0.0.0.0", port=PORT, debug=DEV_MODE)
