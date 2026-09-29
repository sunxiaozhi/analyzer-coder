"""Reconstruct the pre-optimization frontend for visual comparison only."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
ref = 'fa98698'
target = Path(__file__).resolve().parent / 'baseline'
paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', ref, 'frontend/src'], cwd=root, text=True).splitlines()
selected = [p for p in paths if (p.startswith('frontend/src/features/knowledge/') or p.startswith('frontend/src/features/ask/') or p.startswith('frontend/src/features/overview/') or p in ['frontend/src/views/KnowledgeView.vue', 'frontend/src/views/ChunksM0View.vue', 'frontend/src/views/AskView.vue', 'frontend/src/styles/main.css', 'frontend/src/styles/design-alignment.css']) and '.spec.' not in p]
for path in selected:
    content = subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=root).decode('utf-8')
    for group in ['knowledge', 'ask', 'overview']:
        content = content.replace(f'@/features/{group}/', f'/qa/baseline/features/{group}/')
    output = target / path.removeprefix('frontend/src/')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(content, encoding='utf-8')
print(f'Prepared {len(selected)} baseline files at {target}')
