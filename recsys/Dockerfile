FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

RUN mkdir -p /data && chown 1000:1000 /data

ENV RECSYS_DATA_DIR=/data

CMD ["python", "main.py"]
