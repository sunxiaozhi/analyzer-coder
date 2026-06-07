from __future__ import annotations

import hashlib
import math
import re
from collections.abc import Sequence

TOKEN_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+")


class HashingEmbedder:
    """小型确定性向量器，用于无需外部服务的本地索引。"""

    def __init__(self, dimensions: int = 384) -> None:
        self.dimensions = dimensions

    def embed(self, text: str) -> list[float]:
        # 哈希技巧：每个 token 映射到稳定的桶和符号，不下载模型也能生成确定性向量。
        vector = [0.0] * self.dimensions
        for token in _tokens(text):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimensions
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            vector[index] += sign
        return _normalize(vector)

    def embed_many(self, texts: Sequence[str]) -> list[list[float]]:
        return [self.embed(text) for text in texts]


class SentenceTransformerEmbedder:
    """安装 sentence-transformers 后可选使用的语义向量器。"""

    def __init__(self, model_name: str = "BAAI/bge-small-zh-v1.5") -> None:
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as exc:
            raise RuntimeError(
                "sentence-transformers is not installed. "
                "Install it before using SentenceTransformerEmbedder."
            ) from exc
        self.model = SentenceTransformer(model_name)

    def embed(self, text: str) -> list[float]:
        return self.model.encode(text, normalize_embeddings=True).tolist()

    def embed_many(self, texts: Sequence[str]) -> list[list[float]]:
        return self.model.encode(list(texts), normalize_embeddings=True).tolist()


def cosine_similarity(left: Sequence[float], right: Sequence[float]) -> float:
    # 向量在生成时已归一化，因此点积就是余弦相似度。
    if len(left) != len(right):
        raise ValueError("Embedding dimensions do not match.")
    return sum(a * b for a, b in zip(left, right, strict=True))


def _tokens(text: str) -> list[str]:
    return [token.lower() for token in TOKEN_PATTERN.findall(text)]


def _normalize(vector: list[float]) -> list[float]:
    # 空向量保持不变，避免空文本归一化时除以零。
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0:
        return vector
    return [value / norm for value in vector]
