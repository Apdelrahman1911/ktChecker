"""Windows-only integration gaps, not another generic worker acceptance test.

The existing cross-platform positives already use real Win32 observations when
run on Windows. These additional controls compare actual query buffers, observe
real owned-handle closure on native rejection, and substitute a different real
read descriptor without inventing metadata or racing a writer. Non-Windows
hosts report three visible skips. No JVM, helper, provider, model, or network
execution; passing does not qualify a Windows ABI, release, or hostile race.
"""

import os
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

try:
    from . import verify_model_worker_distribution as verifier
except ImportError:
    import verify_model_worker_distribution as verifier


@unittest.skipUnless(os.name == "nt", "Windows-only real Win32 metadata/handle integration")
class ModelWorkerWindowsNativeIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(verifier.windows_observation_required())
        # Imports occur only after unittest's visible platform skip decision.
        import ctypes
        import msvcrt

        self.ctypes, self.msvcrt = ctypes, msvcrt
        self.api = verifier._windows_api()  # Actual System32 API, never a stub.
        self.temporary = tempfile.TemporaryDirectory(prefix="ktc native metadata ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.first = self.root / "first equal bytes \u03bb.bin"
        self.second = self.root / "second equal bytes \u03bb.bin"
        self.payload = b"synthetic equal file bytes, not executable content\n"
        self.first.write_bytes(self.payload)
        self.second.write_bytes(self.payload)

    def raw_native_reference(self, handle):
        """Parse documented byte offsets, not the product's ctypes structures."""
        self.assertEqual(1, self.api.GetFileType(handle))

        def query(kind, size):
            buffer = self.ctypes.create_string_buffer(size)
            self.assertNotEqual(0, self.api.GetFileInformationByHandleEx(
                handle, kind, self.ctypes.byref(buffer), size), "real native reference query unavailable")
            return buffer.raw

        creation, _access, write, change, attributes = struct.unpack("<qqqqI4x", query(0, 40))
        _allocation, size, links, pending_delete, directory = struct.unpack("<qqIBB2x", query(1, 24))
        volume, identifier = struct.unpack("<Q16s", query(18, 24))
        self.assertFalse(pending_delete or directory or attributes & (0x400 | 0x10 | 0x40))
        self.assertEqual(1, links)
        self.assertEqual(len(self.payload), size)
        identity = int.from_bytes(identifier, "little")
        self.assertGreater(identity, 0)
        # A filesystem need not populate the high bits. Compare all returned
        # bits; do not fabricate a >64-bit identity or >32-bit volume claim.
        return volume, identity, attributes, links, size, write, change, creation

    def close_remaining_metadata_handles(self, live):
        # Failure cleanup only; a leaked handle still fails the assertions.
        # This set never contains a borrowed CRT descriptor handle.
        for handle in tuple(live):
            self.assertNotEqual(0, self.api.CloseHandle(handle), "real metadata-handle cleanup failed")
            live.remove(handle)

    def recording_real_api(self):
        """Forward every call to real Win32; record, never replace, its result."""
        calls = {"created": [], "closed": [], "queries": [], "identities": [], "live": set()}
        self.addCleanup(self.close_remaining_metadata_handles, calls["live"])

        def create(*args):
            handle = self.api.CreateFileW(*args)
            calls["created"].append(handle)
            if type(handle) is int and handle > 0 and handle != self.ctypes.c_void_p(-1).value:
                calls["live"].add(handle)
            return handle

        def query(handle, kind, pointer, size):
            status = self.api.GetFileInformationByHandleEx(handle, kind, pointer, size)
            calls["queries"].append((handle, kind, size, status))
            if status and kind == 18:
                value = pointer._obj  # The real populated product buffer.
                calls["identities"].append((handle, int(value.VolumeSerialNumber),
                                            int.from_bytes(bytes(value.Identifier), "little")))
            return status

        def close(handle):
            status = self.api.CloseHandle(handle)
            calls["closed"].append((handle, status))
            if status:
                calls["live"].discard(handle)
            return status

        return SimpleNamespace(CreateFileW=create, GetFileType=self.api.GetFileType,
                               GetFileInformationByHandleEx=query, CloseHandle=close), calls

    def assert_one_owned_handle_closed(self, calls, query_passes=1):
        self.assertEqual(1, len(calls["created"]))
        handle = calls["created"][0]
        self.assertIs(type(handle), int)
        self.assertGreater(handle, 0)
        self.assertEqual([handle], [value for value, _status in calls["closed"]])
        self.assertTrue(all(status != 0 for _value, status in calls["closed"]))
        self.assertEqual([(0, 40), (1, 24), (18, 24)] * query_passes,
                         [(kind, size) for _value, kind, size, _status in calls["queries"]])
        self.assertTrue(all(status != 0 for _value, _kind, _size, status in calls["queries"]))
        self.assertFalse(calls["live"])

    def test_windows_file_id_info_matches_raw_native_buffers_and_distinguishes_equal_bytes(self):
        identities = []
        for path in (self.first, self.second):
            with self.subTest(file=path.name), path.open("rb") as stream:
                handle = self.msvcrt.get_osfhandle(stream.fileno())
                expected = self.raw_native_reference(handle)
                self.assertEqual(expected, verifier._windows_file_observation(path=path))
                self.assertEqual(expected, verifier._windows_file_observation(descriptor=stream.fileno()))
                self.assertEqual(handle, self.msvcrt.get_osfhandle(stream.fileno()))
                self.assertEqual(self.payload, stream.read())  # Borrowed handle remains live.
                identities.append(expected[:2])
        self.assertEqual(2, len(identities))  # Avoid an unrelated index error after a failed subtest.
        self.assertEqual(identities[0][0], identities[1][0])  # Same temporary volume.
        self.assertNotEqual(identities[0][1], identities[1][1])  # Distinct real FILE_ID_128 values.

    def test_windows_metadata_handles_close_once_on_real_success_and_native_rejections(self):
        # A hardlink needs no symlink privilege. Do not silently skip a missing
        # core API/filesystem capability on the Windows integration host.
        os.link(self.second, self.root / "real second hardlink.bin")
        self.assertEqual(2, self.second.stat().st_nlink)
        cases = (("regular", self.first, None),
                 ("directory", self.root, "independent_regular_file_required"),
                 ("hardlink", self.second, "independent_regular_file_required"))
        for kind, path, code in cases:
            api, calls = self.recording_real_api()
            with self.subTest(kind=kind), patch.object(verifier, "_windows_api", return_value=api):
                if code is None:
                    self.assertEqual(1, verifier._windows_file_observation(path=path)[3])
                else:
                    with self.assertRaises(verifier.WorkerVerificationError) as raised:
                        verifier._windows_file_observation(path=path)
                    self.assertEqual(code, raised.exception.code)
                self.assert_one_owned_handle_closed(calls)
        api, calls = self.recording_real_api()
        with self.subTest(kind="missing"), patch.object(verifier, "_windows_api", return_value=api):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier._windows_file_observation(path=self.root / "never-created.bin")
            self.assertEqual("windows_observation_unavailable", raised.exception.code)
            self.assertEqual([self.ctypes.c_void_p(-1).value], calls["created"])
            self.assertEqual([], calls["closed"])
            self.assertEqual([], calls["queries"])
            self.assertFalse(calls["live"])

    def test_windows_real_path_and_different_borrowed_file_cannot_bind_even_with_equal_bytes(self):
        first = verifier._windows_file_observation(path=self.first)
        second = verifier._windows_file_observation(path=self.second)
        self.assertEqual(first[0], second[0])
        self.assertNotEqual(first[1], second[1])
        self.assertEqual(first[3:5], second[3:5])
        original_open, opened = verifier.os.open, []

        def open_other_real_file(path, flags, *args, **kwargs):
            self.assertEqual(self.first, Path(path))
            self.assertFalse(flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND))
            descriptor = original_open(self.second, flags, *args, **kwargs)
            opened.append(descriptor)
            return descriptor

        api, calls = self.recording_real_api()
        # Controlled data-open substitution, not a concurrent rename/race.
        # Native APIs, structures, stat results and descriptor lookup stay real.
        with patch.object(verifier, "_windows_api", return_value=api), \
                patch.object(verifier.os, "open", side_effect=open_other_real_file):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier.read_file(self.first, 1024)
        self.assertEqual("observed_input_drift", raised.exception.code)
        self.assertEqual(1, len(opened))
        self.assert_one_owned_handle_closed(calls, query_passes=2)
        self.assertEqual([first[:2], second[:2]], [(volume, identity) for _handle, volume, identity in calls["identities"]])
        with self.assertRaises(OSError):
            os.fstat(opened[0])  # The read context, not the observer, owns closure.


if __name__ == "__main__":
    unittest.main()
