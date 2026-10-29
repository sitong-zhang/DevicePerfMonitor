FROM python:3.11-slim

WORKDIR /app

# 构建上下文为仓库根：显式复制 backend 子目录内容
COPY backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/ .

ENV PORT=5000
EXPOSE 5000

# 数据持久化：在 Render 等平台挂载 /app/data 卷
# （免费实例无持久磁盘，SQLite 数据会随每次部署重置）
CMD ["python", "app.py"]
