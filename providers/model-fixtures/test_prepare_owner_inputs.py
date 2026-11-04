"""Small preparation-boundary tests. No actual Gradle/JDK fixture execution or downloads."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("model_input_preparer", Path(__file__).with_name("prepare_owner_inputs.py"))
PREPARER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARER)


class PreparationBoundaryTest(unittest.TestCase):
    def test_selected_files_and_parents_never_dereference_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / "original"
            original.write_bytes(b"selected bytes")
            alias = root / "alias"
            alias.symlink_to(original)
            with self.assertRaises(OSError):
                PREPARER.read_copy(alias)
            parent = root / "parent"
            parent.mkdir()
            (parent / "file").write_bytes(b"selected bytes")
            parent_alias = root / "parent-alias"
            parent_alias.symlink_to(parent, target_is_directory=True)
            with self.assertRaises(OSError):
                PREPARER.read_copy(parent_alias / "file")
            with self.assertRaises(OSError):
                PREPARER.selected_directory(parent_alias)

    def test_private_copies_preserve_empty_directories_and_complete_selected_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            homes = {kind: root / kind for kind in ("gradle", "jdk", "seed")}
            for home in homes.values():
                home.mkdir()
            (homes["gradle"] / "lib").mkdir()
            (homes["gradle"] / "lib/gradle-gradle-cli-main-9.6.1.jar").write_bytes(b"unit artifact")
            (homes["jdk"] / "empty-directory").mkdir()
            executable = homes["jdk"] / "java"
            executable.write_bytes(b"unit executable, never run")
            executable.chmod(0o700)
            (homes["seed"] / "caches").mkdir()
            (homes["seed"] / "caches/selected.jar").write_bytes(b"unit seed artifact")
            (homes["seed"] / "caches/Program.class").write_bytes(b"selected DSL cache bytes, never executed")
            (homes["seed"] / "caches/cache.lock").write_bytes(b"regular lock bookkeeping")
            archive = root / "unit-archive.zip"
            archive.write_bytes(b"unit archive, not the vendor distribution")
            output = root / "private-output"
            options = ["--gradle-home", str(homes["gradle"]), "--java-home", str(homes["jdk"]),
                       "--gradle-seed", str(homes["seed"]), "--gradle-archive", str(archive), "--output", str(output)]
            # Unit-only tiny archive pin; production retains the independent actual vendor pin.
            with patch.object(PREPARER, "GRADLE_ARCHIVE_SHA256", hashlib.sha256(archive.read_bytes()).hexdigest()), \
                    patch.object(PREPARER, "HELPERS", (str(executable),)), contextlib.redirect_stdout(io.StringIO()):
                PREPARER.main(options)
            self.assertEqual(0o700, output.stat().st_mode & 0o777)
            self.assertEqual(0o400, (output / "owner-inputs.json").stat().st_mode & 0o777)
            self.assertEqual(0o500, (output / "jdk/java").stat().st_mode & 0o777)
            self.assertEqual(b"unit executable, never run", (output / "jdk/java").read_bytes())
            manifest = json.loads((output / "owner-inputs.json").read_text())
            self.assertIn({"kind": "jdk", "relativePath": "empty-directory"}, manifest["directories"])
            self.assertTrue((output / "jdk/empty-directory").is_dir())
            self.assertEqual(b"selected DSL cache bytes, never executed", (output / "seed/caches/Program.class").read_bytes())
            self.assertTrue(any(item["kind"] == "seed" and item["relativePath"] == "caches/Program.class" for item in manifest["files"]))
            self.assertEqual([{"kind": "seed", "path": "caches/cache.lock", "reason": "ephemeral cache lock, not producer input"}],
                             manifest["omittedInputEntries"])
            self.assertFalse((output / "seed/caches/cache.lock").exists())
            # A suffix alone cannot turn an alias into reviewed regular lock bookkeeping.
            (homes["seed"] / "caches/alias.lock").symlink_to(homes["seed"] / "caches/selected.jar")
            with patch.object(PREPARER, "GRADLE_ARCHIVE_SHA256", hashlib.sha256(archive.read_bytes()).hexdigest()), \
                    patch.object(PREPARER, "HELPERS", (str(executable),)), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(ValueError, "Only regular cache lock files"):
                    PREPARER.main(options[:-1] + [str(root / "rejected-private-output")])


if __name__ == "__main__":
    unittest.main()
