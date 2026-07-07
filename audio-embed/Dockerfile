FROM python:3.11-slim

WORKDIR /app

RUN DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y \
    ffmpeg \
    libsndfile1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

RUN mkdir -p /models && \
    curl -fsSL -o /models/msd-musicnn-1.pb \
    https://essentia.upf.edu/models/feature-extractors/musicnn/msd-musicnn-1.pb

COPY main.py .

EXPOSE 8200

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8200"]
