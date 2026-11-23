"""Review-only tests; original GLB is NOT an exercise demonstration."""
import hashlib
import json
import struct
import unittest

from forma_concept_repro import BASE, SHA, TASK, generate


class FormaConceptReproTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = generate()

    def test_original_bytes_reproducible(self):
        self.assertEqual(len(self.raw), 125288)
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), SHA)
        self.assertEqual(generate(), self.raw)

    def test_glb_structure_and_no_external_resources(self):
        self.assertEqual(self.raw[:4], b'glTF')
        version, length = struct.unpack_from('<II', self.raw, 4)
        self.assertEqual((version, length), (2, len(self.raw)))
        size, kind = struct.unpack_from('<I4s', self.raw, 12)
        self.assertEqual(kind, b'JSON')
        doc = json.loads(self.raw[20:20 + size])
        self.assertEqual(len(doc['meshes']), 20)
        self.assertEqual(len(doc['nodes']), 20)
        self.assertEqual(len(doc['materials']), 3)
        self.assertEqual(doc['extras']['base_sha'], BASE)
        self.assertEqual(doc['extras']['task_id'], TASK)
        self.assertEqual(doc['extras']['review_status'], 'CONCEPT_ONLY')
        self.assertFalse(doc['extras']['technique_verified'])
        self.assertFalse(doc['extras']['medical_accuracy_verified'])
        for section in ('buffers', 'images'):
            self.assertTrue(all('uri' not in item for item in doc.get(section, [])))

    def test_binary_tampering_changes_digest(self):
        mutated = bytearray(self.raw)
        mutated[-4] ^= 1
        self.assertNotEqual(hashlib.sha256(mutated).hexdigest(), SHA)
        self.assertNotEqual(hashlib.sha256(self.raw[:-1]).hexdigest(), SHA)


if __name__ == '__main__':
    unittest.main()
