FROM python:3.11-slim

WORKDIR /app

# 仅依赖 Flask（其余用标准库）
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV PORT=5000
EXPOSE 5000

# 数据持久化：挂载 /app/data 卷
CMD ["python", "app.py"]
