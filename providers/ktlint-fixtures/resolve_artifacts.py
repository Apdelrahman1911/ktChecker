#!/usr/bin/env python3
"""Verify the pinned official optional Ktlint installation, offline by default.

SHA-256 values are local pins computed after verifying official published SHA-1,
not a claim that Maven Central publishes these SHA-256 checksums. This helper is
not process execution authority and does not bundle JARs into ktChecker.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "modules/provider-ktlint/src/main/resources/ktlint-artifacts.json"


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate artifact manifest field: {key}")
        result[key] = value
    return result


def validate_pin(pin):
    if not isinstance(pin, dict):
        raise ValueError("artifact pin must be an object")
    filename = pin.get("filename", "")
    url = pin.get("url")
    if not isinstance(url, str):
        raise ValueError("unsafe/invalid artifact pin")
    parsed = urlparse(url)
    if (not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9._-]+\.jar", filename) or
            parsed.scheme != "https" or parsed.netloc != "repo.maven.apache.org" or
            not parsed.path.startswith("/maven2/") or not parsed.path.endswith("/" + filename) or parsed.query or parsed.fragment or
            not isinstance(pin.get("coordinate"), str) or not pin["coordinate"] or
            type(pin.get("size")) is not int or not 0 < pin["size"] <= 200_000_000 or
            pin.get("upstream_checksum_algorithm") != "sha1" or
            not isinstance(pin.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", pin["sha256"]) or
            not isinstance(pin.get("upstream_checksum"), str) or not re.fullmatch(r"[0-9a-f]{40}", pin["upstream_checksum"])):
        raise ValueError("unsafe/invalid artifact pin")


def verify(path, pin):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != pin["size"]:
        raise ValueError(f"missing/nonregular/incorrect-size artifact: {pin['coordinate']}")
    sha256, sha1 = hashlib.sha256(), hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha256.update(chunk)
            sha1.update(chunk)
    if sha256.hexdigest() != pin["sha256"] or sha1.hexdigest() != pin["upstream_checksum"]:
        raise ValueError(f"artifact digest mismatch: {pin['coordinate']}; remove/repair explicitly")


def resolve(cache, download=False, manifest_path=MANIFEST):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)
    if (not isinstance(manifest, dict) or type(manifest.get("schema_version")) is not int or
            manifest["schema_version"] != 1 or not isinstance(manifest.get("artifacts"), list) or not manifest["artifacts"]):
        raise ValueError("unsupported/empty artifact manifest")
    pins = manifest["artifacts"]
    for pin in pins:
        validate_pin(pin)
    if len({pin["filename"] for pin in pins}) != len(pins) or len({pin["coordinate"] for pin in pins}) != len(pins):
        raise ValueError("duplicate artifact identity")
    cache = cache.absolute()
    if any(parent.is_symlink() for parent in [cache, *cache.parents]):
        raise ValueError("artifact cache must not use a symlink")
    cache.mkdir(parents=True, exist_ok=True)
    identities = []
    for pin in pins:
        destination = cache / pin["filename"]
        if not destination.exists() and not destination.is_symlink():
            if not download:
                raise ValueError(f"artifact unavailable offline: {pin['coordinate']}; select --download explicitly")
            with tempfile.NamedTemporaryFile(prefix=".download-", dir=cache, delete=False) as output:
                temporary = Path(output.name)
                try:
                    with urllib.request.urlopen(pin["url"], timeout=90) as response:
                        if response.url != pin["url"]:
                            raise ValueError("artifact redirect escaped exact pinned URL")
                        total = 0
                        while chunk := response.read(1024 * 1024):
                            total += len(chunk)
                            if total > pin["size"]:
                                raise ValueError("download exceeds pinned size")
                            output.write(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                    verify(temporary, pin)
                    with urllib.request.urlopen(pin["url"] + ".sha1", timeout=30) as response:
                        if response.url != pin["url"] + ".sha1":
                            raise ValueError("checksum redirect escaped exact pinned URL")
                        published = response.read(4097)
                    if len(published) > 4096 or not published.strip():
                        raise ValueError("official SHA-1 is absent/oversized")
                    if published.decode("ascii").strip().split()[0] != pin["upstream_checksum"]:
                        raise ValueError("official SHA-1 changed from pinned identity")
                    os.replace(temporary, destination)
                finally:
                    temporary.unlink(missing_ok=True)
        verify(destination, pin)  # Corrupt caches never trigger automatic replacement.
        identities.append({"coordinate": pin["coordinate"], "digest": "sha256:" + pin["sha256"], "path": str(destination)})
    return identities


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / ".plan-deps/ktlint")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps({"schema_version": 1, "artifacts": resolve(args.cache, args.download)}, indent=2))
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(2, f"ktlint artifact resolution failed: {error}\n")


if __name__ == "__main__":
    main()
