#!/usr/bin/env python3
"""Stage and verify the portable Atlas SDK Maven repository.

The repository itself is published on the ``atlas-maven`` branch of the
producer repository. This tool deliberately does not talk to Git; the workflow
checks out that branch and stages a version into it. Keeping Git out of the
tool makes the immutable-version and checksum rules testable on a plain
directory.

Commands
--------
``stage``
    Copy a version produced by ``publishAtlasToLocalRepo`` into a repository
    directory, refusing to change an already-published version. It also writes
    the Maven metadata, provenance manifest, and README.

``verify``
    Recompute every checksum, validate the POM graph and provenance, and fail
    on machine-specific paths. This is the gate used by CI and by the
    publication workflow.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable, Optional

GROUP_ID = "com.intellitrip.atlas"
GROUP_PATH = Path("com") / "intellitrip" / "atlas"
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
CHECKSUM_SUFFIXES = tuple("." + algorithm for algorithm in CHECKSUMS)
REQUIRED_ARTIFACTS = (
    "atlas-engine",
    "atlas-engine-jvm",
    "atlas-gtfs-static",
    "atlas-gtfs-static-jvm",
)
ENGINE_JVM_DEPENDENCIES = {
    "atlas-domain-jvm",
    "atlas-contracts-jvm",
    "atlas-scope-jvm",
    "atlas-routing-jvm",
    "atlas-map-jvm",
    "atlas-gtfs-static-jvm",
    "atlas-gtfs-realtime-jvm",
    "atlas-weather-jvm",
}
SUSPICIOUS_PATH_PATTERNS = (
    re.compile(r"C:\\\\", re.IGNORECASE),
    re.compile(r"C:[/\\]", re.IGNORECASE),
    re.compile(r"/Users/"),
    re.compile(r"/home/"),
    re.compile(r"file:/", re.IGNORECASE),
    re.compile(r"file:///", re.IGNORECASE),
)


class PublicationError(RuntimeError):
    """A publication contract violation."""


def _sha(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _is_checksum(path: Path) -> bool:
    return path.name.endswith(CHECKSUM_SUFFIXES)


def _checksum_files(path: Path) -> Iterable[Path]:
    return (path.with_name(path.name + ".{}".format(algorithm)) for algorithm in CHECKSUMS)


def _write_checksums(path: Path) -> None:
    for algorithm, checksum_path in zip(CHECKSUMS, _checksum_files(path)):
        checksum_path.write_text(_sha(path, algorithm) + "\n", encoding="ascii", newline="\n")


def _verify_checksums(path: Path) -> None:
    for algorithm, checksum_path in zip(CHECKSUMS, _checksum_files(path)):
        if not checksum_path.exists():
            raise PublicationError("missing {} checksum for {}".format(algorithm, path))
        expected = checksum_path.read_text(encoding="ascii").strip()
        actual = _sha(path, algorithm)
        if expected != actual:
            raise PublicationError(
                "{} checksum mismatch for {} (expected {}, got {})".format(
                    algorithm, path, expected, actual
                )
            )


def _all_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*") if path.is_file())


def _non_checksum_files(root: Path) -> list[Path]:
    return [path for path in _all_files(root) if not _is_checksum(path)]


def _relative_files(root: Path) -> list[Path]:
    return [path.relative_to(root) for path in _non_checksum_files(root)]


def _read_version(repo_root: Path) -> str:
    properties = repo_root / "gradle.properties"
    if not properties.exists():
        raise PublicationError("gradle.properties not found at {}".format(properties))
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("atlasVersion="):
            value = line.split("=", 1)[1].strip()
            if value:
                return value
    raise PublicationError("atlasVersion is not set in {}".format(properties))


def _artifact_version_dirs(source: Path, version: str) -> dict[str, Path]:
    result: dict[str, Path] = {}
    group_root = source / GROUP_PATH
    if not group_root.is_dir():
        raise PublicationError("source publication has no {} directory".format(GROUP_PATH))
    for artifact_dir in sorted(path for path in group_root.iterdir() if path.is_dir()):
        version_dir = artifact_dir / version
        if version_dir.is_dir():
            result[artifact_dir.name] = version_dir
    if not result:
        raise PublicationError("no {} artifacts found under {}".format(version, group_root))
    return result


def _compare_published_version(source: Path, destination: Path) -> None:
    source_files = _relative_files(source)
    destination_files = _relative_files(destination)
    if source_files != destination_files:
        missing = sorted(set(destination_files) - set(source_files))
        added = sorted(set(source_files) - set(destination_files))
        raise PublicationError(
            "immutable version violation: file set differs for {}\n"
            "  missing from source: {}\n"
            "  added by source: {}".format(
                destination,
                ", ".join(str(path) for path in missing) or "(none)",
                ", ".join(str(path) for path in added) or "(none)",
            )
        )
    for relative in source_files:
        source_hash = _sha(source / relative, "sha256")
        destination_hash = _sha(destination / relative, "sha256")
        if source_hash != destination_hash:
            raise PublicationError(
                "immutable version violation: {} differs (source {}, published {})".format(
                    relative, source_hash, destination_hash
                )
            )


def _metadata_timestamp(destination: Path, version: str) -> str:
    """Use the immutable provenance timestamp, never the current clock.

    A rerun of the same version must not produce a new ``maven-metadata.xml``.
    The publication time is already recorded once in provenance, so derive the
    Maven timestamp from that record. Missing provenance (only possible in a
    hand-made test directory) falls back to the Unix epoch rather than ``now``.
    """
    provenance = _provenance_path(destination, version)
    if provenance.exists():
        payload = json.loads(provenance.read_text(encoding="utf-8"))
        published_at = payload.get("publishedAt")
        if published_at:
            parsed = _dt.datetime.fromisoformat(published_at.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=_dt.timezone.utc)
            return parsed.astimezone(_dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    return "19700101000000"


def _write_maven_metadata(destination: Path, version: str) -> None:
    """Regenerate artifact metadata from the version directories actually present."""
    group_root = destination / GROUP_PATH
    if not group_root.is_dir():
        return
    for artifact_dir in sorted(path for path in group_root.iterdir() if path.is_dir()):
        versions = sorted(
            (
                path.name
                for path in artifact_dir.iterdir()
                if path.is_dir() and (path / "{}-{}.pom".format(artifact_dir.name, path.name)).exists()
            ),
            key=_version_key,
        )
        if not versions:
            continue
        latest = versions[-1]
        metadata = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            "<metadata>\n"
            "  <groupId>{}</groupId>\n"
            "  <artifactId>{}</artifactId>\n"
            "  <versioning>\n"
            "    <latest>{}</latest>\n"
            "    <release>{}</release>\n"
            "    <versions>\n"
            "{}\n"
            "    </versions>\n"
            "    <lastUpdated>{}</lastUpdated>\n"
            "  </versioning>\n"
            "</metadata>\n"
        ).format(
            GROUP_ID,
            artifact_dir.name,
            latest,
            latest,
            "\n".join("      <version>{}</version>".format(item) for item in versions),
            _metadata_timestamp(destination, latest),
        )
        metadata_path = artifact_dir / "maven-metadata.xml"
        metadata_path.write_text(metadata, encoding="utf-8", newline="\n")
        # Remove stale metadata checksum files before writing fresh ones.
        for checksum_path in _checksum_files(metadata_path):
            if checksum_path.exists():
                checksum_path.unlink()
        _write_checksums(metadata_path)


def _version_key(version: str) -> tuple:
    parts = re.split(r"[.+-]", version)
    key = []
    for part in parts:
        key.append((0, int(part)) if part.isdigit() else (1, part))
    return tuple(key)


def _write_readme(destination: Path, version: str) -> None:
    readme = destination / "README.md"
    text = """# Atlas Maven repository

