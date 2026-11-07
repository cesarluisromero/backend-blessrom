FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 U2NET_HOME=/models
RUN useradd --system --uid 10001 worker && mkdir -p /app /models && chown -R worker:worker /app /models
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
USER worker
EXPOSE 8090
CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8090","--workers","1"]
