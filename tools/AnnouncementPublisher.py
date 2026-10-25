#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设备监测 App 的公告发布器（独立小工具，无需安装任何依赖）。

用法：
    python3 AnnouncementPublisher.py
然后在窗口里填标题、正文，粘贴你的 GitHub Token，点「发布」即可。

做了什么：把公告写成 announcement.json（含 id / title / content / updatedAt），
提交到仓库根目录。App 启动时会拉取这个文件，发现 id 变了就弹公告，
用户必须滚到最底才能关掉。commit message 带 [skip ci]，不会触发 CI 重建。
"""
import base64
import datetime
import json
import tkinter as tk
import urllib.error
import urllib.request

DEFAULT_REPO = "sitong-zhang/DevicePerfMonitor"
DEFAULT_BRANCH = "main"
API = "https://api.github.com/repos"


def github_request(method, url, token, data=None):
    """发一次 GitHub API 请求，返回 (响应体文本, HTTP 状态码)。"""
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode("utf-8", "replace"), resp.status
    except urllib.error.HTTPError as e:
        return e.read().decode("utf-8", "replace"), e.code
    except Exception as e:  # 网络抖动手超时等
        return str(e), 0


def publish(repo, branch, token, title, content, status_cb):
    title = (title or "").strip()
    if not token:
        status_cb("错误：请填写 GitHub Token")
        return
    if not title:
        status_cb("错误：请填写公告标题")
        return
    if not content.strip():
        status_cb("错误：请填写公告正文")
        return

    ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H%M%S.%fZ")
    payload = {
        "id": ts,
        "title": title,
        "content": content,
        "updatedAt": ts,
    }
    content_b64 = base64.b64encode(
        json.dumps(payload, ensure_ascii=False).encode("utf-8")
    ).decode("ascii")

    path = "announcement.json"
    sha = None
    body, code = github_request(
        "GET", f"{API}/{repo}/contents/{path}?ref={branch}", token
    )
    if code == 200:
        try:
            sha = json.loads(body)["sha"]
        except Exception:
            sha = None
    elif code == 404:
        pass  # 文件还不存在，直接创建
    else:
        status_cb(f"错误：读取仓库现有文件失败（HTTP {code}）")
        return

    data = {
        "message": "chore: update announcement [skip ci]",
        "content": content_b64,
        "branch": branch,
    }
    if sha:
        data["sha"] = sha
    body, code = github_request(
        "PUT",
        f"{API}/{repo}/contents/{path}",
        token,
        json.dumps(data).encode("utf-8"),
    )
    if code in (200, 201):
        status_cb("发布成功！用户下次打开 App 就会收到这条公告。")
    else:
        msg = body[:200]
        try:
            msg = json.loads(body).get("message", msg)
        except Exception:
            pass
        status_cb(f"发布失败（HTTP {code}）：{msg}")


def main():
    root = tk.Tk()
    root.title("设备监测 App · 公告发布器")
    root.geometry("560x560")
    root.resizable(True, True)

    tk.Label(root, text="公告标题", anchor="w").pack(fill="x", padx=12, pady=(12, 2))
    title_var = tk.StringVar()
    tk.Entry(root, textvariable=title_var).pack(fill="x", padx=12, pady=(0, 6))

    tk.Label(root, text="公告正文（支持换行，用户必须滚到最底才能关）", anchor="w").pack(
        fill="x", padx=12, pady=(4, 2)
    )
    content_box = tk.Text(root, height=12, wrap="word")
    content_box.pack(fill="both", expand=True, padx=12, pady=(0, 6))

    tk.Label(root, text="GitHub Token（仅本地使用，不会保存）", anchor="w").pack(
        fill="x", padx=12, pady=(4, 2)
    )
    token_var = tk.StringVar()
    tk.Entry(root, textvariable=token_var, show="*").pack(fill="x", padx=12, pady=(0, 6))

    row = tk.Frame(root)
    row.pack(fill="x", padx=12, pady=(0, 6))
    tk.Label(row, text="仓库").pack(side="left")
    repo_var = tk.StringVar(value=DEFAULT_REPO)
    tk.Entry(row, textvariable=repo_var, width=34).pack(side="left", padx=(4, 8))
    tk.Label(row, text="分支").pack(side="left")
    branch_var = tk.StringVar(value=DEFAULT_BRANCH)
    tk.Entry(row, textvariable=branch_var, width=10).pack(side="left", padx=4)

    status_var = tk.StringVar(value="填好上面内容，点「发布」即可。")
    status = tk.Label(root, textvariable=status_var, anchor="w", fg="#555")
    status.pack(fill="x", padx=12, pady=(2, 6))

    def on_publish():
        status_var.set("发布中……")
        root.update_idletasks()
        publish(
            repo_var.get().strip(),
            branch_var.get().strip() or DEFAULT_BRANCH,
            token_var.get().strip(),
            title_var.get(),
            content_box.get("1.0", "end-1c"),
            status_var.set,
        )

    tk.Button(
        root,
        text="发布",
        command=on_publish,
        height=2,
        bg="#1a73e8",
        fg="white",
        font=("Arial", 11, "bold"),
    ).pack(fill="x", padx=12, pady=(0, 12))

    root.mainloop()


if __name__ == "__main__":
    main()
