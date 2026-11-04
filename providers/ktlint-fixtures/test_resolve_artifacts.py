"""Offline resolver policy tests, not Ktlint detector integrations."""

import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from resolve_artifacts import resolve


class ResolverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cache = self.root / "cache"
        self.cache.mkdir()
        self.body = b"explicit small offline artifact fixture"
        self.pin = {
            "coordinate": "com.pinterest.ktlint:fixture:1.8.0", "filename": "fixture-1.8.0.jar",
            "url": "https://repo.maven.apache.org/maven2/com/pinterest/ktlint/fixture/1.8.0/fixture-1.8.0.jar",
            "size": len(self.body), "sha256": hashlib.sha256(self.body).hexdigest(),
            "upstream_checksum_algorithm": "sha1", "upstream_checksum": hashlib.sha1(self.body).hexdigest(),
        }
        self.manifest = self.root / "manifest.json"
        self.write_manifest()

    def write_manifest(self, pins=None):
        self.manifest.write_text(json.dumps({"schema_version": 1, "artifacts": pins or [self.pin]}))

    def test_offline_never_downloads(self):
        with patch("urllib.request.urlopen") as request:
            with self.assertRaisesRegex(ValueError, "unavailable offline"):
                resolve(self.cache, manifest_path=self.manifest)
            request.assert_not_called()

    def test_every_cached_byte_is_verified_and_corruption_is_not_replaced(self):
        path = self.cache / self.pin["filename"]
        path.write_bytes(self.body)
        result = resolve(self.cache, manifest_path=self.manifest)
        self.assertEqual("sha256:" + self.pin["sha256"], result[0]["digest"])
        path.write_bytes(b"X" + self.body[1:])
        with patch("urllib.request.urlopen") as request:
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                resolve(self.cache, True, self.manifest)
            request.assert_not_called()

    def test_symlink_cache_and_artifacts_are_rejected(self):
        target = self.root / "target.jar"
        target.write_bytes(self.body)
        (self.cache / self.pin["filename"]).symlink_to(target)
        with self.assertRaisesRegex(ValueError, "nonregular"):
            resolve(self.cache, manifest_path=self.manifest)
        alias = self.root / "alias"
        alias.symlink_to(self.cache)
        with self.assertRaisesRegex(ValueError, "symlink"):
            resolve(alias, manifest_path=self.manifest)

    def test_unsafe_and_duplicate_pins_fail_before_network(self):
        original = copy.deepcopy(self.pin)
        for field, value in [("filename", "../outside.jar"), ("url", "http://repo.maven.apache.org/maven2/fixture-1.8.0.jar"),
                             ("url", "https://repo.maven.apache.org.evil/maven2/fixture-1.8.0.jar"),
                             ("url", None), ("url", []), ("url", 42), ("sha256", "0"), ("size", True)]:
            with self.subTest(field=field, value=value), patch("urllib.request.urlopen") as request:
                self.pin = {**original, field: value}
                self.write_manifest()
                with self.assertRaisesRegex(ValueError, "unsafe/invalid"):
                    resolve(self.cache, True, self.manifest)
                request.assert_not_called()
        self.pin = original
        self.write_manifest([self.pin, self.pin])
        with self.assertRaisesRegex(ValueError, "duplicate artifact"):
            resolve(self.cache, True, self.manifest)
        self.manifest.write_text('{"schema_version":1,"schema_version":1,"artifacts":[]}')
        with self.assertRaisesRegex(ValueError, "duplicate artifact manifest"):
            resolve(self.cache, manifest_path=self.manifest)
        for malformed in ("[]", "null", "true", '{"schema_version":true,"artifacts":[]}'):
            self.manifest.write_text(malformed)
            with self.assertRaisesRegex(ValueError, "unsupported/empty artifact manifest"):
                resolve(self.cache, manifest_path=self.manifest)

    def response(self, body, url):
        response = io.BytesIO(body)
        response.url = url
        return response

    def test_explicit_download_is_atomic_and_checks_official_checksum(self):
        with patch("urllib.request.urlopen", side_effect=[self.response(self.body, self.pin["url"]),
                   self.response(self.pin["upstream_checksum"].encode(), self.pin["url"] + ".sha1")]) as request:
            result = resolve(self.cache, True, self.manifest)
            self.assertEqual(2, request.call_count)
            self.assertEqual(1, len(result))
        self.assertEqual(self.body, (self.cache / self.pin["filename"]).read_bytes())
        self.assertEqual([self.pin["filename"]], [path.name for path in self.cache.iterdir()])

    def test_failed_or_redirected_download_leaves_no_artifact_or_partial_file(self):
        for responses in [
            [self.response(self.body + b"too much", self.pin["url"])],
            [self.response(self.body, "https://evil.invalid/artifact.jar")],
            [self.response(self.body, self.pin["url"]), self.response(b"0" * 40, self.pin["url"] + ".sha1")],
        ]:
            with patch("urllib.request.urlopen", side_effect=responses):
                with self.assertRaises(ValueError):
                    resolve(self.cache, True, self.manifest)
            self.assertEqual([], list(self.cache.iterdir()))


if __name__ == "__main__":
    unittest.main()
