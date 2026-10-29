# PulseGuard 后端部署（免费平台）

后端是单文件 Flask + SQLite，仅依赖 Flask，极易部署。仓库已附带 `render.yaml`
与 Docker 配置，可被免费 PaaS 直接拉取部署。

> 关键前提：无论用哪种方式，部署后**必须拿到一个公网 HTTPS 地址**（如
> `https://xxx.onrender.com`），开发者才能用它重新构建 APK（注入
> `BuildConfig.SERVER_URL`）并发布更新——手机端默认写死的是模拟器地址
> `http://10.0.2.2:5000`，真机不可达，必须经此步骤才能连上。

## 方案 A：Render（推荐，免费层）

1. 打开 https://render.com ，用 **GitHub 登录**并授权访问本仓库。
2. New → **Web Service** → 连接仓库 `sitong-zhang/DevicePerfMonitor`，分支 `main`。
3. Render 自动读取仓库根目录的 `render.yaml`
   （runtime: docker，端口 5000，健康检查 `/health`）。
4. **免费实例无持久磁盘**：SQLite 数据会随每次部署重置；需要持久数据请升级
   plan 并挂载 `/app/data` 卷。
5. 在 **Environment** 中按需补充：
   - `SMTP_HOST / SMTP_PORT / SMTP_USER / SMTP_PASS`：发验证码邮件
     （QQ 邮箱填 `smtp.qq.com` + 端口 `465` + 授权码）；不填时 `DEV_MODE=0`
     无法发码，需切回 `DEV_MODE=1` 让验证码回显到 `/api/send_code`。
   - `ADMIN_PASSWORD`：管理端密码（默认 `421083`）。
6. 部署完成后复制分配的 `.onrender.com` 地址，发给开发者注入 APK。

## 方案 B：Railway

类似 Render：连接 GitHub 仓库 → 用 `backend/` 下的 Dockerfile 部署 → 暴露 5000。

## 方案 C：Hugging Face Spaces（Docker）

在 HF 新建 Docker 类型 Space，上传 `backend/` 内容（或绑定 GitHub），设置
`PORT=5000`、`DEV_MODE=0`。需要 HF token。

## 本地联调（不部署）

```bash
cd backend
pip install -r requirements.txt
export DEV_MODE=1          # 验证码回显到接口，无需真实 SMTP
python app.py              # 监听 5000
```

本地后端配合 Android 模拟器（`10.0.2.2:5000`）即可联调。
