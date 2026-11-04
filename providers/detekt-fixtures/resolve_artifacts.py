#!/usr/bin/env python3
"""Resolve only the pinned official artifacts; cached bytes are always verified.

No download happens without --download. This is a developer fixture/installation
helper, not a process execution authority. The manifest's SHA-256 values are
local pins initially computed after official SHA-1 verification, not a claim
that Maven Central published a SHA-256 checksum.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "modules/provider-detekt/src/main/resources/detekt-artifacts.json"


def verify(path, pin):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != pin["size"]:
        raise ValueError(f"missing/nonregular/incorrect-size pinned artifact: {pin['coordinate']}")
    sha256, sha1 = hashlib.sha256(), hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha256.update(chunk)
            sha1.update(chunk)
    if sha256.hexdigest() != pin["sha256"] or sha1.hexdigest() != pin["upstream_checksum"]:
        raise ValueError(f"digest mismatch: {pin['coordinate']}; remove/repair the cache explicitly")


def resolve(cache, download=False, manifest_path=MANIFEST):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["schema_version"] != 1:
        raise ValueError("unsupported artifact manifest")
    cache = cache.absolute()
    if cache.is_symlink():
        raise ValueError("artifact cache must not be a symlink")
    cache.mkdir(parents=True, exist_ok=True)
    identities = []
    for pin in manifest["artifacts"]:
        filename = pin["filename"]
        parsed = urlparse(pin["url"])
        if (Path(filename).name != filename or "/" in filename or "\\" in filename or
                not filename.endswith(".jar") or parsed.scheme != "https" or
                parsed.hostname != "repo.maven.apache.org" or parsed.netloc != "repo.maven.apache.org" or
                not parsed.path.startswith("/maven2/") or not parsed.path.endswith("/" + filename) or
                parsed.query or parsed.fragment or pin["upstream_checksum_algorithm"] != "sha1"):
            raise ValueError("unsafe/unpinned artifact manifest entry")
        destination = cache / filename
        if not destination.exists() and not destination.is_symlink():
            if not download:
                raise ValueError(f"artifact unavailable offline: {pin['coordinate']}; run with --download explicitly")
            with tempfile.NamedTemporaryFile(prefix=".download-", dir=cache, delete=False) as output:
                temporary = Path(output.name)
                try:
                    with urllib.request.urlopen(pin["url"], timeout=90) as response:
                        if urlparse(response.url).hostname != "repo.maven.apache.org":
                            raise ValueError("artifact redirect escaped pinned official host")
                        total = 0
                        while chunk := response.read(1024 * 1024):
                            total += len(chunk)
                            if total > pin["size"]:
                                raise ValueError("download exceeds pinned artifact size")
                            output.write(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                    verify(temporary, pin)
                    checksum = urllib.request.urlopen(pin["url"] + ".sha1", timeout=30).read(4096).decode("ascii").strip().split()[0]
                    if checksum != pin["upstream_checksum"]:
                        raise ValueError("official SHA-1 changed from pinned identity")
                    os.replace(temporary, destination)
                finally:
                    temporary.unlink(missing_ok=True)
        verify(destination, pin)
        identities.append({"coordinate": pin["coordinate"], "digest": "sha256:" + pin["sha256"], "path": str(destination)})
    return identities


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / ".plan-deps/detekt")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps({"schema_version": 1, "artifacts": resolve(args.cache, args.download)}, indent=2))
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, f"detekt artifact resolution failed: {error}\n")


if __name__ == "__main__":
    main()
