"""Tiny synthetic-only ZIP/USTAR controls; never launch or extract a product.

All filesystem fixtures are beneath one TemporaryDirectory. Run this file's
suite alone, including under -O; its checks use unittest, never Python assert.
The synthetic installation is expected bytes, not compiled/runtime evidence.
"""

import ast
from collections import Counter
from contextlib import contextmanager
from dataclasses import fields, replace
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile
import zlib

try:
    from . import verify_distribution_archives as verifier
except ImportError:
    import verify_distribution_archives as verifier


FILES = {
    "app/data.txt": b"synthetic public data\n",
    "bin/start": b"synthetic launcher; not executable evidence\n",
    "empty.bin": b"",
}
PREFIX = "example-dist"
ZIP_LOCAL = struct.Struct("<4s5H3L2H")
ZIP_CENTRAL = struct.Struct("<4s6H3L5H2L")
ZIP_END = struct.Struct("<4s4H2LH")


def raw_deflate(data):
    encoder = zlib.compressobj(wbits=-15)
    return encoder.compress(data) + encoder.flush()


def make_zip(entries, *, prelude=b"", central_gap=b"", comment=b"", tail=b"", end_changes=None):
    """Literal tiny ZIP writer; overrides deliberately construct bad fixtures."""
    body = bytearray(prelude)
    central = bytearray()
    for specification in entries:
        item = dict(specification)
        body.extend(item.get("gap_before", b""))
        offset = len(body)
        name = item["name"]
        name = name.encode("utf-8") if isinstance(name, str) else name
        local_name = item.get("local_name", name)
        if isinstance(local_name, str):
            local_name = local_name.encode("utf-8")
        data = item.get("data", b"")
        method = item.get("method", 0)
        payload = item.get("payload", raw_deflate(data) if method == 8 else data)
        descriptor_kind = item.get("descriptor")
        flags = item.get("flags", 0x0800 | (8 if descriptor_kind else 0))
        crc = zlib.crc32(data)
        compressed = item.get("compressed", len(payload))
        expanded = item.get("expanded", len(data))
        needed = item.get("needed", 20)
        kind = item.get("kind", "file")
        mode = item.get("mode", (stat.S_IFDIR | 0o755) if kind == "directory" else (stat.S_IFREG | 0o644))
        local_extra = item.get("local_extra", b"")
        extra = item.get("extra", b"")
        member_comment = item.get("comment", b"")
        local_values = {
            "signature": b"PK\x03\x04", "needed": needed, "flags": flags, "method": method,
            "time": 0, "date": 0, "crc": 0 if descriptor_kind else crc,
            "compressed": 0 if descriptor_kind else compressed, "expanded": 0 if descriptor_kind else expanded,
            "name_size": len(local_name), "extra_size": len(local_extra),
        }
        local_values.update(item.get("local", {}))
        body.extend(ZIP_LOCAL.pack(*local_values.values()))
        body.extend(local_name)
        body.extend(local_extra)
        body.extend(payload)
        if descriptor_kind:
            descriptor = struct.pack("<3L", item.get("descriptor_crc", crc), compressed, expanded)
            if descriptor_kind == "signed":
                descriptor = b"PK\x07\x08" + descriptor
            body.extend(item.get("descriptor_bytes", descriptor))
        body.extend(item.get("gap_after", b""))
        central_values = {
            "signature": b"PK\x01\x02", "made": item.get("made", (3 << 8) | 20),
            "needed": needed, "flags": flags, "method": method, "time": 0, "date": 0,
            "crc": item.get("crc", crc), "compressed": compressed, "expanded": expanded,
            "name_size": len(name), "extra_size": len(extra), "comment_size": len(member_comment),
            "disk": 0, "internal": 0, "external": (mode << 16) | (0x10 if kind == "directory" else 0),
            "offset": offset,
        }
        central_values.update(item.get("central", {}))
        central.extend(ZIP_CENTRAL.pack(*central_values.values()))
        central.extend(name)
        central.extend(extra)
        central.extend(member_comment)
    body.extend(central_gap)
    central_offset = len(body)
    body.extend(central)
    end = {
        "signature": b"PK\x05\x06", "disk": 0, "central_disk": 0,
        "disk_count": len(entries), "count": len(entries), "central_size": len(central),
        "central_offset": central_offset, "comment_size": len(comment),
    }
    end.update(end_changes or {})
    return bytes(body) + ZIP_END.pack(*end.values()) + comment + tail


def tar_octal(value, width):
    return (f"{value:0{width - 1}o}" + "\0").encode("ascii")


def checksum_tar(header):
    header = bytearray(header)
    header[148:156] = b" " * 8
    header[148:156] = f"{sum(header):06o}\0 ".encode("ascii")
    return bytes(header)


def tar_header(item):
    raw = bytearray(512)
    name = item["name"]
    name = name.encode("utf-8") if isinstance(name, str) else name
    if len(name) > 100:
        raise ValueError("synthetic tar helper requires explicit short name/prefix")
    raw[:len(name)] = name
    data = item.get("data", b"")
    kind = item.get("kind", "file")
    mode = item.get("mode", 0o755 if kind == "directory" else 0o644)
    raw[100:108] = tar_octal(mode, 8)
    raw[108:116] = tar_octal(0, 8)
    raw[116:124] = tar_octal(0, 8)
    raw[124:136] = tar_octal(item.get("expanded", len(data)), 12)
    raw[136:148] = tar_octal(0, 12)
    raw[156:157] = item.get("flag", b"5" if kind == "directory" else b"0")
    raw[257:263] = b"ustar\0"
    raw[263:265] = b"00"
    raw[329:337] = tar_octal(0, 8)
    raw[337:345] = tar_octal(0, 8)
    prefix = item.get("prefix", b"")
    prefix = prefix.encode("utf-8") if isinstance(prefix, str) else prefix
    if len(prefix) > 155:
        raise ValueError("synthetic tar prefix too long")
    raw[345:345 + len(prefix)] = prefix
    for start, value in item.get("header_changes", {}).items():
        raw[start:start + len(value)] = value
    return checksum_tar(raw)


def make_tar(entries, *, end_blocks=2, suffix=b""):
    body = bytearray()
    for specification in entries:
        item = dict(specification)
        body.extend(tar_header(item))
        data = item.get("payload", item.get("data", b""))
        body.extend(data)
        padding = (-len(data)) % 512
        body.extend(item.get("padding", b"\0" * padding))
    return bytes(body) + b"\0" * (end_blocks * 512) + suffix


def change_tar(raw, changes, *, recalculate=True):
    header = bytearray(raw[:512])
    for start, value in changes.items():
        header[start:start + len(value)] = value
    if recalculate:
        header = checksum_tar(header)
    return bytes(header) + raw[512:]


def four_bytes_with_crc(target):
    """Small GF(2) solve for the valid unsigned-descriptor signature edge case."""
    zero = zlib.crc32(b"\0" * 4)
    basis = {}
    for bit in range(32):
        value = zlib.crc32((1 << bit).to_bytes(4, "little")) ^ zero
        mask = 1 << bit
        while value:
            pivot = value.bit_length() - 1
            if pivot not in basis:
                basis[pivot] = value, mask
                break
            old_value, old_mask = basis[pivot]
            value, mask = value ^ old_value, mask ^ old_mask
    value, mask = target ^ zero, 0
    while value:
        pivot = value.bit_length() - 1
        if pivot not in basis:
            raise ValueError("CRC target cannot be represented")
        old_value, old_mask = basis[pivot]
        value, mask = value ^ old_value, mask ^ old_mask
    return mask.to_bytes(4, "little")


