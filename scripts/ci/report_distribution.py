#!/usr/bin/env python3
"""Bind real distribution JARs to resolved coordinates, declared licenses and notices."""

import argparse
import json
from pathlib import Path
import re
import uuid
import xml.etree.ElementTree as ET
import zipfile

if __package__:
    from .ci_common import REPO_ROOT, digest_bytes, digest_file, source_identity, utc_now, write_json
else:
    from ci_common import REPO_ROOT, digest_bytes, digest_file, source_identity, utc_now, write_json


DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
COORDINATE = re.compile(r"[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+")


SPDX_NAMES = {
    "apache-2.0": "Apache-2.0", "the apache software license, version 2.0": "Apache-2.0",
    "the apache license, version 2.0": "Apache-2.0", "apache license, version 2.0": "Apache-2.0",
    "mit": "MIT", "mit license": "MIT", "the mit license": "MIT",
}
SPDX_URLS = {
    "apache.org/licenses/license-2.0.txt": "Apache-2.0", "apache.org/licenses/license-2.0": "Apache-2.0",
    "opensource.org/licenses/mit": "MIT", "opensource.org/license/mit": "MIT",
}


def safe_relative(value: object) -> bool:
    return isinstance(value, str) and 0 < len(value) <= 1024 and not re.search(r"[\x00-\x1f\x7f]", value) and "\\" not in value and ":" not in value \
        and not Path(value).is_absolute() and all(part not in {".", "..", ""} for part in value.split("/"))


def document(path: Path, limit: int) -> tuple[object, str]:
    """Bound the exact bytes parsed and reject duplicate keys/invalid UTF-8."""
    if path.stat().st_size > limit:
        raise ValueError("Dependency inventory input exceeds its byte bound")
    raw = path.read_bytes()
    if len(raw) > limit:
        raise ValueError("Dependency inventory input grew beyond its byte bound")

    def unique_object(pairs: list[tuple[str, object]]) -> dict:
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("Duplicate keys are not allowed in inventory input")
            value[key] = item
        return value

    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object), digest_bytes(raw)


def text_value(value: object, limit: int = 1024) -> bool:
    return isinstance(value, str) and 0 < len(value) <= limit and not re.search(r"[\x00-\x1f\x7f]", value)


def contained_file(base: Path, relative: str) -> Path | None:
    """Required attribution cannot use even an in-tree parent/leaf symlink."""
    if not safe_relative(relative):
        return None
    base = base.resolve()
    candidate = base / relative
    current = candidate
    while current != base:
        if current.is_symlink():
            return None
        current = current.parent
    return candidate if candidate.is_file() and candidate.resolve().is_relative_to(base) else None


def validate_policy(policy: object) -> dict:
    if not isinstance(policy, dict) or type(policy.get("schema_version")) is not int or policy["schema_version"] != 1 \
            or "owner_license" not in policy or policy["owner_license"] is not None and not text_value(policy["owner_license"]):
        raise ValueError("An explicit versioned license/NOTICE policy with nullable owner license is required")
    known = policy.get("known_license_assets")
    if not isinstance(known, dict) or len(known) > 1000:
        raise ValueError("Known license text policy must be a bounded object")
    asset_digests = {}

    def assets(rows: object) -> None:
        if not isinstance(rows, list) or len(rows) > 1000:
            raise ValueError("Required retained license/NOTICE assets must be a bounded list")
        for asset in rows:
            if not isinstance(asset, dict) or not safe_relative(asset.get("path")) \
                    or not isinstance(asset.get("digest"), str) or not DIGEST.fullmatch(asset["digest"]):
                raise ValueError("Required retained assets need safe relative paths and exact SHA-256")
            previous = asset_digests.setdefault(asset["path"], asset["digest"])
            if previous != asset["digest"]:
                raise ValueError("Notice policy assigns contradictory bytes to the same retained asset")

    for identifier, rows in known.items():
        if not text_value(identifier, 128) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+-]*", identifier):
            raise ValueError("Known license identifiers must be explicit safe policy keys")
        assets(rows)
    for key in ("notice_rules", "shaded_review_rules"):
        rules = policy.get(key)
        if not isinstance(rules, list) or len(rules) > 1000:
            raise ValueError("NOTICE/shaded review policy must contain explicit bounded rule lists")
        for rule in rules:
            if not isinstance(rule, dict) or not text_value(rule.get("coordinate_pattern"), 512) \
                    or type(rule.get("review_complete")) is not bool:
                raise ValueError("NOTICE/shaded review rule needs an explicit coordinate pattern and boolean review status")
            try:
                re.compile(rule["coordinate_pattern"])
            except re.error as error:
                raise ValueError("Invalid trusted NOTICE coordinate pattern") from error
            if key == "notice_rules":
                assets(rule.get("required_assets"))
    files = policy.get("required_distribution_files")
    if not isinstance(files, list) or len(files) > 1000 or any(not safe_relative(row) for row in files) or len(set(files)) != len(files):
        raise ValueError("Required attribution files must be explicit unique safe relative paths")
    return policy


