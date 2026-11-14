"""Read-only, fail-closed gate for original FORMA glTF/GLB models.

This is a container/manifest preflight, NOT Khronos' full glTF validation,
medical review, a runtime renderer test, or permission to publish assets.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

CATALOG = Path('domain/src/main/kotlin/ru/forma/domain/Programs.kt')
MANIFEST = Path('visualization/manifest.json')
ASSET_ROOT = Path('app/src/main/assets/forma3d')
ID = re.compile(r'[a-z][a-z0-9_]{1,63}\Z')
SHA = re.compile(r'[0-9a-f]{64}\Z')
SOURCE_ID = re.compile(r'\bExercise\("([a-z][a-z0-9_]*)"\s*,')
REVIEW_REF = re.compile(r'https://github\.com/spenskoj90-sudo/forma-fitness-android/issues/9#issuecomment-[0-9]+\Z')


class AssetError(ValueError):
    pass


def catalog_ids(source: str) -> set[str]:
    ids = SOURCE_ID.findall(source)
    if not ids or len(ids) != len(set(ids)):
        raise AssetError('catalog cannot be read uniquely')
    return set(ids)


def check_glb(raw: bytes) -> None:
    if len(raw) < 20 or raw[:4] != b'glTF':
        raise AssetError('not a GLB 2.0 container')
    version, declared = struct.unpack_from('<II', raw, 4)
    if version != 2 or declared != len(raw):
        raise AssetError('bad GLB version or length')
    chunk_len, chunk_type = struct.unpack_from('<II', raw, 12)
    if chunk_type != 0x4E4F534A or chunk_len % 4 or 20 + chunk_len > len(raw):
        raise AssetError('missing or truncated JSON chunk')
    try:
        doc = json.loads(raw[20:20 + chunk_len].decode('utf-8').strip())
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AssetError('invalid GLB JSON') from exc
    if not isinstance(doc, dict) or not isinstance(doc.get('asset'), dict) or doc['asset'].get('version') != '2.0':
        raise AssetError('glTF 2.0 asset metadata required')
    for section in ('buffers', 'images'):
        records = doc.get(section, [])
        if not isinstance(records, list) or any(not isinstance(item, dict) or 'uri' in item for item in records):
            raise AssetError('external resource URI or invalid buffer/image')
    offset = 20 + chunk_len
    while offset < len(raw):
        if offset + 8 > len(raw):
            raise AssetError('truncated GLB chunk header')
        size, kind = struct.unpack_from('<II', raw, offset)
        if size % 4 or size > len(raw) - offset - 8 or kind != 0x004E4942:
            raise AssetError('unexpected or truncated GLB chunk')
        offset += 8 + size
    if offset != len(raw):
        raise AssetError('GLB chunk-length mismatch')


def inspect(repo: Path, manifest: dict) -> dict:
    if not isinstance(manifest, dict) or set(manifest) != {'schema', 'assets'} or manifest['schema'] != 1:
        raise AssetError('unknown manifest schema')
    assets = manifest['assets']
    if not isinstance(assets, list) or len(assets) > 200:
        raise AssetError('invalid asset inventory')
    catalog = catalog_ids((repo / CATALOG).read_text(encoding='utf-8'))
    root = (repo / ASSET_ROOT).resolve()
    seen = set()
    approved = 0
    for item in assets:
        if not isinstance(item, dict) or set(item) != {'exercise_id', 'path', 'sha256', 'origin', 'status', 'review'}:
            raise AssetError('unexpected asset metadata')
        ex, rel, digest = item['exercise_id'], item['path'], item['sha256']
        if not isinstance(ex, str) or not ID.fullmatch(ex) or ex not in catalog or ex in seen:
            raise AssetError('unknown or duplicate exercise ID')
        seen.add(ex)
        if item['origin'] != 'original' or item['status'] not in ('candidate', 'reviewed'):
            raise AssetError('only original candidate/reviewed assets allowed')
        if not isinstance(rel, str) or len(rel) > 180 or not rel.startswith(str(ASSET_ROOT) + '/') or not rel.endswith('.glb'):
            raise AssetError('invalid asset path')
        path = Path(rel)
        if path.is_absolute() or any(p in ('..', '.') for p in path.parts):
            raise AssetError('path traversal')
        file = repo / path
        if not file.is_file() or file.is_symlink() or not file.resolve().is_relative_to(root):
            raise AssetError('missing, symlinked or escaped asset')
        if file.stat().st_size > 24 * 1024 * 1024 or file.stat().st_size < 20:
            raise AssetError('asset exceeds bounded size')
        if not isinstance(digest, str) or not SHA.fullmatch(digest):
            raise AssetError('invalid SHA-256')
        raw = file.read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest:
            raise AssetError('asset SHA-256 mismatch')
        check_glb(raw)
        review = item['review']
        if item['status'] == 'candidate':
            if review is not None:
                raise AssetError('candidate cannot claim a review')
        else:
            if not isinstance(review, dict) or set(review) != {'reviewer', 'reference'} or not isinstance(review['reviewer'], str) or not review['reviewer'].strip() or len(review['reviewer']) > 120 or not isinstance(review['reference'], str) or not REVIEW_REF.fullmatch(review['reference']):
                raise AssetError('reviewed asset requires independently checkable citation')
            approved += 1
    return {'catalog_total': len(catalog), 'asset_total': len(assets), 'reviewed_claims': approved,
            'technique_verified': False, 'device_verified': False}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--require-complete', action='store_true')
    args = parser.parse_args()
    try:
        manifest = json.loads((args.repo / MANIFEST).read_text(encoding='utf-8'))
        result = inspect(args.repo, manifest)
        print('VISUAL_ASSET_AUDIT_PASS ' + json.dumps(result, sort_keys=True))
        if args.require_complete:
            raise AssetError('independent technique review and physical acceptance required')
        return 0
    except (AssetError, OSError, json.JSONDecodeError, UnicodeDecodeError, TypeError) as error:
        print('VISUAL_ASSET_AUDIT_FAIL ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