@unittest.skipUnless(verifier.platform_available(), "POSIX no-follow support unavailable; separate availability controls remain")
class DistributionArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="synthetic-distribution-archives-")
        self.addCleanup(self.temporary.cleanup)
        # Admit only this trusted, newly created fixture root through host temp aliases
        # (for example on macOS), BEFORE creating any adversarial staged/archive paths.
        # The verifier itself must never resolve an untrusted input or output path.
        self.root = Path(self.temporary.name).resolve(strict=True)
        self.installation = self.root / "installation"
        self.installation.mkdir(mode=0o700)
        self.content = dict(FILES)
        self.executables = {"bin/start"}
        self.counter = 0
        for name, data in self.content.items():
            self.new_file(self.installation / name, data, executable=name in self.executables)

    def new_file(self, path, data, *, executable=False):
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o700 if executable else 0o600)
        try:
            position = 0
            while position < len(data):
                position += os.write(fd, data[position:])
        finally:
            os.close(fd)
        return path

    def entries(self, *, prefix=PREFIX, root=False, parents=False, empty=(), method=0):
        rows = []
        if root:
            rows.append({"name": prefix + "/", "kind": "directory"})
        if parents:
            for name in ("app", "bin"):
                rows.append({"name": prefix + "/" + name + "/", "kind": "directory"})
        for name in empty:
            rows.append({"name": prefix + "/" + name + "/", "kind": "directory"})
        for name, data in sorted(self.content.items()):
            rows.append({"name": prefix + "/" + name, "data": data, "method": method,
                         "mode": stat.S_IFREG | (0o755 if name in self.executables else 0o644)})
        return rows

    def archive(self, raw, suffix=".zip"):
        self.counter += 1
        return self.new_file(self.root / f"synthetic-{self.counter}{suffix}", raw)

    def zip(self, rows=None, **changes):
        return self.archive(make_zip(self.entries() if rows is None else rows, **changes))

    def tar(self, rows=None, **changes):
        return self.archive(make_tar(self.entries() if rows is None else rows, **changes), ".tar")

    def verify(self, *archives, **options):
        return verifier.verify(self.installation, list(archives), **options)

    def reject(self, archive, code, *, state="failed", **options):
        result = self.verify(archive, **options)
        self.assertEqual(state, result["state"], result)
        self.assertEqual(code, result["errors"][0]["code"], result)
        self.assertNotEqual("unchanged", result["input_recheck"])
        self.assertTrue(all(row["state"] != "verified" for row in result["archives"]))
        self.assertFalse(result["release_qualified"])
        return result

    def check_valid(self, *archives, **options):
        result = self.verify(*archives, **options)
        self.assertEqual("verified", result["state"], result)
        self.assertEqual([], result["errors"])
        self.assertEqual("unchanged", result["input_recheck"])
        self.assertTrue(all(row["state"] == "verified" for row in result["archives"]))
        return result

    def cli(self, *archives, output=None):
        output = output or self.root / "new-report.json"
        argv = ["--installation", str(self.installation), "--output", str(output)]
        for archive in archives:
            argv += ["--archive", str(archive)]
        with patch("sys.stdout", new_callable=io.StringIO) as stdout:
            status = verifier.main(argv)
        return status, json.loads(stdout.getvalue()), output

    def test_fixture_root_admission_does_not_resolve_verifier_input_or_output_aliases(self):
        physical_base = self.root / "physical-temp-base"
        physical_base.mkdir(mode=0o700)
        alias = self.root / "temp-base-alias"
        os.symlink(physical_base, alias, target_is_directory=True)
        fixture = DistributionArchiveTests()
        self.addCleanup(lambda: self.assertTrue(fixture.doCleanups(), "Nested fixture cleanup must complete"))
        # Exercise the real setUp through a synthetic host-temp alias on every POSIX
        # host; no nested suite is run and no macOS-specific private path is assumed.
        with patch.object(tempfile, "gettempdir", return_value=str(alias)):
            fixture.setUp()
        aliased_root = Path(fixture.temporary.name)
        self.assertEqual(alias, aliased_root.parent)
        self.assertEqual(physical_base / aliased_root.name, fixture.root)
        self.assertNotEqual(aliased_root, fixture.root)
        archives = [fixture.zip(), fixture.tar()]
        fixture.check_valid(*archives)
        status, result, _ = fixture.cli(*archives)
        self.assertEqual(0, status)
        self.assertIs(True, result["output_written"])

        # Canonical fixture enrollment must not become a general verifier bypass:
        # the original alias remains forbidden for stage, archive, and report paths.
        rejected = verifier.verify(aliased_root / "installation", archives)
        self.assertEqual("failed", rejected["state"])
        self.assertEqual([{"code": "input_link_or_special_file", "phase": "installation"}], rejected["errors"])
        self.assertNotEqual("unchanged", rejected["input_recheck"])
        self.assertFalse(rejected["release_qualified"])
        fixture.reject(aliased_root / archives[0].name, "input_link_or_special_file")
        status, result, _ = fixture.cli(*archives, output=aliased_root / "aliased-output.json")
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual({"code": "input_link_or_special_file", "phase": "new_report"}, result["errors"][-1])
        self.assertFalse((fixture.root / "aliased-output.json").exists())

    def test_valid_stored_zip_and_ustar_pair_matches_actual_bytes(self):
        archives = [self.zip(), self.tar()]
        result = self.check_valid(*archives)
        self.assertEqual({"zip32", "ustar"}, {row["format"] for row in result["archives"]})
        self.assertEqual(3, result["installation"]["file_count"])
        for row in result["installation"]["files"]:
            self.assertEqual(len(self.content[row["path"]]), row["bytes"])
            self.assertEqual(hashlib.sha256(self.content[row["path"]]).hexdigest(), row["sha256"])
            self.assertEqual(row["path"] in self.executables, row["executable"])
        for path, row in zip(archives, result["archives"]):
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), row["sha256"])
            self.assertEqual(result["installation"]["inventory_sha256"], row["matched_staged_inventory_sha256"])

    def test_explicit_expected_prefix_binds_both_archives_without_inferring_filenames(self):
        prefix = "reviewed distribution-caf\u00e9"
        archives = [self.zip(self.entries(prefix=prefix)), self.tar(self.entries(prefix=prefix))]
        result = self.check_valid(*archives, expected_prefix=prefix)
        self.assertEqual(prefix, result["expected_prefix"])
        self.assertEqual([prefix, prefix], [row["prefix"] for row in result["archives"]])
        self.assertTrue(all(path.stem != prefix for path in archives))

    def test_expected_prefix_rejects_wrong_or_differing_archive_roots(self):
        for index in (0, 1):
            with self.subTest(wrong_archive=index + 1):
                rows = [self.entries(), self.entries()]
                rows[index] = self.entries(prefix=PREFIX + "-other")
                result = self.verify(self.zip(rows[0]), self.tar(rows[1]), expected_prefix=PREFIX)
                self.assertEqual("failed", result["state"])
                self.assertEqual([{"code": "archive_prefix_mismatch", "phase": "archives",
                                   "archive_index": index + 1}], result["errors"])
                self.assertTrue(all(row["state"] != "verified" for row in result["archives"]))
                self.assertEqual("not_completed", result["input_recheck"])

    def test_omitted_expected_prefix_preserves_explicit_local_comparison_scope(self):
        result = self.check_valid(self.zip(), self.tar(self.entries(prefix="another-safe-prefix")))
        self.assertIsNone(result["expected_prefix"])
        self.assertEqual([PREFIX, "another-safe-prefix"], [row["prefix"] for row in result["archives"]])

    def test_valid_raw_deflate_zip_including_empty_stream(self):
        self.check_valid(self.zip(self.entries(method=8)))

    def test_valid_zip_library_writer_is_compatible_without_library_reader(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for row in self.entries():
                info = zipfile.ZipInfo(row["name"])
                info.create_system = 3
                info.external_attr = row["mode"] << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, row["data"])
        self.check_valid(self.archive(buffer.getvalue()))

    def test_explicit_root_and_nonempty_parents_are_optional(self):
        for root, parents in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(root=root, parents=parents):
                rows = self.entries(root=root, parents=parents)
                self.check_valid(self.zip(rows), self.tar(rows))

    def test_empty_branches_require_every_directory_without_regular_descendants(self):
        (self.installation / "blank/leaf").mkdir(mode=0o700, parents=True)
        rows = self.entries(empty=("blank", "blank/leaf"))
        result = self.check_valid(self.zip(rows), self.tar(rows))
        self.assertEqual(["blank", "blank/leaf"], result["installation"]["empty_directories"])

    def test_missing_empty_parent_fails_even_if_empty_leaf_is_explicit(self):
        (self.installation / "blank/leaf").mkdir(mode=0o700, parents=True)
        self.reject(self.zip(self.entries(empty=("blank/leaf",))), "archive_missing_explicit_empty_directory")

    def test_missing_empty_leaf_fails_even_if_parent_is_explicit(self):
        (self.installation / "blank/leaf").mkdir(mode=0o700, parents=True)
        self.reject(self.tar(self.entries(empty=("blank",))), "archive_missing_explicit_empty_directory")

    def test_empty_sibling_under_nonempty_parent_still_requires_explicit_entry(self):
        (self.installation / "app/blank").mkdir(mode=0o700)
        self.reject(self.zip(), "archive_missing_explicit_empty_directory")
        self.check_valid(self.tar(self.entries(empty=("app/blank",))))

    def test_empty_regular_file_is_distinct_from_absent_file(self):
        rows = [row for row in self.entries() if not row["name"].endswith("/empty.bin")]
        self.reject(self.zip(rows), "archive_missing_regular_file")

    def test_archive_missing_nonempty_file(self):
        self.reject(self.tar(self.entries()[1:]), "archive_missing_regular_file")

    def test_archive_extra_regular_file(self):
        rows = self.entries() + [{"name": PREFIX + "/unexpected", "data": b"extra"}]
        self.reject(self.zip(rows), "archive_extra_regular_file")

    def test_archive_extra_directory(self):
        rows = self.entries() + [{"name": PREFIX + "/unexpected/", "kind": "directory"}]
        self.reject(self.tar(rows), "archive_extra_directory")

    def test_archive_wrong_size(self):
        rows = self.entries()
        rows[0]["data"] += b"!"
        self.reject(self.zip(rows), "archive_file_size_mismatch")

    def test_archive_wrong_sha256_with_matching_size(self):
        rows = self.entries()
        rows[0]["data"] = b"X" * len(rows[0]["data"])
        self.reject(self.tar(rows), "archive_file_sha256_mismatch")

    def test_executable_added_fails_exact_flag(self):
        rows = self.entries()
        rows[0]["mode"] = stat.S_IFREG | 0o744
        self.reject(self.zip(rows), "archive_executable_mismatch")

    def test_executable_removed_fails_exact_flag(self):
        rows = self.entries()
        rows[1]["mode"] = stat.S_IFREG | 0o644
        self.reject(self.tar(rows), "archive_executable_mismatch")

    def test_nonexecutable_permission_differences_are_not_certified(self):
        rows = self.entries()
        rows[0]["mode"] = stat.S_IFREG | 0o400
        self.check_valid(self.zip(rows), self.tar(rows))

    def test_duplicate_regular_file_is_not_deduplicated(self):
        rows = self.entries()
        self.reject(self.zip(rows + [rows[0]]), "duplicate_member_name")

    def test_duplicate_directory_is_not_deduplicated(self):
        rows = self.entries(root=True)
        self.reject(self.tar(rows + [rows[0]]), "duplicate_member_name")

    def test_multiple_prefixes_are_not_stripped_independently(self):
        rows = self.entries()
        rows[1]["name"] = "other-dist/bin/start"
        self.reject(self.zip(rows), "archive_multiple_prefixes")

    def test_prefix_must_not_be_regular_file(self):
        rows = [{"name": PREFIX, "data": b""}] + self.entries()
        self.reject(self.tar(rows), "archive_regular_file_at_prefix")

    def test_case_alias_of_regular_file(self):
        rows = self.entries()
        extra = dict(rows[0], name=PREFIX + "/APP/data.txt")
        self.reject(self.zip(rows + [extra]), "member_case_or_file_directory_alias")

    def test_casefold_expansion_alias_is_rejected(self):
        limits = verifier.Limits()
        namespace = verifier.Namespace(limits, archive=True)
        namespace.add("prefix/Straße/file", "file")
        with self.assertRaises(verifier.VerificationError) as error:
            namespace.add("prefix/STRASSE/other", "file")
        self.assertEqual("member_case_or_file_directory_alias", error.exception.code)

    def test_implicit_parent_cannot_be_a_regular_file(self):
        rows = self.entries() + [{"name": PREFIX + "/app", "data": b""}]
        self.reject(self.tar(rows), "member_case_or_file_directory_alias")

    def test_unsafe_archive_names_are_literal_not_normalized(self):
        names = (
            "../escape", "/absolute", "prefix//double", "prefix/./dot", "prefix/../up",
            "C:/drive", "prefix\\backslash", "prefix/colon:name", "prefix/question?",
            "prefix/star*", "prefix/quote\"", "prefix/pipe|", "prefix/<angle>",
            "prefix/CON", "prefix/con.txt", "prefix/NUL", "prefix/COM1", "prefix/LPT9.txt",
            "prefix/trailing.", "prefix/trailing ", "prefix/.git/config", "prefix/.GIT/config",
            "prefix/non-nfc-e\u0301", "prefix/control\n", "prefix/tab\t", "prefix/escape\x1b",
            "prefix/zero\0", "prefix/bidi\u202e", "prefix/zero-width\u200b", "prefix/\ufeffbom",
        )
        for name in names:
            with self.subTest(name=repr(name)):
                rows = self.entries()
                rows[0]["name"] = name
                self.reject(self.zip(rows), "unsafe_member_name")

    def test_nfc_unicode_paths_match_without_normalization(self):
        name = "app/café.txt"
        self.content[name] = b"tiny unicode-path fixture"
        self.new_file(self.installation / name, self.content[name])
        self.check_valid(self.zip(), self.tar())

    def test_zip_non_utf8_name_is_explicitly_unsupported(self):
        rows = self.entries()
        rows[0].update(name=b"prefix/\x82.txt", flags=0)
        self.reject(self.zip(rows), "unsupported_member_name_encoding", state="unsupported")

    def test_zip_invalid_utf8_name_is_explicitly_unsupported(self):
        rows = self.entries()
        rows[0]["name"] = b"prefix/\xff.txt"
        self.reject(self.zip(rows), "unsupported_member_name_encoding", state="unsupported")

    def test_tar_invalid_utf8_name_is_explicitly_unsupported(self):
        rows = self.entries()
        rows[0]["name"] = b"prefix/\xff.txt"
        self.reject(self.tar(rows), "unsupported_member_name_encoding", state="unsupported")

    def test_surrogate_string_path_fails_without_traceback(self):
        with self.assertRaises(verifier.VerificationError) as error:
            verifier.safe_relative("prefix/\ud800", verifier.Limits())
        self.assertEqual("unsafe_member_name", error.exception.code)

    def test_zip_local_central_name_mismatch(self):
        rows = self.entries()
        rows[0]["local_name"] = PREFIX + "/app/evil.txt"
        self.reject(self.zip(rows), "zip_local_central_name_mismatch")

    def test_zip_local_central_header_mismatches(self):
        for field, value in (("signature", b"NOPE"), ("needed", 10), ("flags", 0), ("method", 8), ("time", 1), ("date", 1)):
            with self.subTest(field=field):
                rows = self.entries()
                rows[0]["local"] = {field: value}
                self.reject(self.zip(rows), "zip_local_central_header_mismatch")

    def test_zip_local_central_crc_and_size_mismatches(self):
        for field, value in (("crc", 0), ("compressed", 0), ("expanded", 0)):
            with self.subTest(field=field):
                rows = self.entries()
                rows[0]["local"] = {field: value}
                self.reject(self.zip(rows), "zip_local_central_size_crc_mismatch")

    def test_zip_declared_crc_is_checked_against_decoded_bytes(self):
        rows = self.entries()
        rows[0].update(crc=0, local={"crc": 0})
        self.reject(self.zip(rows), "zip_crc_mismatch")

    def test_zip_signed_and_unsigned_data_descriptors(self):
        for descriptor in ("signed", "unsigned"):
            with self.subTest(descriptor=descriptor):
                rows = self.entries(method=8)
                for row in rows:
                    row["descriptor"] = descriptor
                self.check_valid(self.zip(rows))

    def test_zip_unsigned_descriptor_crc_equal_to_signature_is_not_misread(self):
        data = four_bytes_with_crc(0x08074B50)
        self.assertEqual(0x08074B50, zlib.crc32(data))
        path = self.installation / "app/data.txt"
        path.write_bytes(data)
        self.content["app/data.txt"] = data
        for descriptor in ("unsigned", "signed"):
            with self.subTest(descriptor=descriptor):
                rows = self.entries()
                rows[0]["descriptor"] = descriptor
                self.check_valid(self.zip(rows))

    def test_zip_data_descriptor_wrong_crc(self):
        rows = self.entries()
        rows[0].update(descriptor="signed", descriptor_crc=0)
        self.reject(self.zip(rows), "zip_data_descriptor_mismatch")

    def test_zip_data_descriptor_truncated_at_central_directory(self):
        rows = self.entries()
        rows[-1].update(descriptor="unsigned", descriptor_bytes=b"\0" * 8)
        self.reject(self.zip(rows), "zip_truncated_data_descriptor")

    def test_zip_descriptor_local_nonzero_wrong_size_is_not_ignored(self):
        rows = self.entries()
        rows[0].update(descriptor="signed", local={"expanded": 1})
        self.reject(self.zip(rows), "zip_local_central_size_crc_mismatch")

    def test_zip_zip64_and_newer_versions_are_unsupported(self):
        for alteration in ({"needed": 45}, {"expanded": 0xFFFFFFFF}, {"compressed": 0xFFFFFFFF}, {"central": {"offset": 0xFFFFFFFF}}):
            with self.subTest(alteration=alteration):
                rows = self.entries()
                rows[0].update(alteration)
                self.reject(self.zip(rows), "unsupported_zip64_or_version", state="unsupported")

    def test_zip_zip64_end_sentinels_are_unsupported_before_central_read(self):
        for alteration in ({"count": 0xFFFF, "disk_count": 0xFFFF}, {"central_size": 0xFFFFFFFF}, {"central_offset": 0xFFFFFFFF}):
            with self.subTest(alteration=alteration):
                self.reject(self.zip(end_changes=alteration), "unsupported_zip64", state="unsupported")

    def test_zip_multivolume_is_explicitly_unsupported(self):
        for alteration in ({"disk": 1}, {"central_disk": 1}, {"disk_count": 2}):
            with self.subTest(alteration=alteration):
                self.reject(self.zip(end_changes=alteration), "unsupported_zip_multivolume", state="unsupported")
        rows = self.entries()
        rows[0]["central"] = {"disk": 1}
        self.reject(self.zip(rows), "unsupported_zip_multivolume", state="unsupported")

    def test_zip_encryption_flags_and_unsupported_compression(self):
        for alteration in ({"flags": 1}, {"flags": 0x800 | 0x40}, {"flags": 0x800 | 2}, {"method": 12}, {"needed": 11}):
            with self.subTest(alteration=alteration):
                rows = self.entries()
                rows[0].update(alteration)
                self.reject(self.zip(rows), "unsupported_zip_flags_compression_or_version", state="unsupported")

    def test_zip_nonunix_creator_and_unknown_attributes_are_unsupported(self):
        for alteration in ({"made": 20}, {"central": {"internal": 2}}, {"central": {"external": (stat.S_IFREG | 0o644) << 16 | 0x80}}):
            with self.subTest(alteration=alteration):
                rows = self.entries()
                rows[0].update(alteration)
                self.reject(self.zip(rows), "unsupported_zip_mode_or_attributes", state="unsupported")

    def test_zip_unknown_extra_metadata_and_zip64_are_not_ignored(self):
        for tag in (0x0001, 0x7075, 0x7875, 0x000D, 0xBEEF):
            for location in ("extra", "local_extra"):
                with self.subTest(tag=hex(tag), location=location):
                    rows = self.entries()
                    rows[0][location] = struct.pack("<HH", tag, 0)
                    code = "unsupported_zip64" if tag == 1 else "unsupported_zip_extra_field"
                    self.reject(self.zip(rows), code, state="unsupported")

    def test_zip_known_timestamp_fields_are_checked_and_not_certified(self):
        timestamps = struct.pack("<HHBI", 0x5455, 5, 1, 1234)
        ntfs = struct.pack("<HH", 0x000A, 32) + b"\0" * 4 + b"\x01\0\x18\0" + b"\0" * 24
        rows = self.entries()
        for row in rows:
            row.update(extra=timestamps + ntfs, local_extra=timestamps + ntfs)
        self.check_valid(self.zip(rows))

    def test_zip_extra_fields_malformed_or_duplicated(self):
        fixtures = (
            (b"\x55", "zip_malformed_extra_field"),
            (struct.pack("<HH", 0x5455, 9) + b"\x01", "zip_malformed_or_duplicate_extra_field"),
            (struct.pack("<HHB", 0x5455, 1, 8), "zip_malformed_timestamp_extra"),
            (struct.pack("<HHB", 0x5455, 1, 1), "zip_malformed_timestamp_extra"),
            (struct.pack("<HH", 0x000A, 0), "zip_malformed_ntfs_timestamp_extra"),
            (struct.pack("<HHB", 0x5455, 1, 0) * 2, "zip_malformed_or_duplicate_extra_field"),
        )
        for extra, code in fixtures:
            with self.subTest(extra=extra.hex()):
                rows = self.entries()
                rows[0]["extra"] = extra
                self.reject(self.zip(rows), code)

    def test_zip_sfx_and_hidden_local_gaps_are_not_permitted(self):
        self.reject(self.zip(prelude=b"stub"), "zip_overlapping_or_unaccounted_local_bytes")
        rows = self.entries()
        rows[1]["gap_before"] = b"hidden"
        self.reject(self.zip(rows), "zip_overlapping_or_unaccounted_local_bytes")
        self.reject(self.zip(central_gap=b"hidden"), "zip_unaccounted_local_bytes")

    def test_zip_overlapping_local_offsets_are_not_permitted(self):
        rows = self.entries()
        rows.append(dict(rows[0], central={"offset": 0}))
        self.reject(self.zip(rows), "zip_overlapping_or_unaccounted_local_bytes")

    def test_zip_central_count_and_extent_mismatches(self):
        self.reject(self.zip(end_changes={"disk_count": 4, "count": 4}), "zip_central_count_mismatch")
        self.reject(self.zip(end_changes={"central_size": 1}), "zip_unaccounted_central_or_end_bytes")
        rows = self.entries()
        rows[0]["central"] = {"signature": b"NOPE"}
        self.reject(self.zip(rows), "zip_central_count_mismatch")

    def test_zip_truncated_or_trailing_end_record_is_not_normalized(self):
        raw = make_zip(self.entries())
        self.reject(self.archive(raw[:20]), "zip_missing_end_record")
        self.reject(self.archive(raw[:-1]), "zip_missing_or_ambiguous_end_record")
        self.reject(self.archive(raw + b"extra"), "zip_missing_or_ambiguous_end_record")

    def test_zip_ambiguous_end_records_are_rejected(self):
        raw = make_zip(self.entries())
        second = ZIP_END.pack(b"PK\x05\x06", 0, 0, 0, 0, 0, 0, 0)
        first = bytearray(raw)
        struct.pack_into("<H", first, len(first) - 2, len(second))
        self.reject(self.archive(bytes(first) + second), "zip_missing_or_ambiguous_end_record")

    def test_zip_stored_compressed_size_must_equal_actual_size(self):
        rows = self.entries()
        rows[0]["payload"] = rows[0]["data"] + b"x"
        self.reject(self.zip(rows), "zip_stored_size_mismatch")

    def test_zip_deflate_truncated_trailing_and_invalid_streams(self):
        data = self.content["app/data.txt"]
        variants = (
            (raw_deflate(data)[:-1], "zip_truncated_compressed_stream"),
            (raw_deflate(data) + b"payload", "zip_trailing_compressed_data"),
            (b"\xff" * 3, "zip_invalid_compressed_stream"),
            (raw_deflate(b""), "archive_actual_member_size_mismatch"),
        )
        for payload, code in variants:
            with self.subTest(code=code):
                rows = self.entries(method=8)
                rows[0]["payload"] = payload
                self.reject(self.zip(rows), code)

    def test_zip_actual_expansion_above_declared_size_is_rejected(self):
        rows = self.entries(method=8)
        rows[0]["payload"] = raw_deflate(rows[0]["data"] + b"hidden expansion")
        self.reject(self.zip(rows), "archive_actual_member_bytes_mismatch_or_limit")

    def test_zip_empty_declared_member_cannot_hide_output(self):
        rows = self.entries(method=8)
        rows[-1]["payload"] = raw_deflate(b"hidden")
        self.reject(self.zip(rows), "archive_actual_member_bytes_mismatch_or_limit")

    def test_chunked_staged_reads_and_bounded_deflate_output(self):
        data = b"A" * (verifier.CHUNK_BYTES + 17)
        (self.installation / "app/data.txt").write_bytes(data)
        self.content["app/data.txt"] = data
        self.check_valid(self.zip(self.entries(method=8)), self.tar())

    def test_archive_links_and_special_modes_are_rejected(self):
        for file_type in (stat.S_IFLNK, stat.S_IFIFO, stat.S_IFCHR, stat.S_IFBLK, stat.S_IFSOCK):
            for format_name in ("zip", "tar"):
                with self.subTest(file_type=file_type, format_name=format_name):
                    rows = self.entries()
                    rows[0]["mode"] = file_type | 0o644
                    archive = self.zip(rows) if format_name == "zip" else self.tar(rows)
                    self.reject(archive, "archive_link_or_special_mode")

    def test_unsafe_permissions_are_rejected_without_chmod(self):
        for permissions in (0o4644, 0o2644, 0o1644, 0o664, 0o646, 0o244, 0):
            for format_name in ("zip", "tar"):
                with self.subTest(permissions=oct(permissions), format_name=format_name):
                    rows = self.entries()
                    rows[0]["mode"] = stat.S_IFREG | permissions
                    archive = self.zip(rows) if format_name == "zip" else self.tar(rows)
                    self.reject(archive, "unsafe_file_mode")

    def test_zip_missing_file_type_bits_are_not_assumed_regular(self):
        rows = self.entries()
        rows[0]["mode"] = 0o644
        self.reject(self.zip(rows), "archive_link_or_special_mode")

    def test_zip_directory_type_name_attribute_and_data_must_agree(self):
        variants = (
            ({"name": PREFIX + "/app/", "kind": "directory", "central": {"external": (stat.S_IFDIR | 0o755) << 16}}, "zip_directory_attribute_mismatch"),
            ({"name": PREFIX + "/app", "kind": "directory"}, "zip_directory_name_type_mismatch"),
            ({"name": PREFIX + "/app/", "data": b""}, "file_name_has_directory_suffix"),
            ({"name": PREFIX + "/app/", "kind": "directory", "data": b"x"}, "archive_directory_has_data"),
        )
        for row, code in variants:
            with self.subTest(code=code):
                self.reject(self.zip([row] + self.entries()), code)

    def test_tar_standard_prefix_field_and_null_regular_type_are_accepted(self):
        rows = self.entries()
        for row in rows:
            prefix, name = row["name"].rsplit("/", 1)
            row.update(name=name, prefix=prefix, flag=b"\0")
        self.check_valid(self.tar(rows))

    def test_tar_untyped_and_consistent_type_bits_are_both_accepted(self):
        rows = self.entries(root=True)
        for row in rows:
            row["mode"] = (row.get("mode", 0o755)) & 0o7777
        self.check_valid(self.tar(rows))
        for row in rows:
            if row.get("kind") == "directory":
                row["mode"] = stat.S_IFDIR | 0o755
            else:
                row["mode"] |= stat.S_IFREG
        self.check_valid(self.tar(rows))

    def test_tar_directory_may_omit_trailing_slash_but_not_carry_data(self):
        row = {"name": PREFIX + "/app", "kind": "directory"}
        self.check_valid(self.tar([row] + self.entries()))
        self.reject(self.tar([dict(row, data=b"x")] + self.entries()), "archive_directory_has_data")

    def test_tar_inconsistent_type_and_mode_are_rejected(self):
        row = {"name": PREFIX + "/app/", "kind": "directory", "mode": stat.S_IFREG | 0o755}
        self.reject(self.tar([row] + self.entries()), "archive_link_or_special_mode")

    def test_tar_directory_requires_owner_read_and_execute(self):
        for mode in (0o400, 0o100, 0o722, 0o1755):
            with self.subTest(mode=oct(mode)):
                row = {"name": PREFIX + "/app/", "kind": "directory", "mode": mode}
                self.reject(self.tar([row] + self.entries()), "unsafe_file_mode")

    def test_tar_checksum_mismatch_is_not_unsupported_format(self):
        raw = make_tar(self.entries())
        self.reject(self.archive(change_tar(raw, {0: b"X"}, recalculate=False), ".tar"), "tar_header_checksum_mismatch")

    def test_tar_magic_and_version_are_explicitly_unsupported(self):
        raw = make_tar(self.entries())
        for changes in ({257: b"ustar "}, {263: b" \0"}, {257: b"\0" * 8}):
            with self.subTest(changes=changes):
                self.reject(self.archive(change_tar(raw, changes), ".tar"), "unsupported_tar_format", state="unsupported")

    def test_tar_pax_gnu_sparse_and_longname_extensions_are_unsupported(self):
        for flag in (b"x", b"g", b"L", b"K", b"S"):
            with self.subTest(flag=flag):
                rows = self.entries()
                rows[0]["flag"] = flag
                self.reject(self.tar(rows), "unsupported_tar_extension_or_sparse", state="unsupported")

    def test_tar_links_devices_fifo_and_unknown_types_are_rejected(self):
        for flag in (b"1", b"2", b"3", b"4", b"6", b"7", b"Z"):
            with self.subTest(flag=flag):
                rows = self.entries()
                rows[0]["flag"] = flag
                self.reject(self.tar(rows), "tar_link_or_special_member")

    def test_tar_link_and_device_metadata_are_not_ignored_for_regular_files(self):
        raw = make_tar(self.entries())
        for changes in ({157: b"target"}, {329: tar_octal(1, 8)}, {337: tar_octal(1, 8)}):
            with self.subTest(changes=changes):
                self.reject(self.archive(change_tar(raw, changes), ".tar"), "tar_link_or_device_metadata")

    def test_tar_base256_numeric_fields_are_explicitly_unsupported(self):
        raw = make_tar(self.entries())
        for start, width in ((100, 8), (108, 8), (116, 8), (124, 12), (136, 12), (329, 8)):
            with self.subTest(start=start):
                changes = {start: b"\x80" + b"\0" * (width - 1)}
                self.reject(self.archive(change_tar(raw, changes), ".tar"), "unsupported_tar_numeric_encoding", state="unsupported")

    def test_tar_invalid_octal_fields_are_rejected(self):
        raw = make_tar(self.entries())
        for start, width in ((100, 8), (108, 8), (116, 8), (124, 12), (136, 12), (329, 8), (337, 8)):
            with self.subTest(start=start):
                self.reject(self.archive(change_tar(raw, {start: b"9" + b"\0" * (width - 1)}), ".tar"), "tar_invalid_octal_number")

    def test_tar_name_prefix_user_group_and_header_padding_are_checked(self):
        raw = make_tar(self.entries())
        for changes in ({90: b"X"}, {350: b"X"}, {270: b"X"}, {302: b"X"}):
            with self.subTest(changes=changes):
                self.reject(self.archive(change_tar(raw, changes), ".tar"), "tar_nonzero_name_padding")
        self.reject(self.archive(change_tar(raw, {500: b"X"}), ".tar"), "tar_nonzero_header_padding")

    def test_tar_data_padding_must_be_zero(self):
        rows = self.entries()
        rows[0]["padding"] = b"X" + b"\0" * ((-len(rows[0]["data"])) % 512 - 1)
        self.reject(self.tar(rows), "tar_nonzero_member_padding")

    def test_tar_alignment_truncation_and_missing_terminator(self):
        raw = make_tar(self.entries())
        self.reject(self.archive(raw[:-1], ".tar"), "tar_truncated_or_unaligned")
        self.reject(self.archive(raw[:512], ".tar"), "tar_truncated_or_unaligned")
        self.reject(self.tar(end_blocks=0), "tar_missing_terminator")

    def test_tar_two_zero_end_blocks_and_zero_only_trailing_records(self):
        self.reject(self.tar(end_blocks=1), "tar_missing_second_end_block")
        self.reject(self.tar(suffix=b"X" + b"\0" * 511), "tar_payload_after_terminator")
        self.check_valid(self.tar(end_blocks=4))

    def test_tar_member_extent_cannot_consume_past_archive(self):
        rows = self.entries()
        rows[0]["expanded"] = 10000
        self.reject(self.tar(rows), "tar_member_extent_truncated")

    def test_empty_zip_and_tar_archives_are_non_success(self):
        self.reject(self.zip([]), "zip_member_count_limit_or_empty")
        self.reject(self.tar([]), "tar_empty_archive")

    def test_compressed_tar_and_unknown_suffix_are_explicitly_unsupported(self):
        raw = make_tar(self.entries())
        for suffix in (".tar.gz", ".tgz", ".tar.bz2", ".tar.xz"):
            with self.subTest(suffix=suffix):
                self.reject(self.archive(raw, suffix), "unsupported_tar_compression", state="unsupported")
        self.reject(self.archive(raw, ".bin"), "unsupported_archive_format", state="unsupported")

    def test_archive_argument_count_and_duplicates_are_bounded(self):
        archive = self.zip()
        self.assertEqual("archive_argument_count_limit_or_empty", self.verify()["errors"][0]["code"])
        self.assertEqual("duplicate_archive_argument", self.verify(archive, archive)["errors"][0]["code"])
        result = self.verify(archive, self.tar(), limits=replace(verifier.Limits(), max_archives=1))
        self.assertEqual("archive_argument_count_limit_or_empty", result["errors"][0]["code"])

    def test_invalid_limits_never_open_inputs_and_cannot_raise_ceiling(self):
        for field in fields(verifier.Limits()):
            for value in (0, -1, True, getattr(verifier.Limits(), field.name) + 1):
                with self.subTest(field=field.name, value=value), patch.object(verifier.os, "open") as opened:
                    limits = replace(verifier.Limits(), **{field.name: value})
                    result = self.verify(self.root / "absent.zip", limits=limits)
                    self.assertEqual("invalid_limits", result["errors"][0]["code"])
                    opened.assert_not_called()

    def test_staged_file_count_entry_count_and_byte_limits(self):
        archive = self.zip()
        cases = (
            ({"max_files": 2}, "staged_file_count_limit"),
            ({"max_entries": 1}, "staged_entry_limit"),
            ({"max_file_bytes": 1}, "staged_file_bytes_limit"),
            ({"max_staged_bytes": 1}, "staged_total_bytes_limit"),
        )
        for values, code in cases:
            with self.subTest(values=values):
                self.reject(archive, code, limits=replace(verifier.Limits(), **values))

    def test_archive_compressed_and_aggregate_bytes_are_bounded(self):
        zip_path, tar_path = self.zip(), self.tar()
        self.reject(zip_path, "archive_compressed_bytes_limit", limits=replace(verifier.Limits(), max_archive_bytes=zip_path.stat().st_size - 1))
        total = zip_path.stat().st_size + tar_path.stat().st_size
        result = self.verify(zip_path, tar_path, limits=replace(verifier.Limits(), max_total_archive_bytes=total - 1))
        self.assertEqual("total_archive_compressed_bytes_limit", result["errors"][0]["code"])

    def test_archive_entry_count_is_checked_before_member_materialization(self):
        rows = self.entries(root=True, parents=True)
        for archive, code in ((self.zip(rows), "zip_member_count_limit_or_empty"), (self.tar(rows), "tar_member_count_limit")):
            with self.subTest(code=code):
                self.reject(archive, code, limits=replace(verifier.Limits(), max_entries=5))

    def test_zip_central_directory_is_bounded_before_parse(self):
        self.reject(self.zip(), "zip_central_directory_bytes_limit", limits=replace(verifier.Limits(), max_zip_central_bytes=1))

    def test_archive_member_declared_bytes_have_independent_limit(self):
        rows = self.entries()
        rows[0]["expanded"] = 100
        for archive in (self.zip(rows), self.tar(rows)):
            with self.subTest(suffix=archive.suffix):
                self.reject(archive, "archive_member_bytes_limit", limits=replace(verifier.Limits(), max_file_bytes=50))

    def test_archive_declared_decompressed_totals_are_bounded(self):
        total = sum(map(len, self.content.values()))
        archive1, archive2 = self.zip(), self.tar()
        self.reject(archive1, "archive_decompressed_bytes_limit", limits=replace(verifier.Limits(), max_decompressed_bytes=total - 1))
        result = self.verify(archive1, archive2, limits=replace(verifier.Limits(), max_total_decompressed_bytes=2 * total - 1))
        self.assertEqual("total_decompressed_bytes_limit", result["errors"][0]["code"])

    def test_actual_decoded_aggregate_is_independently_bounded(self):
        archive = self.zip()
        budget = verifier.Budget(replace(verifier.Limits(), max_total_decompressed_bytes=3))
        with verifier.HeldPath(archive, directory=False, budget=budget) as held:
            member = verifier.zip_members(held, verifier.Budget(verifier.Limits()))[0]
            with self.assertRaises(verifier.VerificationError) as error:
                verifier.hash_member(held, member, budget)
        self.assertEqual("actual_total_decompressed_bytes_limit", error.exception.code)

    def test_relative_depth_full_path_and_component_limits_are_downward_only(self):
        cases = (
            ("a/b", {"max_depth": 1}),
            ("abcd/efgh", {"max_path_bytes": 8}),
            ("abcde", {"max_component_bytes": 4}),
            ("ééé", {"max_component_bytes": 5}),
        )
        for path, values in cases:
            with self.subTest(path=path), self.assertRaises(verifier.VerificationError) as error:
                verifier.safe_relative(path, replace(verifier.Limits(), **values))
            self.assertEqual("unsafe_member_name", error.exception.code)

    def test_depth_limit_includes_archive_prefix(self):
        self.reject(self.zip(), "unsafe_member_name", limits=replace(verifier.Limits(), max_depth=2))

    def test_expanded_namespace_has_its_own_bound(self):
        namespace = verifier.Namespace(replace(verifier.Limits(), max_entries=1), archive=True)
        with self.assertRaises(verifier.VerificationError) as error:
            namespace.add("prefix/parent/file", "file")
        self.assertEqual("expanded_namespace_entry_limit", error.exception.code)

    def test_best_effort_deadline_is_non_success_not_corruption(self):
        archive = self.zip()
        with patch.object(verifier.time, "monotonic", side_effect=[0, 2]), \
                patch.object(verifier, "platform_available", return_value=True), patch.object(verifier.os, "open") as opened:
            self.reject(archive, "time_limit", state="unavailable", limits=replace(verifier.Limits(), max_seconds=1))
            opened.assert_not_called()

    def test_staged_symlink_is_not_followed(self):
        outside = self.new_file(self.root / "outside-private-sentinel", b"not archive content")
        os.symlink(outside, self.installation / "linked")
        self.reject(self.zip(), "input_link_or_special_file")
        self.assertEqual(b"not archive content", outside.read_bytes())

    def test_staged_hardlink_is_not_regular_single_identity(self):
        os.link(self.installation / "app/data.txt", self.installation / "hardlinked")
        self.reject(self.zip(), "input_link_or_special_file")

    def test_archive_symlink_and_hardlink_are_rejected(self):
        archive = self.zip()
        linked = self.root / "linked.zip"
        os.symlink(archive, linked)
        self.reject(linked, "input_link_or_special_file")
        linked.unlink()
        os.link(archive, linked)
        self.reject(linked, "input_link_or_special_file")

    def test_symlinked_host_parent_is_not_followed(self):
        actual = self.root / "actual"
        actual.mkdir(mode=0o700)
        archive = self.new_file(actual / "fixture.zip", make_zip(self.entries()))
        alias = self.root / "alias"
        os.symlink(actual, alias, target_is_directory=True)
        self.reject(alias / archive.name, "input_link_or_special_file")

    def test_staged_unsafe_modes_and_special_type_are_checked_before_open(self):
        original = verifier.os.stat
        archive = self.zip()
        for mode in (stat.S_IFREG | 0o664, stat.S_IFREG | 0o4644, stat.S_IFIFO | 0o644):
            def synthetic_stat(path, *args, **kwargs):
                value = original(path, *args, **kwargs)
                if path == "data.txt" and "dir_fd" in kwargs:
                    parts = list(value)
                    parts[0] = mode
                    return os.stat_result(parts)
                return value
            with self.subTest(mode=oct(mode)), patch.object(verifier, "platform_available", return_value=True), \
                    patch.object(verifier.os, "stat", side_effect=synthetic_stat):
                code = "input_link_or_special_file" if stat.S_ISFIFO(mode) else "unsafe_file_mode"
                self.reject(archive, code)

    def test_staged_case_alias_is_rejected(self):
        # Case-insensitive filesystems cannot create distinct app/APP siblings.
        # Inject only the second enumeration spelling, backed by real child metadata,
        # so the staged walker must still reject the alias on every supported host.
        (self.installation / "Alias").mkdir(mode=0o700)
        archive = self.zip()
        original_scan, original_stat = verifier.os.scandir, verifier.os.stat
        initial = self.installation.stat()
        identity = initial.st_dev, initial.st_ino
        injected = False
        alias_stats = 0

        def installation_fd(fd):
            current = os.fstat(fd)
            return (current.st_dev, current.st_ino) == identity

        @contextmanager
        def aliased_scan(fd):
            nonlocal injected
            with original_scan(fd) as scan:
                if installation_fd(fd):
                    children = list(scan) + [SimpleNamespace(name="alias")]
                    injected = True
                    yield iter(children)
                else:
                    yield scan

        def aliased_stat(path, *args, **kwargs):
            nonlocal alias_stats
            fd = kwargs.get("dir_fd")
            if path == "alias" and fd is not None and installation_fd(fd):
                alias_stats += 1
                return original_stat("Alias", *args, **kwargs)
            return original_stat(path, *args, **kwargs)

        with patch.object(verifier, "platform_available", return_value=True), \
                patch.object(verifier.os, "scandir", side_effect=aliased_scan), \
                patch.object(verifier.os, "stat", side_effect=aliased_stat):
            self.reject(archive, "member_case_or_file_directory_alias")
        self.assertTrue(injected)
        self.assertEqual(1, alias_stats)

    def test_staged_unsafe_name_is_rejected_without_raw_diagnostic(self):
        self.new_file(self.installation / "escape\x1bprivate", b"private bytes")
        result = self.reject(self.zip(), "unsafe_member_name")
        self.assertNotIn("private", json.dumps(result))
        self.assertNotIn("\\u001b", json.dumps(result))

    def test_no_regular_staged_files_is_not_success(self):
        other = self.root / "empty-installation"
        other.mkdir(mode=0o700)
        result = verifier.verify(other, [self.zip()])
        self.assertEqual("staged_no_regular_files", result["errors"][0]["code"])

    def test_stat_to_open_file_growth_cannot_undercount_staged_budget(self):
        archive = self.zip()
        original = verifier._open_child
        injected = False
        def changed_before_inner_stat(parent, name, directory, budget, *, policy):
            nonlocal injected
            if name == "data.txt" and not injected:
                injected = True
                (self.installation / "app/data.txt").write_bytes(b"X" * 100)
            return original(parent, name, directory, budget, policy=policy)
        with patch.object(verifier, "_open_child", side_effect=changed_before_inner_stat):
            self.reject(archive, "staged_file_changed_before_open", state="stale", limits=replace(verifier.Limits(), max_staged_bytes=70))

    def test_same_name_leaf_substitution_during_hash_is_detected(self):
        archive = self.zip()
        original = verifier._hash_staged
        injected = False
        def substituted(fd, initial, budget):
            nonlocal injected
            value = original(fd, initial, budget)
            if not injected:
                injected = True
                target = self.installation / "app/data.txt"
                target.unlink()
                self.new_file(target, self.content["app/data.txt"])
            return value
        with patch.object(verifier, "_hash_staged", side_effect=substituted):
            self.reject(archive, "staged_file_binding_drift", state="stale")

    def test_staged_file_mutation_during_read_is_detected(self):
        archive = self.zip()
        original = verifier.os.read
        injected = False
        def changed_during_read(fd, count):
            nonlocal injected
            data = original(fd, count)
            if not injected and data:
                injected = True
                (self.installation / "app/data.txt").write_bytes(b"X" * (len(self.content["app/data.txt"]) - 1))
            return data
        with patch.object(verifier.os, "read", side_effect=changed_during_read):
            self.reject(archive, "staged_file_stat_or_size_drift", state="stale")

    def test_staged_same_size_overwrite_during_read_is_detected(self):
        archive = self.zip()
        original = verifier.os.read
        injected = False
        def changed_during_read(fd, count):
            nonlocal injected
            data = original(fd, count)
            if not injected and data:
                injected = True
                (self.installation / "app/data.txt").write_bytes(b"X" * len(self.content["app/data.txt"]))
            return data
        with patch.object(verifier.os, "read", side_effect=changed_during_read):
            result = self.verify(archive)
        self.assertTrue(injected, "The same-size overwrite must actually occur")
        # Timestamp coalescing may defer rejection to the mandatory full rehash.
        # Either legitimate early metadata drift or final inventory drift must fail.
        self.assertEqual("stale", result["state"])
        self.assertIn(result["errors"][0]["code"], {"staged_file_stat_or_size_drift", "staged_inventory_drift"})
        self.assertNotEqual("unchanged", result["input_recheck"])
        self.assertTrue(all(row["state"] != "verified" for row in result["archives"]))
        self.assertFalse(result["release_qualified"])

    def test_staged_parent_rename_during_walk_detects_ctime_or_binding_drift(self):
        archive = self.zip()
        original = verifier._hash_staged
        injected = False
        def substituted(fd, initial, budget):
            nonlocal injected
            value = original(fd, initial, budget)
            if not injected:
                injected = True
                (self.installation / "app").rename(self.installation / "app-retired")
                self.new_file(self.installation / "app/data.txt", self.content["app/data.txt"])
            return value
        with patch.object(verifier, "_hash_staged", side_effect=substituted):
            result = self.verify(archive)
        # Some filesystems coalesce the rename's ctime with its creation time;
        # either the earlier metadata check or the mandatory path check must fail.
        self.assertEqual("stale", result["state"])
        self.assertIn(result["errors"][0]["code"], {"staged_directory_walk_drift", "staged_directory_binding_drift"})
        self.assertNotEqual("unchanged", result["input_recheck"])
        self.assertTrue(all(row["state"] != "verified" for row in result["archives"]))
        self.assertFalse(result["release_qualified"])

    def test_staged_parent_substitution_after_walk_fails_path_binding(self):
        archive = self.zip()
        original = verifier.os.stat
        parent_identity = self.installation.stat().st_dev, self.installation.stat().st_ino
        calls = 0
        def substituted(path, *args, **kwargs):
            nonlocal calls
            if path == "app" and "dir_fd" in kwargs:
                parent = os.fstat(kwargs["dir_fd"])
                if (parent.st_dev, parent.st_ino) == parent_identity:
                    calls += 1
                    if calls == 3:
                        (self.installation / "app").rename(self.installation / "app-retired")
                        self.new_file(self.installation / "app/data.txt", self.content["app/data.txt"])
            return original(path, *args, **kwargs)
        with patch.object(verifier, "platform_available", return_value=True), patch.object(verifier.os, "stat", side_effect=substituted):
            self.reject(archive, "staged_directory_binding_drift", state="stale")
        self.assertEqual(3, calls)

    def test_host_ancestor_substitution_fails_final_no_follow_binding(self):
        container = self.root / "container"
        container.mkdir(mode=0o700)
        self.installation.rename(container / "installation")
        self.installation = container / "installation"
        archive = self.zip()
        original = verifier.verify_archive
        def substituted(held, snapshot, budget):
            value = original(held, snapshot, budget)
            container.rename(self.root / "container-retired")
            container.mkdir(mode=0o700)
            return value
        with patch.object(verifier, "verify_archive", side_effect=substituted):
            self.reject(archive, "input_path_binding_drift", state="stale")

    def test_archive_mutation_during_pread_fails_before_and_after_stat_check(self):
        archive = self.zip()
        original = verifier.os.pread
        original_bytes = archive.read_bytes()
        injected = False
        def changed(fd, count, offset):
            nonlocal injected
            data = original(fd, count, offset)
            if not injected:
                injected = True
                archive.write_bytes(original_bytes + b"drift")
            return data
        with patch.object(verifier.os, "pread", side_effect=changed):
            self.reject(archive, "archive_stat_drift", state="stale")

    def test_archive_short_read_is_stale_not_valid_empty_content(self):
        archive = self.zip()
        with patch.object(verifier.os, "pread", return_value=b""):
            self.reject(archive, "archive_short_read", state="stale")

    def test_final_staged_hash_and_complete_inventory_are_recomputed(self):
        archive = self.zip()
        original = verifier.verify_archive
        def changed_after_archive(held, snapshot, budget):
            value = original(held, snapshot, budget)
            (self.installation / "app/data.txt").write_bytes(b"X" * len(self.content["app/data.txt"]))
            return value
        with patch.object(verifier, "verify_archive", side_effect=changed_after_archive):
            self.reject(archive, "staged_inventory_drift", state="stale")

    def test_each_archive_is_rehashed_before_after_and_globally(self):
        archives = [self.zip(), self.tar()]
        original_digest, original_inventory = verifier.HeldPath.digest, verifier.inventory
        digests = []
        inventories = []
        def digest(held):
            digests.append(held.path)
            return original_digest(held)
        def inventory(held, budget):
            inventories.append(held.path)
            return original_inventory(held, budget)
        with patch.object(verifier.HeldPath, "digest", new=digest), patch.object(verifier, "inventory", side_effect=inventory):
            self.check_valid(*archives)
        self.assertEqual([self.installation, self.installation], inventories)
        for archive in archives:
            self.assertEqual(3, digests.count(archive))

    def test_after_decoding_archive_digest_mismatch_cannot_succeed(self):
        archive = self.zip()
        original = verifier.HeldPath.digest
        calls = 0
        def digest(held):
            nonlocal calls
            calls += 1
            actual = original(held)
            return "0" * 64 if calls == 2 else actual
        with patch.object(verifier.HeldPath, "digest", new=digest):
            self.reject(archive, "archive_content_drift", state="stale")

    def test_global_archive_digest_mismatch_cannot_succeed(self):
        archive = self.zip()
        original = verifier.HeldPath.digest
        calls = 0
        def digest(held):
            nonlocal calls
            calls += 1
            actual = original(held)
            return "0" * 64 if calls == 3 else actual
        with patch.object(verifier.HeldPath, "digest", new=digest):
            self.reject(archive, "archive_final_content_drift", state="stale")

    def test_original_archive_failure_is_not_overwritten_by_final_recheck(self):
        rows = self.entries()
        rows[0]["data"] += b"x"
        original = verifier.inventory
        count = 0
        def inventory(held, budget):
            nonlocal count
            count += 1
            if count > 1:
                raise RuntimeError("final recheck must not replace original failure")
            return original(held, budget)
        with patch.object(verifier, "inventory", side_effect=inventory):
            self.reject(self.zip(rows), "archive_file_size_mismatch")
        self.assertEqual(1, count)

    def test_handled_generic_context_exit_cannot_leave_verified_state(self):
        archive = self.zip()
        base_stack = verifier.ExitStack
        cleanup_calls = []
        for error_type in (TypeError, ValueError, struct.error):
            class FailingExitStack(base_stack):
                failure_type = error_type
                def __exit__(self, *arguments):
                    super().__exit__(*arguments)
                    cleanup_calls.append(self.failure_type.__name__)
                    raise self.failure_type("synthetic post-verification cleanup failure")
            with self.subTest(error=error_type.__name__), patch.object(verifier, "ExitStack", FailingExitStack):
                result = self.verify(archive)
                self.assertEqual("failed", result["state"])
                self.assertEqual("unchanged", result["input_recheck"], "The regression must reach completed input rechecks")
                self.assertEqual([{"code": "verification_invalid_input", "phase": "final_input_recheck"}], result["errors"])
                self.assertFalse(result["release_qualified"])
        self.assertEqual(["TypeError", "ValueError", "error"], cleanup_calls)

    def test_cli_context_exit_generic_error_is_nonzero(self):
        archive = self.zip()
        base_stack = verifier.ExitStack
        class FailingExitStack(base_stack):
            def __exit__(self, *arguments):
                super().__exit__(*arguments)
                raise ValueError("synthetic post-verification cleanup failure")
        with patch.object(verifier, "ExitStack", FailingExitStack):
            status, result, output = self.cli(archive)
        self.assertEqual(1, status)
        self.assertEqual("failed", result["state"])
        self.assertTrue(result["output_written"], "A delivered failure report must not imply CLI success")
        self.assertEqual("verification_invalid_input", result["errors"][0]["code"])
        self.assertEqual("failed", json.loads(output.read_text())["state"])
        self.assertFalse(result["release_qualified"])

    def test_read_only_verification_preserves_inputs_and_creates_nothing(self):
        archives = [self.zip(), self.tar()]
        before_names = sorted(str(path.relative_to(self.root)) for path in self.root.rglob("*"))
        before = {path: (path.read_bytes(), verifier._fingerprint(path.stat()))
                  for path in self.root.rglob("*") if path.is_file()}
        self.check_valid(*archives)
        self.assertEqual(before_names, sorted(str(path.relative_to(self.root)) for path in self.root.rglob("*")))
        for path, (data, fingerprint) in before.items():
            self.assertEqual(data, path.read_bytes())
            self.assertEqual(fingerprint, verifier._fingerprint(path.stat()))

    def test_reports_never_emit_contents_full_host_paths_or_trust_claims(self):
        result = self.check_valid(self.zip(), self.tar())
        serialized = json.dumps(result)
        self.assertNotIn(str(self.root), serialized)
        for content in self.content.values():
            if content:
                self.assertNotIn(content.decode("ascii").strip(), serialized)
        for flag in ("atomic_snapshot_or_hostile_writer_containment", "hard_deadline_enforced", "checksum_alone_is_trust",
                     "compiled_code_or_runtime_proof", "historical_build_source_binding", "release_qualified",
                     "installation_performed", "raw_contents_emitted"):
            self.assertIs(False, result[flag])
        self.assertEqual("not_assessed", result["license_review"])
        for activity in ("extraction", "signing", "publication"):
            self.assertEqual("not_performed", result[activity])

    def test_cli_success_writes_only_one_new_readback_checked_report(self):
        archives = [self.zip(), self.tar()]
        inputs = {path: (path.read_bytes(), verifier._fingerprint(path.stat())) for path in archives}
        status, result, output = self.cli(*archives)
        self.assertEqual(0, status)
        self.assertEqual("verified", result["state"])
        self.assertIs(True, result["output_written"])
        on_disk = json.loads(output.read_text(encoding="ascii"))
        self.assertNotIn("output_written", on_disk, "A report cannot certify its own delivery")
        self.assertEqual({key: value for key, value in result.items() if key != "output_written"}, on_disk)
        self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))
        for path, (data, fingerprint) in inputs.items():
            self.assertEqual(data, path.read_bytes())
            self.assertEqual(fingerprint, verifier._fingerprint(path.stat()))

    def test_cli_failed_input_report_is_allowed_but_never_success(self):
        rows = self.entries()[1:]
        status, result, output = self.cli(self.zip(rows))
        self.assertEqual(1, status)
        self.assertEqual("failed", result["state"])
        self.assertIs(True, result["output_written"])
        self.assertEqual("failed", json.loads(output.read_text())["state"])

    def test_cli_existing_output_is_not_replaced(self):
        output = self.new_file(self.root / "existing-report.json", b"private existing sentinel")
        status, result, _ = self.cli(self.zip(), output=output)
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual("report_already_exists", result["errors"][-1]["code"])
        self.assertEqual(b"private existing sentinel", output.read_bytes())

    def test_cli_linked_output_is_not_followed(self):
        sentinel = self.new_file(self.root / "sentinel.json", b"unmodified sentinel")
        output = self.root / "linked-report.json"
        os.symlink(sentinel, output)
        status, result, _ = self.cli(self.zip(), output=output)
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual("report_already_exists", result["errors"][-1]["code"])
        self.assertEqual(b"unmodified sentinel", sentinel.read_bytes())

    def test_cli_output_parent_symlink_is_not_followed(self):
        target = self.root / "actual-output"
        target.mkdir(mode=0o700)
        alias = self.root / "alias-output"
        os.symlink(target, alias, target_is_directory=True)
        status, result, _ = self.cli(self.zip(), output=alias / "new.json")
        self.assertEqual(1, status)
        self.assertEqual("input_link_or_special_file", result["errors"][-1]["code"])
        self.assertFalse((target / "new.json").exists())

    def test_cli_output_cannot_be_an_input_or_inside_installation(self):
        archive = self.zip()
        before = archive.read_bytes()
        for output in (archive, self.installation, self.installation / "new.json", self.installation / "app/new.json"):
            with self.subTest(output=output.name):
                status, result, _ = self.cli(archive, output=output)
                self.assertEqual(1, status)
                self.assertEqual("report_would_write_an_input", result["errors"][-1]["code"])
                self.assertIs(False, result["output_written"])
        self.assertEqual(before, archive.read_bytes())
        self.assertFalse((self.installation / "new.json").exists())

    def test_cli_missing_output_parent_is_not_created(self):
        missing = self.root / "missing"
        status, result, _ = self.cli(self.zip(), output=missing / "new.json")
        self.assertEqual(1, status)
        self.assertEqual("unavailable", result["state"])
        self.assertIs(False, result["output_written"])
        self.assertFalse(missing.exists())

    def test_cli_output_write_failure_leaves_only_new_unconfirmed_file(self):
        archive = self.zip()
        with patch.object(verifier.os, "write", return_value=0):
            status, result, output = self.cli(archive)
        self.assertEqual(1, status)
        self.assertEqual("unavailable", result["state"])
        self.assertIs(False, result["output_written"])
        self.assertEqual("report_short_write", result["errors"][-1]["code"])
        self.assertEqual(b"", output.read_bytes())

    def test_cli_report_readback_detects_changed_new_output(self):
        archive = self.zip()
        original = verifier.os.write
        def tampered(fd, raw):
            count = original(fd, raw)
            os.pwrite(fd, b"X", 0)
            return count
        with patch.object(verifier.os, "write", side_effect=tampered):
            status, result, output = self.cli(archive)
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual("report_output_content_or_stat_drift", result["errors"][-1]["code"])
        self.assertEqual(b"X", output.read_bytes()[:1])

    def test_cli_report_short_readback_is_non_success(self):
        archive = self.zip()
        original = verifier.os.pread
        def short_report(fd, count, offset):
            return b"" if stat.S_IMODE(os.fstat(fd).st_mode) == 0o600 and os.fstat(fd).st_size > archive.stat().st_size else original(fd, count, offset)
        with patch.object(verifier.os, "pread", side_effect=short_report):
            status, result, _ = self.cli(archive)
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual("report_readback_short_read", result["errors"][-1]["code"])

    def test_cli_report_final_binding_substitution_is_non_success(self):
        archive = self.zip()
        output = self.root / "new-report.json"
        original = verifier.os.write
        injected = False
        def substituted(fd, raw):
            nonlocal injected
            count = original(fd, raw)
            if not injected:
                injected = True
                output.rename(self.root / "unconfirmed-retired.json")
                self.new_file(output, b"substituted")
            return count
        with patch.object(verifier.os, "write", side_effect=substituted):
            status, result, _ = self.cli(archive, output=output)
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual("report_output_binding_drift", result["errors"][-1]["code"])
        self.assertEqual(b"substituted", output.read_bytes())
        unconfirmed = json.loads((self.root / "unconfirmed-retired.json").read_text())
        self.assertNotIn("output_written", unconfirmed)
        self.assertIn("unconfirmed", unconfirmed["report_delivery_scope"])

    def test_shared_cli_deadline_includes_new_report_phase(self):
        archive = self.zip()
        times = iter([0])
        def monotonic():
            return next(times, 121)
        with patch.object(verifier.time, "monotonic", side_effect=monotonic), \
                patch.object(verifier, "verify", return_value={"state": "verified", "errors": []}):
            status, result, output = self.cli(archive)
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual("time_limit", result["errors"][-1]["code"])
        self.assertFalse(output.exists())

    def test_report_serialization_is_bounded_before_output_creation(self):
        output = self.root / "new-report.json"
        with patch.object(verifier, "MAX_REPORT_BYTES", 8), self.assertRaises(verifier.VerificationError) as error:
            verifier.write_new_report(output, {"state": "unavailable"}, self.installation, [])
        self.assertEqual("report_bytes_limit", error.exception.code)
        self.assertFalse(output.exists())

    def test_inventory_serialization_is_independently_bounded(self):
        archive = self.zip()
        with patch.object(verifier, "MAX_REPORT_BYTES", 1):
            self.reject(archive, "inventory_serialization_limit")

    def test_windows_console_superscript_and_spaced_device_aliases_are_rejected(self):
        names = ("CONIN$", "conout$.txt", "COM¹", "COM².log", "COM³ .txt", "LPT¹", "LPT².log", "LPT³ .txt", "NUL .txt", "con  .log")
        for name in names:
            with self.subTest(name=name):
                rows = self.entries()
                rows[0]["name"] = PREFIX + "/" + name
                self.reject(self.zip(rows), "unsafe_member_name")
                self.reject(self.tar(rows), "unsafe_member_name")

    def test_tar_unsafe_names_have_the_same_literal_portable_policy(self):
        names = ("../escape", "/absolute", "prefix//double", "prefix/./dot", "prefix/../up",
                 "C:/drive", "prefix\\backslash", "prefix/CON.txt", "prefix/trailing.",
                 "prefix/.git/file", "prefix/non-nfc-e\u0301", "prefix/newline\n", "prefix/bidi\u202e")
        for name in names:
            with self.subTest(name=repr(name)):
                rows = self.entries()
                rows[0]["name"] = name
                self.reject(self.tar(rows), "unsafe_member_name")

    def test_tar_embedded_null_suffix_is_not_an_alternate_name(self):
        rows = self.entries()
        rows[0]["name"] = b"prefix/name\0hidden"
        self.reject(self.tar(rows), "tar_nonzero_name_padding")

    def test_tar_prefix_field_does_not_hide_path_traversal(self):
        rows = self.entries()
        rows[0].update(name="data.txt", prefix="../escape")
        self.reject(self.tar(rows), "unsafe_member_name")

    def test_archive_regular_count_has_an_independent_downward_limit(self):
        rows = self.entries() + [{"name": PREFIX + "/extra", "data": b""}]
        limits = replace(verifier.Limits(), max_files=3)
        self.reject(self.zip(rows), "archive_regular_file_count_limit", limits=limits)
        self.reject(self.tar(rows), "archive_regular_file_count_limit", limits=limits)

    def test_zip_central_member_variable_extents_are_bounded(self):
        for field, value in (("name_size", 0), ("name_size", 65535), ("extra_size", 65535), ("comment_size", 65535)):
            with self.subTest(field=field):
                rows = self.entries()
                rows[0]["central"] = {field: value}
                self.reject(self.zip(rows), "zip_central_extent_mismatch")

    def test_zip_local_declared_extents_cannot_escape_central_boundary(self):
        for alteration in ({"local": {"name_size": 1}}, {"central": {"offset": 100000}}, {"compressed": 100000}):
            with self.subTest(alteration=alteration):
                rows = self.entries()
                rows[0].update(alteration)
                self.reject(self.zip(rows), "zip_local_extent_mismatch")

    def test_zip64_locator_is_unsupported_even_without_end_sentinels(self):
        raw = make_zip(self.entries())
        locator = struct.pack("<4sLQL", b"PK\x06\x07", 0, 0, 1)
        self.reject(self.archive(raw[:-22] + locator + raw[-22:]), "unsupported_zip64", state="unsupported")

    def test_tar_checksum_numeric_encoding_is_checked_before_comparison(self):
        raw = make_tar(self.entries())
        self.reject(self.archive(change_tar(raw, {148: b"888888\0 "}, recalculate=False), ".tar"), "tar_invalid_octal_number")
        self.reject(self.archive(change_tar(raw, {148: b"\x80" + b"\0" * 7}, recalculate=False), ".tar"), "unsupported_tar_numeric_encoding", state="unsupported")

    def test_compressed_tar_magic_is_unsupported_even_with_plain_tar_suffix(self):
        for magic in (b"\x1f\x8b", b"BZh", b"\xfd7zXZ\0", b"\x28\xb5\x2f\xfd"):
            with self.subTest(magic=magic.hex()):
                self.reject(self.archive(magic + b"tiny synthetic non-tar bytes", ".tar"), "unsupported_tar_compression", state="unsupported")

    def test_tar_uid_gid_names_and_timestamps_are_not_certified(self):
        raw = make_tar(self.entries())
        changes = {108: tar_octal(7, 8), 116: tar_octal(11, 8), 136: tar_octal(12345, 12), 265: b"synthetic-owner", 297: b"synthetic-group"}
        self.check_valid(self.archive(change_tar(raw, changes), ".tar"))

    def test_exact_lowered_limits_are_inclusive_and_still_verify(self):
        rows = self.entries()
        raw = make_zip(rows)
        total = sum(map(len, self.content.values()))
        central_bytes = ZIP_END.unpack(raw[-22:])[5]
        limits = replace(verifier.Limits(), max_archives=1, max_files=3, max_entries=5,
                         max_file_bytes=max(map(len, self.content.values())), max_staged_bytes=total,
                         max_archive_bytes=len(raw), max_total_archive_bytes=len(raw),
                         max_decompressed_bytes=total, max_total_decompressed_bytes=total,
                         max_zip_central_bytes=central_bytes, max_depth=3,
                         max_component_bytes=len(PREFIX), max_path_bytes=max(len(row["name"]) for row in rows))
        self.check_valid(self.archive(raw), limits=limits)

    def test_unsafe_host_parent_traversal_is_rejected_before_open(self):
        archive = self.zip()
        with patch.object(verifier, "platform_available", return_value=True), patch.object(verifier.os, "open") as opened:
            result = verifier.verify(Path("../unopened-installation"), [archive])
        opened.assert_not_called()
        self.assertEqual("unsafe_input_path", result["errors"][0]["code"])

    def test_new_report_parent_may_not_be_group_writable(self):
        original = verifier.os.stat
        def unsafe(path, *args, **kwargs):
            value = original(path, *args, **kwargs)
            if path == self.root.name and "dir_fd" in kwargs:
                parts = list(value)
                parts[0] = stat.S_IFDIR | 0o720
                return os.stat_result(parts)
            return value
        output = self.root / "new-report.json"
        with patch.object(verifier, "platform_available", return_value=True), patch.object(verifier.os, "stat", side_effect=unsafe), \
                self.assertRaises(verifier.VerificationError) as error:
            verifier.write_new_report(output, {"synthetic": True}, self.installation, [])
        self.assertEqual("unsafe_file_mode", error.exception.code)
        self.assertFalse(output.exists())

    def test_cli_report_parent_substitution_is_non_success(self):
        archive = self.zip()
        directory = self.root / "reports"
        directory.mkdir(mode=0o700)
        output = directory / "new.json"
        original = verifier.os.write
        injected = False
        def substituted(fd, raw):
            nonlocal injected
            count = original(fd, raw)
            if not injected:
                injected = True
                directory.rename(self.root / "reports-retired")
                directory.mkdir(mode=0o700)
            return count
        with patch.object(verifier.os, "write", side_effect=substituted):
            status, result, _ = self.cli(archive, output=output)
        self.assertEqual(1, status)
        self.assertIs(False, result["output_written"])
        self.assertEqual("report_parent_binding_drift", result["errors"][-1]["code"])
        self.assertFalse(output.exists())

    def test_new_report_writes_and_readbacks_remain_chunk_bounded(self):
        output = self.root / "chunked-report.json"
        synthetic = {"synthetic": "X" * (verifier.CHUNK_BYTES + 33)}
        original_write, original_read = verifier.os.write, verifier.os.pread
        writes, reads = [], []
        def writing(fd, raw):
            writes.append(len(raw))
            return original_write(fd, raw)
        def reading(fd, count, offset):
            reads.append(count)
            return original_read(fd, count, offset)
        with patch.object(verifier.os, "write", side_effect=writing), patch.object(verifier.os, "pread", side_effect=reading):
            verifier.write_new_report(output, synthetic, self.installation, [])
        self.assertEqual(synthetic, json.loads(output.read_text()))
        self.assertGreaterEqual(len(writes), 2)
        self.assertGreaterEqual(len(reads), 2)
        self.assertLessEqual(max(writes + reads), verifier.CHUNK_BYTES)

    def test_new_report_partial_positive_writes_are_completed(self):
        archive = self.zip()
        original = verifier.os.write
        def short_write(fd, raw):
            return original(fd, raw[:97])
        with patch.object(verifier.os, "write", side_effect=short_write):
            status, result, output = self.cli(archive)
        self.assertEqual(0, status)
        self.assertIs(True, result["output_written"])
        self.assertEqual("verified", json.loads(output.read_text())["state"])

    def test_staged_directory_listing_drift_is_detected(self):
        archive = self.zip()
        original = verifier.os.scandir
        identity = self.installation.stat().st_dev, self.installation.stat().st_ino
        injected = False
        test = self
        class ChangedScan:
            def __init__(self, fd):
                self.fd, self.scan = fd, original(fd)
            def __enter__(self):
                return self
            def __exit__(self, *_args):
                self.scan.close()
            def __iter__(self):
                nonlocal injected
                yield from self.scan
                item = os.fstat(self.fd)
                if (item.st_dev, item.st_ino) == identity and not injected:
                    injected = True
                    test.new_file(test.installation / "late-added", b"drift")
        with patch.object(verifier, "platform_available", return_value=True), patch.object(verifier.os, "scandir", side_effect=ChangedScan):
            self.reject(archive, "staged_directory_listing_drift", state="stale")

    def test_stat_to_open_same_name_inode_substitution_is_detected(self):
        archive = self.zip()
        original = verifier.os.open
        injected = False
        def substituted(path, flags, *args, **kwargs):
            nonlocal injected
            if path == "data.txt" and "dir_fd" in kwargs and not injected:
                injected = True
                target = self.installation / "app/data.txt"
                target.rename(self.root / "retired-data.txt")
                self.new_file(target, self.content["app/data.txt"])
            return original(path, flags, *args, **kwargs)
        with patch.object(verifier, "platform_available", return_value=True), patch.object(verifier.os, "open", side_effect=substituted):
            self.reject(archive, "input_changed_during_open", state="stale")

    def test_descriptor_chains_are_closed_after_success_and_failure(self):
        valid = self.zip()
        invalid = self.archive(b"not a zip file")
        original_open, original_close = verifier.os.open, verifier.os.close
        for archive in (valid, invalid):
            opened, closed = [], []
            def opening(*args, **kwargs):
                fd = original_open(*args, **kwargs)
                opened.append(fd)
                return fd
            def closing(fd):
                closed.append(fd)
                return original_close(fd)
            with self.subTest(archive=archive.name), patch.object(verifier, "platform_available", return_value=True), \
                    patch.object(verifier.os, "open", side_effect=opening), patch.object(verifier.os, "close", side_effect=closing):
                result = self.verify(archive)
            self.assertEqual("verified" if archive == valid else "failed", result["state"])
            self.assertTrue(opened)
            self.assertEqual(Counter(opened), Counter(closed))