def validate_manifest(manifest: object, distribution: Path, root: Path) -> list[dict]:
    if not isinstance(manifest, dict) or type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1 \
            or manifest.get("producer") != "gradle-resolved-distribution-artifacts/1" or manifest.get("gradleVersion") != "9.6.1" \
            or manifest.get("project") != ":cli":
        raise ValueError("Actual pinned Gradle resolved-artifact manifest for the CLI is required")
    if not text_value(manifest.get("distribution"), 8192) or not Path(manifest["distribution"]).is_absolute() \
            or Path(manifest["distribution"]).resolve() != distribution:
        raise ValueError("Resolved-artifact manifest belongs to a different installed distribution")
    rows = manifest.get("artifacts")
    if not isinstance(rows, list) or not 0 < len(rows) <= 10000:
        raise ValueError("Actual resolved distribution artifacts must be a nonempty bounded list")
    for row in rows:
        if not isinstance(row, dict) or not text_value(row.get("coordinate"), 512) or not COORDINATE.fullmatch(row["coordinate"]) \
                or row.get("origin") not in ("maven", "owner_project") \
                or row.get("configuration") not in ("runtimeClasspath", "bundledCompiler", "application") \
                or not text_value(row.get("artifactIdentity"), 8192) or not text_value(row.get("file"), 8192):
            raise ValueError("Resolved artifact lacks a real component coordinate/origin/configuration/file mapping")
        file = Path(row["file"])
        if not file.is_absolute() or not file.is_file() or file.is_symlink() or file.suffix != ".jar" \
                or row["origin"] == "owner_project" and not file.resolve().is_relative_to(root.resolve()):
            raise ValueError("Resolved distribution artifact JAR bytes are absent/unsafe")
        if "pom" not in row or row["pom"] is not None and (not text_value(row["pom"], 8192) or not Path(row["pom"]).is_absolute()
                or not Path(row["pom"]).is_file() or Path(row["pom"]).is_symlink() or Path(row["pom"]).suffix != ".pom"):
            raise ValueError("Exact queried POM path must be an actual safe file or explicitly unavailable")
        if row["origin"] == "owner_project" and row["pom"] is not None:
            raise ValueError("Owner project code must not inherit an unrelated dependency POM license")
    return rows


def license_id(name: str | None, url: str | None) -> str | None:
    # Exact published name/official-license URL aliases only. Never infer from
    # group name, project language, archive name, or a neighboring dependency.
    candidates = set()
    if name and name.strip().lower() in SPDX_NAMES:
        candidates.add(SPDX_NAMES[name.strip().lower()])
    if url:
        normalized = re.sub(r"^https?://(?:www\.)?", "", url.strip().lower()).rstrip("/")
        if normalized in SPDX_URLS:
            candidates.add(SPDX_URLS[normalized])
    return next(iter(candidates)) if len(candidates) == 1 else None


