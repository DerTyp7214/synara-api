FROM pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime

WORKDIR /app

RUN DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y \
    ffmpeg \
    git \
    && rm -rf /var/lib/apt/lists/*

# Fix for some whisperx dependency issues
RUN pip install --no-cache-dir fastapi uvicorn pydantic

# Install WhisperX from git for latest features and alignment models
RUN pip install git+https://github.com/m-bain/whisperX.git

COPY main.py .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
