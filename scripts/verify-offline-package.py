"""Check an offline delivery and every file inside its compressed archive."""
from pathlib import Path
import hashlib
import json
import tarfile

root = Path(__file__).resolve().parents[1]
version = (root / 'VERSION').read_text(encoding='utf-8').strip()
name = f'analyzer-coder-docker-{version}'
directory = root / 'release' / name
archive = directory.with_name(name + '.tar.gz')

def digest(stream):
    result = hashlib.sha256()
    while data := stream.read(4 * 1024 * 1024):
        result.update(data)
    return result.hexdigest()

assert not (directory / '.incomplete').exists()
expected_archive = Path(str(archive) + '.sha256').read_text().split()[0]
with archive.open('rb') as stream:
    assert digest(stream) == expected_archive, 'Archive SHA-256 mismatch'
checksums = {}
for line in (directory / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
    checksum, filename = line.split('  ', 1)
    assert Path(filename).name == filename
    with (directory / filename).open('rb') as stream:
        assert digest(stream) == checksum, filename
    checksums[filename] = checksum
required = {'image.tar', 'analyzer.sh', 'analyzer.ps1', 'compose.yaml', 'image.env', 'README.md', 'MANIFEST.json'}
assert set(checksums) == required
seen = set()
with tarfile.open(archive, 'r|gz') as package:
    for member in package:
        if member.isdir():
            assert member.name.rstrip('/') == name
            continue
        assert member.isfile(), 'Unexpected archive entry'
        assert member.name.startswith(name + '/')
        filename = member.name[len(name) + 1:]
        assert filename in required | {'SHA256SUMS'} and filename not in seen
        seen.add(filename)
        stream = package.extractfile(member)
        if filename == 'SHA256SUMS':
            assert stream.read() == (directory / filename).read_bytes()
        else:
            assert digest(stream) == checksums[filename], f'Archived {filename}'
assert seen == required | {'SHA256SUMS'}
manifest = json.loads((directory / 'MANIFEST.json').read_text())
assert manifest['releaseVersion'] == version
assert manifest['image'] == f'analyzer-coder:{version}'
assert manifest['platform'] == 'linux/amd64'
with tarfile.open(directory / 'image.tar') as image_archive:
    images = json.load(image_archive.extractfile('manifest.json'))
    assert len(images) == 1 and images[0]['RepoTags'] == [manifest['image']]
    config = json.load(image_archive.extractfile(images[0]['Config']))
    layer_count = len(config['rootfs']['diff_ids'])
    assert 0 < layer_count <= 64, f'Unsafe image depth: {layer_count}'
    assert len(images[0]['Layers']) == layer_count
    assert manifest['filesystemLayers'] == layer_count
readme = (directory / 'README.md').read_text(encoding='utf-8')
assert '{{RELEASE_VERSION}}' not in readme and version in readme
assert f'analyzer-coder:{version}' in (directory / 'image.env').read_text()
print(json.dumps({'version': version, 'archive': str(archive), 'bytes': archive.stat().st_size,
                  'sha256': expected_archive, 'checkedPackageFiles': len(required),
                  'archiveAndInternalChecksums': 'PASS'}, indent=2))
