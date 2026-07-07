import logging
from typing import List

import numpy as np
import essentia
from fastapi import FastAPI
from pydantic import BaseModel
from essentia.standard import MonoLoader, TensorflowPredictMusiCNN

essentia.log.infoActive = False
essentia.log.warningActive = False

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("audio-embed")

MODEL_PATH = "/models/msd-musicnn-1.pb"
EMBEDDING_LAYER = "model/dense/BiasAdd"
SAMPLE_RATE = 16000
MODEL_VERSION = "msd-musicnn_v1"

app = FastAPI()
model = TensorflowPredictMusiCNN(graphFilename=MODEL_PATH, output=EMBEDDING_LAYER)


class EmbedRequest(BaseModel):
    paths: List[str]


class EmbedResult(BaseModel):
    path: str
    vector: List[float] | None = None


class EmbedResponse(BaseModel):
    modelVersion: str
    dim: int
    results: List[EmbedResult]


@app.get("/health")
async def health():
    return {"status": "ok", "modelVersion": MODEL_VERSION}


@app.post("/embed", response_model=EmbedResponse)
async def embed(req: EmbedRequest):
    results = []
    dim = 0
    for path in req.paths:
        vector = _embed_one(path)
        if vector is not None:
            dim = len(vector)
        results.append(EmbedResult(path=path, vector=vector))
    return EmbedResponse(modelVersion=MODEL_VERSION, dim=dim, results=results)


def _embed_one(path: str):
    try:
        audio = MonoLoader(filename=path, sampleRate=SAMPLE_RATE, resampleQuality=4)()
        patches = model(audio)
        if patches is None or len(patches) == 0:
            return None
        vector = np.mean(patches, axis=0)
        norm = float(np.linalg.norm(vector))
        if norm > 0:
            vector = vector / norm
        return [float(x) for x in vector]
    except Exception as exc:
        logger.error(f"Failed to embed {path}: {exc}")
        return None