This branch is the immutable Maven repository for the portable IntelliTrip
Atlas SDK. It is generated, never hand-edited.

- Group: `{group}`
- Latest version: `{version}`
- Maven URL: `https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven`
- Provenance: `provenance/{version}.json`

## Consume

```kotlin
repositories {{
    maven {{ url = uri("https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven") }}
}}

dependencies {{
    implementation("com.intellitrip.atlas:atlas-engine:{version}")
}}
```

The engine pulls its portable capabilities transitively. POMs, Gradle module
metadata, sources, and MD5/SHA-1/SHA-256/SHA-512 checksums are published for
every module.

## Immutability

A version directory is published once. The publication tool refuses to change
an existing version and records the source commit, build environment, and
artifact hashes under `provenance/`. To ship a change, bump `atlasVersion` in
the producer's `gradle.properties` and publish a new version.
""".format(group=GROUP_ID, version=version)
    if not readme.exists() or readme.read_text(encoding="utf-8") != text:
        readme.write_text(text, encoding="utf-8", newline="\n")
        for checksum_path in _checksum_files(readme):
            if checksum_path.exists():
                checksum_path.unlink()
        _write_checksums(readme)


def _artifact_manifest(destination: Path, version: str) -> list[dict]:
    manifest = []
    group_root = destination / GROUP_PATH
    for artifact_dir in sorted(path for path in group_root.iterdir() if path.is_dir()):
        version_dir = artifact_dir / version
        if not version_dir.is_dir():
            continue
        for path in sorted(_non_checksum_files(version_dir)):
            manifest.append(
                {
                    "path": str(path.relative_to(destination)).replace(os.sep, "/"),
                    "size": path.stat().st_size,
                    "sha256": _sha(path, "sha256"),
                }
            )
    return manifest


def _provenance_path(destination: Path, version: str) -> Path:
    return destination / "provenance" / "{}.json".format(version)


def _write_provenance(
    destination: Path,
    version: str,
    source_commit: str,
    source_ref: str,
    repository_url: str,
    gradle_version: str,
    java_version: str,
) -> None:
    path = _provenance_path(destination, version)
    manifest = _artifact_manifest(destination, version)
    existing: Optional[dict] = None
    if path.exists():
        existing = json.loads(path.read_text(encoding="utf-8"))
        if existing.get("version") != version:
            raise PublicationError("existing provenance version does not match {}".format(version))
        if existing.get("source", {}).get("commit") != source_commit:
            raise PublicationError(
                "immutable provenance violation: {} was published from {}, not {}".format(
                    version, existing.get("source", {}).get("commit"), source_commit
                )
            )
        if existing.get("artifacts") != manifest:
            raise PublicationError(
                "immutable provenance violation: artifact manifest changed for {}".format(version)
            )
        return
    payload = {
        "schemaVersion": 1,
        "groupId": GROUP_ID,
        "version": version,
        "source": {
            "repository": repository_url,
            "commit": source_commit,
            "ref": source_ref,
        },
        "build": {
            "gradle": gradle_version or "unknown",
            "java": java_version or "unknown",
            "command": "./gradlew publishAtlasToLocalRepo",
        },
        "publishedAt": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        "artifacts": manifest,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    for checksum_path in _checksum_files(path):
        if checksum_path.exists():
            checksum_path.unlink()
    _write_checksums(path)


def _pom_namespace(root: ET.Element) -> str:
    if root.tag.startswith("{"):
        return root.tag.split("}", 1)[0] + "}"
    return ""


def _verify_pom(path: Path, version: str) -> None:
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    namespace = _pom_namespace(root)

    def text(name: str) -> Optional[str]:
        value = root.find(namespace + name)
        return value.text.strip() if value is not None and value.text else None

    if text("groupId") != GROUP_ID:
        raise PublicationError("{} has unexpected groupId {}".format(path, text("groupId")))
    if text("version") != version:
        raise PublicationError("{} has unexpected version {}".format(path, text("version")))
    artifact_id = text("artifactId")
    if not artifact_id:
        raise PublicationError("{} has no artifactId".format(path))
    for dependency in root.findall(".//{}dependency".format(namespace)):
        group = dependency.find(namespace + "groupId")
        dependency_version = dependency.find(namespace + "version")
        if group is not None and group.text == GROUP_ID:
            if dependency_version is None or dependency_version.text.strip() != version:
                raise PublicationError(
                    "{} has an Atlas dependency without version {}: {}".format(
                        path, version, dependency_version.text if dependency_version is not None else "none"
                    )
                )


def _verify_engine_dependencies(destination: Path, version: str) -> None:
    pom = destination / GROUP_PATH / "atlas-engine-jvm" / version / "atlas-engine-jvm-{}.pom".format(version)
    root = ET.fromstring(pom.read_text(encoding="utf-8"))
    namespace = _pom_namespace(root)
    dependencies = {
        dependency.find(namespace + "artifactId").text
        for dependency in root.findall(".//{}dependency".format(namespace))
        if dependency.find(namespace + "groupId") is not None
        and dependency.find(namespace + "groupId").text == GROUP_ID
        and dependency.find(namespace + "artifactId") is not None
    }
    missing = ENGINE_JVM_DEPENDENCIES - dependencies
    if missing:
        raise PublicationError(
            "atlas-engine-jvm POM is missing transitive Atlas modules: {}".format(", ".join(sorted(missing)))
        )


def _verify_no_absolute_paths(destination: Path) -> None:
    for path in _non_checksum_files(destination):
        if path.suffix.lower() not in {".pom", ".module", ".json", ".xml", ".md"}:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for pattern in SUSPICIOUS_PATH_PATTERNS:
            if pattern.search(text):
                raise PublicationError(
                    "machine-specific path found in {}: {}".format(path, pattern.pattern)
                )


def verify(destination: Path, version: str) -> None:
    destination = destination.resolve()
    if not destination.is_dir():
        raise PublicationError("repository directory does not exist: {}".format(destination))
    group_root = destination / GROUP_PATH
    if not group_root.is_dir():
        raise PublicationError("{} has no {} directory".format(destination, GROUP_PATH))

    artifact_dirs = _artifact_version_dirs(destination, version)
    for artifact in REQUIRED_ARTIFACTS:
        if artifact not in artifact_dirs:
            raise PublicationError("required artifact {}:{} is missing".format(GROUP_ID, artifact))

    for artifact, version_dir in artifact_dirs.items():
        base = "{}-{}".format(artifact, version)
        if not (version_dir / (base + ".pom")).exists():
            raise PublicationError("{} is missing its POM".format(version_dir))
        if not (version_dir / (base + ".module")).exists():
            raise PublicationError("{} is missing Gradle module metadata".format(version_dir))
        if artifact.endswith("-jvm") and not (version_dir / (base + ".jar")).exists():
            raise PublicationError("{} is missing its JVM jar".format(version_dir))
        if artifact.endswith("-wasm-js") and not (version_dir / (base + ".klib")).exists():
            raise PublicationError("{} is missing its wasm klib".format(version_dir))
        if not artifact.endswith("-jvm") and not artifact.endswith("-wasm-js"):
            if not (version_dir / (base + ".jar")).exists():
                raise PublicationError("{} is missing its metadata jar".format(version_dir))
        for path in _non_checksum_files(version_dir):
            _verify_checksums(path)
        _verify_pom(version_dir / (base + ".pom"), version)

    _verify_engine_dependencies(destination, version)

    provenance = _provenance_path(destination, version)
    if not provenance.exists():
        raise PublicationError("missing provenance manifest {}".format(provenance))
    _verify_checksums(provenance)
    manifest = json.loads(provenance.read_text(encoding="utf-8"))
    if manifest.get("version") != version:
        raise PublicationError("provenance version mismatch in {}".format(provenance))
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("source", {}).get("commit", "")):
        raise PublicationError("provenance source commit is not a 40-character Git SHA")
    recorded = {entry["path"]: entry["sha256"] for entry in manifest.get("artifacts", [])}
    actual = {
        str(path.relative_to(destination)).replace(os.sep, "/"): _sha(path, "sha256")
        for path in _all_files(group_root)
        if not _is_checksum(path) and path.parent.name == version
    }
    if recorded != actual:
        missing = sorted(set(actual) - set(recorded))
        stale = sorted(set(recorded) - set(actual))
        raise PublicationError(
            "provenance artifact manifest does not match the repository\n"
            "  missing entries: {}\n"
            "  stale entries: {}".format(
                ", ".join(missing) or "(none)", ", ".join(stale) or "(none)"
            )
        )

    for artifact_dir in sorted(path for path in group_root.iterdir() if path.is_dir()):
        metadata = artifact_dir / "maven-metadata.xml"
        if metadata.exists():
            _verify_checksums(metadata)
            if "<version>{}</version>".format(version) not in metadata.read_text(encoding="utf-8"):
                raise PublicationError("{} does not list version {}".format(metadata, version))

    _verify_no_absolute_paths(destination)
    print(
        "Atlas Maven verification OK: version={} artifacts={} files={}".format(
            version, len(artifact_dirs), len(_all_files(destination))
        )
    )


def stage(
    source: Path,
    destination: Path,
    version: str,
    source_commit: str,
    source_ref: str,
    repository_url: str,
    gradle_version: str,
    java_version: str,
) -> None:
    source = source.resolve()
    destination = destination.resolve()
    if not source.is_dir():
        raise PublicationError("source publication does not exist: {}".format(source))
    destination.mkdir(parents=True, exist_ok=True)

    source_artifacts = _artifact_version_dirs(source, version)
    if len(source_artifacts) < len(REQUIRED_ARTIFACTS):
        missing = set(REQUIRED_ARTIFACTS) - set(source_artifacts)
        raise PublicationError(
            "source publication is missing required artifacts: {}".format(", ".join(sorted(missing)))
        )

    for artifact, version_dir in sorted(source_artifacts.items()):
        target = destination / GROUP_PATH / artifact / version
        if target.exists():
            _compare_published_version(version_dir, target)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(version_dir, target)

    published_artifacts = _artifact_version_dirs(destination, version)
    if set(published_artifacts) != set(source_artifacts):
        raise PublicationError(
            "published artifact set differs from source for version {}: source={}, published={}".format(
                version,
                sorted(source_artifacts),
                sorted(published_artifacts),
            )
        )

    for artifact, version_dir in sorted(published_artifacts.items()):
        for path in _non_checksum_files(version_dir):
            _verify_checksums(path)
        for checksum in version_dir.iterdir():
            if _is_checksum(checksum) and not checksum.with_name(
                checksum.name.rsplit(".", 1)[0]
            ).exists():
                raise PublicationError("orphan checksum file {}".format(checksum))

    _write_provenance(
        destination,
        version,
        source_commit,
        source_ref,
        repository_url,
        gradle_version,
        java_version,
    )
    _write_maven_metadata(destination, version)
    _write_readme(destination, version)
    verify(destination, version)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    stage_parser = subparsers.add_parser("stage", help="stage a version into a repository directory")
    stage_parser.add_argument("--source", required=True, type=Path, help="build/atlas-maven directory")
    stage_parser.add_argument("--destination", required=True, type=Path, help="atlas-maven branch worktree")
    stage_parser.add_argument("--version", default=None, help="version; defaults to gradle.properties atlasVersion")
    stage_parser.add_argument("--repo-root", default=".", type=Path, help="producer checkout for gradle.properties")
    stage_parser.add_argument("--source-commit", required=True)
    stage_parser.add_argument("--source-ref", required=True)
    stage_parser.add_argument("--repository-url", required=True)
    stage_parser.add_argument("--gradle-version", default="unknown")
    stage_parser.add_argument("--java-version", default="unknown")

    verify_parser = subparsers.add_parser("verify", help="verify a repository directory")
    verify_parser.add_argument("--repo", required=True, type=Path)
    verify_parser.add_argument("--version", required=True)

    return parser


def main() -> int:
    args = _parser().parse_args()
    try:
        if args.command == "stage":
            version = args.version or _read_version(args.repo_root)
            stage(
                args.source,
                args.destination,
                version,
                args.source_commit,
                args.source_ref,
                args.repository_url,
                args.gradle_version,
                args.java_version,
            )
        elif args.command == "verify":
            verify(args.repo, args.version)
        else:
            raise AssertionError(args.command)
    except PublicationError as error:
        print("Atlas publication error: {}".format(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
