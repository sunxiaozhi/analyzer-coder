from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from java_analyzer.models import JavaVectorChunk

KB_EXTENSIONS = {".adoc", ".markdown", ".md", ".rst", ".txt"}
SKIP_DIRS = {
    ".git",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    "__pycache__",
    "build",
    "dist",
}

HEADING_PATTERN = re.compile(r"^(#{1,6})\s+(.+?)\s*$")


@dataclass(frozen=True)
class KbSection:
    title: str
    start_line: int
    end_line: int
    text: str


def build_kb_chunks(path: str | Path, max_chars: int = 1200) -> list[JavaVectorChunk]:
    # 知识库 chunk 复用 JavaVectorChunk，这样代码和文档可以写入同一个索引，
    # 再通过 source_type 过滤。
    chunks: list[JavaVectorChunk] = []
    for file_path in _iter_kb_files(Path(path)):
        chunks.extend(_chunks_for_file(file_path, max_chars=max_chars))
    return chunks


def _iter_kb_files(path: Path) -> Iterable[Path]:
    # 跳过生成目录和缓存目录，保证文档索引稳定，也避免误索引依赖或构建产物。
    if path.is_file():
        if path.suffix.lower() in KB_EXTENSIONS:
            yield path
        return

    for item in sorted(path.rglob("*")):
        if not item.is_file():
            continue
        if any(part in SKIP_DIRS for part in item.parts):
            continue
        if item.suffix.lower() in KB_EXTENSIONS:
            yield item


def _chunks_for_file(path: Path, max_chars: int) -> list[JavaVectorChunk]:
    text = path.read_text(encoding="utf-8", errors="replace")
    chunks: list[JavaVectorChunk] = []
    for section in _split_sections(text):
        for index, part in enumerate(_split_long_text(section.text, max_chars=max_chars), start=1):
            # 保留近似行号，方便搜索结果回链到原始文档。
            part_start_line = section.start_line + _line_offset(section.text, part)
            part_end_line = part_start_line + part.count("\n")
            chunks.append(
                JavaVectorChunk(
                    id=f"{path}::kb::{section.title}::{index}",
                    text=_chunk_text(path, section.title, part),
                    metadata={
                        "source_type": "kb",
                        "file_path": str(path),
                        "doc_name": path.name,
                        "kind": "document",
                        "section": section.title,
                        "symbol_name": section.title,
                        "type_name": "",
                        "start_line": part_start_line,
                        "start_column": 1,
                        "end_line": part_end_line,
                        "end_column": 1,
                    },
                )
            )
    return chunks


def _split_sections(text: str) -> list[KbSection]:
    # Markdown 风格标题作为检索边界；没有标题的文件退化为单个“文档”分节。
    lines = text.splitlines()
    sections: list[KbSection] = []
    current_title = "document"
    current_start = 1
    current_lines: list[str] = []

    for line_number, line in enumerate(lines, start=1):
        match = HEADING_PATTERN.match(line)
        if match and current_lines:
            sections.append(
                KbSection(
                    title=current_title,
                    start_line=current_start,
                    end_line=line_number - 1,
                    text="\n".join(current_lines).strip(),
                )
            )
            current_title = match.group(2).strip()
            current_start = line_number
            current_lines = [line]
            continue
        if match and not current_lines:
            current_title = match.group(2).strip()
            current_start = line_number
        current_lines.append(line)

    if current_lines:
        sections.append(
            KbSection(
                title=current_title,
                start_line=current_start,
                end_line=len(lines),
                text="\n".join(current_lines).strip(),
            )
        )
    return [section for section in sections if section.text]


def _split_long_text(text: str, max_chars: int) -> list[str]:
    # 优先按段落边界切分以保持可读性；超过目标大小的超长段落再硬切分。
    if len(text) <= max_chars:
        return [text]

    parts: list[str] = []
    current: list[str] = []
    current_len = 0
    for paragraph in re.split(r"\n\s*\n", text):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        if current and current_len + len(paragraph) + 2 > max_chars:
            parts.append("\n\n".join(current))
            current = []
            current_len = 0
        if len(paragraph) > max_chars:
            parts.extend(_split_by_size(paragraph, max_chars=max_chars))
            continue
        current.append(paragraph)
        current_len += len(paragraph) + 2

    if current:
        parts.append("\n\n".join(current))
    return parts


def _split_by_size(text: str, max_chars: int) -> list[str]:
    return [text[index : index + max_chars] for index in range(0, len(text), max_chars)]


def _line_offset(full_text: str, part: str) -> int:
    index = full_text.find(part)
    if index < 0:
        return 0
    return full_text[:index].count("\n")


def _chunk_text(path: Path, section: str, text: str) -> str:
    return "\n".join(
        [
            f"Knowledge document {path.name}",
            f"Section: {section}",
            f"File: {path}",
            text,
        ]
    )
