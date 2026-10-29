#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PulseGuard 后端服务
==================

职责：
  - 邮箱验证码发送（真实 SMTP，默认 QQ 邮箱）
  - 账号体系：注册（邮箱验证码 + 手机号 + 账号 + 密码 + 昵称）/ 登录
  - 用户端数据同步：每小时自动 + 关键操作实时上报
  - 管理端 REST API：密码锁 421083、用户列表、禁用功能 / 限制 / 单独公告 / 全局公告
  - 可选 Web 管理页（/admin），同样需要密码 421083

存储：单文件 SQLite（默认 backend/data/app.db），零外部依赖（仅需 Flask）。
部署：见 README.md（Docker / 任意 Python 主机）。

所有接口返回 JSON：{ "ok": true/false, "msg": "...", ... }
认证：用户接口在 Header 带 `Authorization: Bearer <user_token>` 或 query `?token=`；
      管理接口用 `Authorization: Bearer <admin_token>`。
"""

import os
import re
import secrets
import sqlite3
import smtplib
import ssl
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
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "421083")
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


def _load_logo() -> bytes | None:
    """读取内嵌品牌 logo（assets/brand_logo_email.png，相对本文件目录）。"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "assets", "brand_logo_email.png")
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError:
        return None


def send_code_email(email: str, code: str) -> bool:
    """发送验证码邮件（HTML 版，内嵌分子智能科技 logo）；成功返回 True。SMTP 未配置或失败返回 False。"""
    if not SMTP_USER or not SMTP_PASS:
        app.logger.warning("SMTP 未配置，无法发送验证码邮件（email=%s）", email)
        return False
    try:
        msg = EmailMessage()
        msg["Subject"] = f"【{APP_NAME}】您的验证码"
        msg["From"] = SMTP_FROM
        msg["To"] = email
        minutes = CODE_TTL // 60
        msg.set_content(
            f"您的 {APP_NAME} 账号验证码为：{code}\n"
            f"验证码 {minutes} 分钟内有效，请勿泄露给他人。\n"
            f"如非本人操作，请忽略本邮件。\n\n"
            f"分子智能科技有限公司 MOLECULE TECHNOLOGY"
        )
        logo = _load_logo()
        if logo is not None:
            html = f"""\
<div style="margin:0;padding:0;background:#f4f6fb;">
  <div style="max-width:520px;margin:0 auto;padding:28px 16px;font-family:'PingFang SC','Microsoft YaHei',Helvetica,Arial,sans-serif;">
    <div style="background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 18px rgba(31,50,110,.10);">
      <div style="text-align:center;padding:28px 24px 12px;">
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
            msg.add_alternative(html, subtype="html")
            msg.get_payload()[1].add_attachment(
                logo, maintype="image", subtype="png",
                filename="brand_logo.png", cid="brand-logo",
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
        "created_at": u["created_at"],
        "last_sync_at": u["last_sync_at"],
    }


# ---------------------------------------------------------------------------
# 公开接口：验证码 / 注册 / 登录
# ---------------------------------------------------------------------------
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
    return json_ok(msg="同步成功")


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
    if pwd != ADMIN_PASSWORD:
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
    conn.execute(
        "INSERT INTO ops(user_id, type, detail, created_at) VALUES(?,?,?,?)",
        (uid, "admin_disable_feature", f"{feature}={'禁用' if disabled else '启用'}", now_ms()),
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
    conn.execute(
        "INSERT INTO ops(user_id, type, detail, created_at) VALUES(?,?,?,?)",
        (uid, "admin_set_status", f"status={status}; note={note}", now_ms()),
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
# Web 管理页（/admin）：同样需要密码 421083
# ---------------------------------------------------------------------------
@app.route("/admin", methods=["GET", "POST"])
def admin_page():
    if request.method == "POST":
        pwd = request.form.get("password", "")
        if pwd == ADMIN_PASSWORD:
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


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    init_db()
    print(f"[PulseGuard backend] ADMIN_PASSWORD={'*'*len(ADMIN_PASSWORD)} "
          f"DEV_MODE={DEV_MODE} SMTP_USER={SMTP_USER}")
    app.run(host="0.0.0.0", port=PORT, debug=DEV_MODE)