class DistributionArchiveStaticAndAvailabilityTests(unittest.TestCase):
    def test_invalid_expected_prefix_fails_closed_before_io_without_echoing_it(self):
        prefixes = ("", ".", "..", "a/b", "../private", "C:\\private", "CON", "bad\nprefix",
                    "\ud800", "non-nfc-e\u0301", "x" * 256, b"bytes", 1, True, [], {})
        for prefix in prefixes:
            with self.subTest(prefix_type=type(prefix).__name__), \
                    patch.object(verifier.os, "open") as opened:
                result = verifier.verify(Path("not-opened"), [Path("not-opened.zip")], expected_prefix=prefix)
            opened.assert_not_called()
            self.assertEqual("failed", result["state"])
            self.assertIsNone(result["expected_prefix"])
            self.assertEqual([{"code": "invalid_expected_archive_prefix", "phase": "arguments"}], result["errors"])
            self.assertIsNone(result["installation"])
            self.assertEqual([], result["archives"])

    def test_expected_prefix_does_not_bypass_platform_unavailability(self):
        with patch.object(verifier, "platform_available", return_value=False), patch.object(verifier.os, "open") as opened:
            result = verifier.verify(Path("not-opened"), [Path("not-opened.zip")], expected_prefix=PREFIX)
        opened.assert_not_called()
        self.assertEqual("unavailable", result["state"])
        self.assertEqual(PREFIX, result["expected_prefix"])
        self.assertEqual([{"code": "platform_no_follow_unavailable", "phase": "arguments"}], result["errors"])

    def test_platform_unavailable_is_non_success_without_opening_inputs(self):
        with patch.object(verifier, "platform_available", return_value=False), patch.object(verifier.os, "open") as opened:
            result = verifier.verify(Path("unopened-installation"), [Path("unopened.zip")])
        opened.assert_not_called()
        self.assertEqual("unavailable", result["state"])
        self.assertEqual("platform_no_follow_unavailable", result["errors"][0]["code"])
        self.assertIs(False, result["release_qualified"])

    def test_report_platform_unavailable_never_creates_output(self):
        with patch.object(verifier, "platform_available", return_value=False), \
                patch.object(verifier.os, "open") as opened, self.assertRaises(verifier.VerificationError) as error:
            verifier.write_new_report(Path("uncreated.json"), {}, Path("installation"), [])
        opened.assert_not_called()
        self.assertEqual("unavailable", error.exception.state)
        self.assertEqual("report_no_follow_unavailable", error.exception.code)

    def test_cli_unavailable_platform_cannot_return_success(self):
        argv = ["--installation", "not-opened", "--archive", "not-opened.zip", "--output", "not-created.json"]
        with patch.object(verifier, "platform_available", return_value=False), patch.object(verifier.os, "open") as opened, \
                patch("sys.stdout", new_callable=io.StringIO) as stdout:
            status = verifier.main(argv)
        opened.assert_not_called()
        result = json.loads(stdout.getvalue())
        self.assertEqual(1, status)
        self.assertEqual("unavailable", result["state"])
        self.assertIs(False, result["output_written"])

    def test_cli_argument_errors_do_not_echo_unvalidated_input(self):
        argv = ["--private-\x1b-sentinel", "secret-path"]
        with patch("sys.stderr", new_callable=io.StringIO) as stderr, self.assertRaises(SystemExit) as error:
            verifier.main(argv)
        self.assertEqual(2, error.exception.code)
        self.assertEqual("Distribution archive verification: invalid_arguments\n", stderr.getvalue())

    def test_non_limits_and_limits_subclasses_fail_without_traceback(self):
        class DifferentLimits(verifier.Limits):
            pass
        for limits in ({}, 1, "untrusted limits", DifferentLimits()):
            with self.subTest(limits=type(limits).__name__), patch.object(verifier.os, "open") as opened:
                result = verifier.verify(Path("not-opened"), [Path("not-opened.zip")], limits=limits)
                self.assertEqual("invalid_limits", result["errors"][0]["code"])
                opened.assert_not_called()

    def test_absolute_default_resource_ceilings_and_chunk_size_are_pinned(self):
        self.assertEqual({
            "max_archives": 4, "max_files": 4096, "max_entries": 8192, "max_depth": 32,
            "max_path_bytes": 1024, "max_component_bytes": 255, "max_file_bytes": 256 * verifier.MIB,
            "max_staged_bytes": 512 * verifier.MIB, "max_archive_bytes": 512 * verifier.MIB,
            "max_total_archive_bytes": 1024 * verifier.MIB, "max_decompressed_bytes": 512 * verifier.MIB,
            "max_total_decompressed_bytes": 1024 * verifier.MIB, "max_zip_central_bytes": 16 * verifier.MIB,
            "max_seconds": 120,
        }, verifier.asdict(verifier.Limits()))
        self.assertEqual(65536, verifier.CHUNK_BYTES)
        self.assertEqual(32 * verifier.MIB, verifier.MAX_REPORT_BYTES)

    def test_verifier_ast_has_no_process_network_extraction_or_input_mutation_api(self):
        source = Path(verifier.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        imports = {alias.name.split(".", 1)[0] for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names}
        imports.update(node.module.split(".", 1)[0] for node in ast.walk(tree) if isinstance(node, ast.ImportFrom) and node.module)
        self.assertFalse(imports & {"ci_common", "subprocess", "socket", "urllib", "requests", "http", "tarfile", "zipfile", "shutil"})
        banned = {"extract", "extractall", "chmod", "fchmod", "chown", "fchown", "system", "popen", "spawn", "fork",
                  "execv", "execve", "run", "unlink", "remove", "replace", "rename", "mkdir", "makedirs", "write_text", "write_bytes"}
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)]
        self.assertFalse({node.func.attr for node in calls if isinstance(node.func, ast.Attribute)} & banned)
        self.assertFalse({node.func.id for node in calls if isinstance(node.func, ast.Name)} & {"eval", "exec", "__import__"})
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(tree)))
        creating = [node for node in calls if isinstance(node.func, ast.Attribute) and node.func.attr == "open"
                    and any(isinstance(child, ast.Attribute) and child.attr == "O_CREAT" for child in ast.walk(node))]
        self.assertEqual(1, len(creating))
        self.assertTrue({"O_CREAT", "O_EXCL", "O_NOFOLLOW"}.issubset({child.attr for child in ast.walk(creating[0]) if isinstance(child, ast.Attribute)}))
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "write_new_report")
        self.assertIn(creating[0], list(ast.walk(function)))
        self.assertFalse(any(isinstance(node.func, ast.Attribute) and node.func.attr == "flush" for node in calls))

    def test_suite_contains_no_optimization_sensitive_python_assertions(self):
        source = Path(__file__).read_text(encoding="utf-8")
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(ast.parse(source))))


if __name__ == "__main__":
    unittest.main()
