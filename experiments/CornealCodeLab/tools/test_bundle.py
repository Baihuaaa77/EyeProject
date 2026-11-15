import base64
import json
from pathlib import Path
import tempfile
import unittest
from unpack_bundle import unpack


class BundleTests(unittest.TestCase):
    def test_restore_keeps_pixels_and_lines_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "bundle.json"
            source.write_text(json.dumps({"format": "corneal-lab-bundle-1", "documents": {
                "summary.json": {"id": "中文"}, "frames.jsonl": [{"index": 1}, {"index": 2}]},
                "binaryFiles": {"patches.f32": base64.b64encode(bytes(range(255))).decode()}}), encoding="utf8")
            destination = unpack(source, root / "restored")
            self.assertEqual((destination / "patches.f32").read_bytes(), bytes(range(255)))
            self.assertEqual(len((destination / "frames.jsonl").read_text().splitlines()), 2)
            with self.assertRaises(FileExistsError):
                unpack(source, destination)

    def test_invalid_filename_cannot_escape_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "bundle.json"
            for name in ("../outside.json", "..\\outside.json", "/outside.json"):
                source.write_text(json.dumps({"format": "corneal-lab-bundle-1", "documents": {name: {}}}), encoding="utf8")
                with self.assertRaises(ValueError):
                    unpack(source, root / "restored")
                self.assertFalse((root / "restored").exists())
