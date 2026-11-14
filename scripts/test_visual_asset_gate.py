import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from visual_asset_gate import AssetError, check_glb, inspect


def glb(document=None):
    doc = document if document is not None else {'asset': {'version': '2.0'}, 'scenes': []}
    payload = json.dumps(doc, separators=(',', ':')).encode()
    payload += b' ' * ((-len(payload)) % 4)
    return b'glTF' + struct.pack('<II', 2, 20 + len(payload)) + struct.pack('<II', len(payload), 0x4E4F534A) + payload


class GateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        catalog = self.repo / 'domain/src/main/kotlin/ru/forma/domain/Programs.kt'
        catalog.parent.mkdir(parents=True)
        catalog.write_text('val e = listOf(Exercise("squat", "Присед"), Exercise("bird_dog", "Техника"))')
        self.model = self.repo / 'app/src/main/assets/forma3d/squat.glb'
        self.model.parent.mkdir(parents=True)
        self.model.write_bytes(glb())
        self.entry = {'exercise_id': 'squat', 'path': 'app/src/main/assets/forma3d/squat.glb',
                      'sha256': hashlib.sha256(self.model.read_bytes()).hexdigest(),
                      'origin': 'original', 'status': 'candidate', 'review': None}

    def check(self, *assets):
        return inspect(self.repo, {'schema': 1, 'assets': list(assets)})

    def test_empty_inventory_is_not_quality_approval(self):
        self.assertEqual(self.check()['reviewed_claims'], 0)
        self.assertFalse(self.check()['technique_verified'])

    def test_valid_original_candidate(self):
        self.assertEqual(self.check(self.entry)['asset_total'], 1)

    def test_duplicate_and_unknown_exercise(self):
        with self.assertRaisesRegex(AssetError, 'duplicate'):
            self.check(self.entry, self.entry)
        with self.assertRaisesRegex(AssetError, 'unknown'):
            self.check({**self.entry, 'exercise_id': 'fake'})

    def test_digest_mismatch(self):
        with self.assertRaisesRegex(AssetError, 'mismatch'):
            self.check({**self.entry, 'sha256': '0' * 64})

    def test_disallow_path_traversal(self):
        with self.assertRaises(AssetError):
            self.check({**self.entry, 'path': 'app/src/main/assets/forma3d/../other.glb'})

    def test_reject_symlink(self):
        target = self.repo / 'target.glb'
        target.write_bytes(self.model.read_bytes())
        self.model.unlink()
        self.model.symlink_to(target)
        with self.assertRaises(AssetError):
            self.check(self.entry)

    def test_reject_symlinked_parent_directory(self):
        source_dir = self.model.parent
        target_dir = self.repo / 'moved-original-models'
        source_dir.rename(target_dir)
        source_dir.symlink_to(target_dir, target_is_directory=True)
        with self.assertRaisesRegex(AssetError, 'symlink'):
            self.check(self.entry)

    def test_no_external_urls_in_glb(self):
        bad = glb({'asset': {'version': '2.0'}, 'images': [{'uri': 'https://example.com/a.png'}]})
        with self.assertRaisesRegex(AssetError, 'external'):
            check_glb(bad)

    def test_bad_header_and_length(self):
        with self.assertRaises(AssetError):
            check_glb(b'aaaa' + glb()[4:])
        with self.assertRaises(AssetError):
            check_glb(glb()[:-2])

    def test_reject_duplicate_binary_chunks(self):
        container = glb()
        bin_chunk = struct.pack('<II', 4, 0x004e4942) + b'\0' * 4
        body = container[12:] + bin_chunk * 2
        duplicate = container[:8] + struct.pack('<I', 12 + len(body)) + body
        with self.assertRaisesRegex(AssetError, 'duplicate BIN'):
            check_glb(duplicate)

    def test_claimed_review_requires_reference(self):
        with self.assertRaises(AssetError):
            self.check({**self.entry, 'status': 'reviewed', 'review': None})
        reviewed = {**self.entry, 'status': 'reviewed', 'review':
                    {'reviewer': 'qualified reviewer', 'reference':
                     'https://github.com/spenskoj90-sudo/forma-fitness-android/issues/9#issuecomment-12345'}}
        self.assertEqual(self.check(reviewed)['reviewed_claims'], 1)
        self.assertFalse(self.check(reviewed)['technique_verified'])

    def test_external_or_unknown_status_and_extra_keys_fail(self):
        with self.assertRaises(AssetError):
            self.check({**self.entry, 'origin': 'scraped'})
        with self.assertRaises(AssetError):
            self.check({**self.entry, 'status': 'approved'})
        with self.assertRaises(AssetError):
            self.check({**self.entry, 'command': 'rm -rf /'})


if __name__ == '__main__':
    unittest.main()
