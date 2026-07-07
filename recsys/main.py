import json
import logging
import os
import time
from collections import Counter, defaultdict

import numpy as np
from gensim.models import Word2Vec
from sklearn.cluster import MiniBatchKMeans
from sklearn.decomposition import PCA
from sklearn.linear_model import Ridge

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("recsys")

DATA_DIR = os.getenv("RECSYS_DATA_DIR", "/data")
POLL_SECONDS = float(os.getenv("RECSYS_POLL_SECONDS", "5"))
MIN_PAIRED = 50
MODEL_VERSION = "hybrid_v1"


def _p(name):
    return os.path.join(DATA_DIR, name)


def _unit(v):
    n = float(np.linalg.norm(v))
    return v / n if n > 0 else v


def run_job():
    with open(_p("meta.json")) as f:
        meta = json.load(f)
    dim = int(meta.get("dim", 64))
    n_clusters = int(meta.get("clusters", 24))

    songs = {}
    with open(_p("songs.jsonl")) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            audio = np.asarray(o["audio"], dtype=np.float32) if o.get("audio") else None
            songs[o["id"]] = {"audio": audio, "genres": [g.lower() for g in o.get("genres", [])]}

    sequences = []
    with open(_p("sequences.jsonl")) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            seq = json.loads(line)
            if len(seq) >= 2:
                sequences.append(seq)

    logger.info(f"job: {len(songs)} songs, {len(sequences)} sequences, dim={dim}")

    behavioral = {}
    if sequences:
        wv = Word2Vec(
            sentences=sequences, vector_size=dim, window=8, min_count=1,
            sg=1, epochs=5, workers=os.cpu_count() or 2, seed=42,
        ).wv
        for sid in songs:
            if sid in wv:
                behavioral[sid] = _unit(np.asarray(wv[sid], dtype=np.float32))

    audio_ids = [sid for sid, s in songs.items() if s["audio"] is not None]
    unified = dict(behavioral)

    if behavioral and audio_ids:
        paired = [sid for sid in audio_ids if sid in behavioral]
        if len(paired) >= MIN_PAIRED:
            reg = Ridge(alpha=1.0)
            reg.fit(
                np.vstack([songs[sid]["audio"] for sid in paired]),
                np.vstack([behavioral[sid] for sid in paired]),
            )
            for sid in audio_ids:
                if sid not in unified:
                    pred = reg.predict(songs[sid]["audio"].reshape(1, -1))[0]
                    unified[sid] = _unit(pred.astype(np.float32))

    remaining = [sid for sid in audio_ids if sid not in unified]
    if remaining:
        matrix = np.vstack([songs[sid]["audio"] for sid in remaining])
        d = min(dim, matrix.shape[1], max(1, matrix.shape[0] - 1))
        reduced = PCA(n_components=d).fit_transform(matrix) if matrix.shape[0] > d else matrix[:, :d]
        if reduced.shape[1] < dim:
            reduced = np.pad(reduced, ((0, 0), (0, dim - reduced.shape[1])))
        for sid, row in zip(remaining, reduced):
            unified[sid] = _unit(row.astype(np.float32))

    ids = list(unified.keys())
    clusters, mood = {}, {}
    if ids:
        matrix = np.vstack([unified[i] for i in ids])
        k = max(1, min(n_clusters, len(ids)))
        labels = MiniBatchKMeans(n_clusters=k, random_state=0, n_init=3, batch_size=1024).fit_predict(matrix)
        cluster_genres = defaultdict(Counter)
        for sid, label in zip(ids, labels):
            clusters[sid] = int(label)
            for g in songs[sid]["genres"]:
                cluster_genres[int(label)][g] += 1
        for label, counter in cluster_genres.items():
            mood[label] = counter.most_common(1)[0][0] if counter else None

    with open(_p("embeddings.jsonl"), "w") as f:
        for sid in ids:
            label = clusters.get(sid)
            f.write(json.dumps({
                "id": sid,
                "vector": [float(x) for x in unified[sid]],
                "cluster": label,
                "mood": mood.get(label) if label is not None else None,
            }) + "\n")

    with open(_p("result.ready"), "w") as f:
        f.write(MODEL_VERSION + "\n")
    logger.info(f"wrote {len(ids)} embeddings")


def main():
    logger.info(f"recsys watching {DATA_DIR}")
    while True:
        try:
            if os.path.exists(_p("request.ready")):
                os.remove(_p("request.ready"))
                for stale in ("result.ready", "result.failed"):
                    if os.path.exists(_p(stale)):
                        os.remove(_p(stale))
                run_job()
        except Exception as exc:
            logger.error(f"job failed: {exc}", exc_info=True)
            with open(_p("result.failed"), "w") as f:
                f.write(str(exc))
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