def pom_licenses(path: Path) -> dict:
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Oversized Maven POM")
    raw = path.read_bytes()
    text = raw.decode("utf-8-sig")
    if len(raw) > 2 * 1024 * 1024 or "\x00" in text or re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", text, re.IGNORECASE):
        raise ValueError("Unsafe/oversized Maven POM")
    root = ET.fromstring(text)
    for node in root.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]
    if root.tag != "project":
        raise ValueError("Artifact metadata is not a Maven project POM")
    declarations = []
    for row in root.findall("licenses/license"):
        if len(declarations) >= 64:
            raise ValueError("POM license declarations exceed the inventory bound")
        name, url = row.findtext("name"), row.findtext("url")
        name = " ".join(name.split()) if name else None
        url = " ".join(url.split()) if url else None
        declarations.append({"name": name[:1024] if name else None, "url": url[:2048] if url else None,
                             "spdx_id": license_id(name, url), "evidence": "declared_in_actual_resolved_pom"})
    return {"digest": digest_bytes(raw), "declared_licenses": declarations,
            "inherited_or_shaded_licenses_resolved": False}


def embedded_notices(path: Path) -> list[dict]:
    result = []
    total = 0
    names = set()
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 250000:
            raise ValueError("JAR entry count exceeds inventory bound")
        for row in entries:
            name = row.filename
            basename = name.rsplit("/", 1)[-1].lower()
            if row.is_dir() or not re.match(r"^(?:license|licence|notice|copyright)(?:\.|-|$)", basename):
                continue
            total += row.file_size
            if not safe_relative(name) or row.file_size > 2 * 1024 * 1024 or total > 16 * 1024 * 1024 \
                    or len(result) >= 256 or name in names:
                raise ValueError("Unsafe/oversized embedded license/NOTICE asset")
            names.add(name)
            raw = archive.read(row)
            result.append({"path_in_jar": name, "size": len(raw), "digest": digest_bytes(raw),
                           "content_preserved_in_distributed_jar": True,
                           "license_interpretation": "not_inferred_from_filename"})
    return result


