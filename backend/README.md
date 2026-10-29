# PulseGuard 后端服务

账号体系 + 邮箱验证码 + 数据同步 + 管理端 API 的总枢纽。单文件 SQLite 存储，仅依赖 Flask，可零成本部署到任意 Python 主机或容器。

## 它负责什么

| 能力 | 说明 |
|---|---|
| 邮箱验证码 | 真实 SMTP 发送（默认 QQ 邮箱），注册时校验 |
| 账号体系 | 注册（邮箱+验证码+手机号+账号+密码+昵称，全必填）/ 登录，口令加盐 SHA-256 |
| 数据同步 | 用户端每小时自动同步 + 关键操作（购买、功能激活…）实时上报 |
| 管理端 API | 密码锁 `421083`、用户列表、禁用功能 / 限制账户 / 单独公告 / 全局公告 |
| Web 管理页 | `/admin`，与管理端 App 共用同一套后端逻辑 |

## 环境变量

见 `.env.example`。关键项：

- `ADMIN_PASSWORD`：管理端密码，默认 `421083`
- `SMTP_HOST/PORT/USER/PASS`：发件邮箱（QQ 邮箱填 `smtp.qq.com:465` + 授权码）
- `APP_NAME`：展示名
- `DEV_MODE`：本地联调时设为 `1`，SMTP 不通会把验证码回显到 `/api/send_code` 的 `dev_code` 字段；**生产务必设 `0`**
- `DB_PATH`：SQLite 路径（容器部署挂 `/app/data` 卷）
- `PORT`：监听端口，默认 `5000`

## 本地运行

```bash
cd backend
pip install -r requirements.txt
export DEV_MODE=1 ADMIN_PASSWORD=421083   # 本地先跑通流程
python3 app.py
```

## 容器部署（推荐）

```bash
docker build -t pulseguard-backend ./backend
docker run -d --name pg -p 5000:5000 \
  -v pg-data:/app/data \
  -e ADMIN_PASSWORD=421083 \
  -e SMTP_HOST=smtp.qq.com -e SMTP_PORT=465 \
  -e SMTP_USER=你的QQ邮箱 -e SMTP_PASS=你的授权码 \
  -e DEV_MODE=0 \
  pulseguard-backend
```

也可用 Railway / Render / Fly.io 等免费容器平台，绑定环境变量并把 `/app/data` 挂为持久卷即可。

## 接口一览

所有接口返回 `{ "ok": bool, "msg"?: str, ... }`。用户接口带 `Authorization: Bearer <user_token>`（或 `?token=`），管理接口带 `<admin_token>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/send_code` | 发送邮箱验证码 |
| POST | `/api/register` | 注册（校验验证码） |
| POST | `/api/login` | 登录，返回 token |
| GET | `/api/me` | 我的资料 + 禁用功能 + 公告 |
| POST | `/api/sync` | 上报数据快照（每小时 / 实时） |
| POST | `/api/event` | 记录关键操作事件 |
| GET | `/api/announcements` | 我的公告（全局 + 定向） |
| POST | `/api/admin/login` | 管理端登录（密码 421083）→ admin token |
| GET | `/api/admin/users` | 全部注册用户列表 |
| GET | `/api/admin/users/<id>` | 用户详情（快照/操作/公告） |
| POST | `/api/admin/users/<id>/disable_feature` | 禁用/启用某功能 |
| POST | `/api/admin/users/<id>/set_status` | 设置账户状态（normal/restricted/disabled） |
| POST | `/api/admin/users/<id>/announce` | 向单个用户发公告 |
| POST | `/api/admin/announce` | 发全局公告 |
| GET | `/admin` | Web 管理页（密码登录） |

## 客户端接入

安卓用户端与管理端 App 通过 `API_BASE_URL`（构建期由 `devperfApiBaseUrl` gradle 属性注入 `BuildConfig.API_BASE_URL`）访问本服务。部署后把该地址填入即可。