def required_asset(asset: dict, distribution: Path, root: Path) -> tuple[dict, list[str]]:
    if not isinstance(asset, dict) or not safe_relative(asset.get("path")) \
            or not isinstance(asset.get("digest"), str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", asset["digest"]):
        raise ValueError("Notice policy must bind repository-relative assets to exact digests")
    relative = asset["path"]
    errors = []
    for label, base in (("retained source", root), ("distribution", distribution)):
        path = contained_file(base, relative)
        if path is None:
            errors.append(f"Required {label} license/NOTICE asset is absent/unsafe: {relative}")
        elif digest_file(path) != asset["digest"]:
            errors.append(f"Required {label} license/NOTICE bytes differ from the retained pinned text: {relative}")
    return {"path": relative, "digest": asset["digest"], "preserved_in_distribution": not errors}, errors


def generate(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    if args.distribution.is_symlink():
        raise ValueError("Actual installed distribution must not be a symlink")
    distribution = args.distribution.resolve()
    if not distribution.is_relative_to(root.resolve()) or not distribution.is_dir():
        raise ValueError("Actual installed distribution must be a directory inside this checkout")
    manifest, manifest_digest = document(args.dependencies, 16 * 1024 * 1024)
    policy, policy_digest = document(args.policy, 2 * 1024 * 1024)
    policy = validate_policy(policy)
    artifacts = validate_manifest(manifest, distribution, root)
    result = {"schema_version": 1, "evidence_kind": "actual_distribution_dependency_inventory", "recorded_at": utc_now(),
              "source": source_identity(root), "state": "running", "components": [], "errors": [], "license_review_blockers": [],
              "source_identity_scope": "Working tree at inventory time; does not reconstruct historical build inputs",
              "dependency_manifest_digest": manifest_digest, "notice_policy_digest": policy_digest, "required_attribution_files": [],
              "release_qualified": False, "signing": "not_performed", "publication": "not_performed",
              "qualification": "Resolved coordinates/actual bytes/declared licenses only; not a complete legal or release qualification"}
    if not policy.get("owner_license"):
        result["license_review_blockers"].append("owner_license_unresolved: original ktChecker code has no selected distribution license")
    identity_map = {}
    for row in artifacts:
        file = Path(row["file"])
        key = (file.name, digest_file(file))
        identity_map.setdefault(key, []).append(row)
    if any(path.is_symlink() for path in distribution.rglob("*")):
        raise ValueError("Installed distribution must not contain symlinked files/directories")
    installed = sorted(distribution.rglob("*.jar"))
    if not installed:
        raise ValueError("Installed distribution contains no real JARs")
    components = {}
    seen = set()
    for file in installed:
        relative = file.relative_to(distribution).as_posix()
        if file.is_symlink() or not file.resolve().is_relative_to(distribution):
            raise ValueError("Installed JAR must not escape the actual distribution")
        digest = digest_file(file)
        key = (file.name, digest)
        candidates = identity_map.get(key, [])
        identities = {(row["coordinate"], row["origin"], digest_file(Path(row["pom"])) if row["pom"] else None) for row in candidates}
        if len(identities) != 1:
            result["errors"].append(f"Installed JAR has absent/ambiguous actual resolved identity: {relative}")
            continue
        coordinate = next(iter(identities))[0]
        seen.add(key)
        row = candidates[0]
        component_key = (coordinate, digest)
        if component_key not in components:
            metadata = pom_licenses(Path(row["pom"])) if row["pom"] else {"digest": None, "declared_licenses": [],
                        "inherited_or_shaded_licenses_resolved": False}
            component = {"coordinate": coordinate, "origin": row["origin"], "digest": digest, "size": file.stat().st_size,
                         "distribution_paths": [], "configurations": sorted({item["configuration"] for item in candidates}),
                         "pom": metadata, "embedded_license_notice_assets": embedded_notices(file),
                         "required_retained_assets": [], "notice_review": "unreviewed", "license_review_complete": False}
            if row["origin"] == "maven" and not metadata["declared_licenses"]:
                result["license_review_blockers"].append(f"declared_license_unknown:{coordinate}")
            for declaration in metadata["declared_licenses"]:
                spdx = declaration["spdx_id"]
                if not spdx:
                    result["license_review_blockers"].append(f"license_id_unresolved:{coordinate}")
                    continue
                retained = policy.get("known_license_assets", {}).get(spdx, [])
                if not retained:
                    result["license_review_blockers"].append(f"required_license_text_unreviewed:{coordinate}:{spdx}")
                for asset in retained:
                    evidence, errors = required_asset(asset, distribution, root)
                    component["required_retained_assets"].append(evidence)
                    result["errors"].extend(errors)
            matched = []
            for rule in policy.get("notice_rules", []):
                if re.fullmatch(rule["coordinate_pattern"], coordinate):
                    matched.append(rule)
                    for asset in rule.get("required_assets", []):
                        evidence, errors = required_asset(asset, distribution, root)
                        component["required_retained_assets"].append(evidence)
                        result["errors"].extend(errors)
            if row["origin"] == "maven" and (not matched or any(rule.get("review_complete") is not True for rule in matched)):
                result["license_review_blockers"].append(f"required_notice_review_unresolved:{coordinate}")
            for rule in policy.get("shaded_review_rules", []):
                if re.fullmatch(rule["coordinate_pattern"], coordinate) and rule.get("review_complete") is not True:
                    result["license_review_blockers"].append(f"shaded_third_party_license_notice_inventory_unresolved:{coordinate}")
            components[component_key] = component
        components[component_key]["distribution_paths"].append(relative)
    for key in identity_map.keys() - seen:
        result["errors"].append(f"Resolved runtime/bundled artifact is absent from installed distribution: {key[0]}")
    for relative in policy.get("required_distribution_files", []):
        source, installed = contained_file(root, relative), contained_file(distribution, relative)
        source_digest = digest_file(source) if source else None
        installed_digest = digest_file(installed) if installed else None
        preserved = source_digest is not None and source_digest == installed_digest
        result["required_attribution_files"].append({"path": relative, "source_digest": source_digest,
                                                    "distribution_digest": installed_digest, "preserved_in_distribution": preserved})
        if not preserved:
            result["errors"].append(f"Required distribution attribution file is absent/unsafe/different: {relative}")
    result["components"] = sorted(components.values(), key=lambda row: (row["coordinate"], row["digest"]))
    result["errors"] = sorted(set(result["errors"]))
    result["license_review_blockers"] = sorted(set(result["license_review_blockers"]))
    result["license_review_status"] = "blocked" if result["errors"] or result["license_review_blockers"] else "reviewed_policy_assets_present"
    result["state"] = "passed" if not result["errors"] else "failed"
    return result


def cyclone_dx(report: dict) -> dict:
    components = []
    for row in report["components"]:
        group, name, version = row["coordinate"].split(":")
        component = {"type": "library", "bom-ref": row["coordinate"] + "@" + row["digest"], "group": group, "name": name, "version": version,
                     "purl": f"pkg:maven/{group}/{name}@{version}", "hashes": [{"alg": "SHA-256", "content": row["digest"].removeprefix("sha256:")}],
                     "properties": [{"name": "ktchecker:licenseEvidence", "value": "POM declaration; required notices/shaded review remain separately assessed"},
                                    {"name": "ktchecker:distributionPaths", "value": json.dumps(row["distribution_paths"])},
                                    {"name": "ktchecker:origin", "value": row["origin"]}]}
        licenses = []
        for declaration in row["pom"]["declared_licenses"]:
            license = {"id": declaration["spdx_id"]} if declaration["spdx_id"] else {"name": declaration["name"] or "NOASSERTION"}
            if declaration["url"]:
                license["url"] = declaration["url"]
            licenses.append({"license": license})
        if licenses:
            component["licenses"] = licenses
        components.append(component)
    return {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "serialNumber": "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, report["dependency_manifest_digest"])),
            "metadata": {"timestamp": report["recorded_at"], "properties": [
                {"name": "ktchecker:sourceDigest", "value": report["source"]["working_tree_digest"]},
                {"name": "ktchecker:licenseReview", "value": report["license_review_status"]},
                {"name": "ktchecker:releaseQualified", "value": "false"}]}, "components": components}


def notice_report(report: dict) -> str:
    lines = ["ktChecker actual distribution dependency inventory", "", "This report is not an owner license grant or redistribution clearance.",
             "License names/URLs below are declarations from exact resolved Maven POMs, not invented licenses.",
             "Required retained license/NOTICE text must also remain verbatim in the distribution.", ""]
    for row in report["components"]:
        lines.extend([row["coordinate"], "  SHA-256: " + row["digest"], "  Files: " + ", ".join(row["distribution_paths"])])
        for license in row["pom"]["declared_licenses"]:
            lines.append("  Declared license: " + (license["name"] or "NOASSERTION") + " — " + (license["url"] or "no URL"))
        if not row["pom"]["declared_licenses"]:
            lines.append("  License: NOASSERTION (original owner code or absent declaration)")
        lines.extend("  Retained asset: " + asset["path"] + " " + asset["digest"] for asset in row["required_retained_assets"])
        lines.append("")
    lines.extend(["Unresolved license/NOTICE release blockers:", *["  " + blocker for blocker in report["license_review_blockers"]],
                  "", "Inventory/required-asset errors:", *["  " + error for error in report["errors"]], ""])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--distribution", type=Path, default=REPO_ROOT / "modules/cli/build/install/ktchecker")
    parser.add_argument("--policy", type=Path, default=Path(__file__).with_name("dependency-notice-policy.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--notice", type=Path, required=True)
    parser.add_argument("--require-license-review", action="store_true", help="Fail on unresolved owner/dependency/shaded/NOTICE review; never assert release qualification")
    args = parser.parse_args()
    try:
        report = generate(args)
        write_json(args.sbom, cyclone_dx(report))
        args.notice.parent.mkdir(parents=True, exist_ok=True)
        args.notice.write_text(notice_report(report), encoding="utf-8")
        report["generated_reports"] = [{"name": path.name, "digest": digest_file(path)} for path in (args.sbom, args.notice)]
    except (OSError, ValueError, TypeError, ET.ParseError, zipfile.BadZipFile, RuntimeError) as error:
        report = {"schema_version": 1, "evidence_kind": "actual_distribution_dependency_inventory", "state": "failed",
                  "errors": [f"Actual dependency inventory failed: {type(error).__name__}"], "license_review_blockers": [],
                  "release_qualified": False, "license_review_status": "unavailable"}
    write_json(args.output, report)
    print(f"Distribution inventory: {report['state']}; license/NOTICE review: {report['license_review_status']}")
    for error in report["errors"] + report["license_review_blockers"]:
        print(error)
    return 0 if report["state"] == "passed" and (not args.require_license_review or not report["license_review_blockers"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
