"""Keep C41 public metadata scoped to its exact evidence, value and rule.

Gitleaks 8.30.1 scans Git fragments and percent-decoded text differently from
directory chunks. The initial two-commit CI range found 179 public SHA-1/commit
matches (28 percent-decoded repeats) across these four immutable evidence files.
Both scanner modes below retain decoding and exercise negative controls.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib
import unittest
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[3]
RULE = "sourcegraph-access-token"
EVIDENCE = "infrastructure/runtime-security/proofs/c41-grafana/publication/vulnerabilities.sbom.cdx.json"
EVIDENCE_SHA256 = "777fa25231c08f9402b04e883ba8597e6da5c718f473893c37f6e1c8c412e936"
# Exact JSON positions and source lines from the unmodified Trivy CycloneDX report.
PUBLIC_ADVISORIES = (
    (1, 12, 16869, "CVE-2026-21728", "grafana/tempo", "650eb1985a0776789c85" "64122990f588a742356f"),
    (2, 5, 16949, "CVE-2026-28377", "grafana/tempo", "bb8ca663db34a0980c97" "58b40d918fda3b4dbec3"),
    (3, 12, 17083, "CVE-2026-58055", "nghttp2/nghttp2", "ab28105c4a0197da24f8" "bfc414bc116055249e1e"),
)
PREFIX = "infrastructure/runtime-security/proofs/c41-grafana/"
RAW_SCAN = PREFIX + "publication/vulnerabilities.json"
SPDX_FILES = (PREFIX + "anonymous/registry-attestation-0-payload-0.json",
              PREFIX + "publication/registry-attestation-0-payload-0.json")
FILE_SHA256 = {
    RAW_SCAN: "afe7303f42d55b3eddb3974050d7c007825795494337e05416bf36fe295ddb38",
    EVIDENCE: EVIDENCE_SHA256,
    **{path: "5324147e618248ec7d359eb51e588d3a320ad50558f97641147c29061e2debec" for path in SPDX_FILES},
}
PUBLIC_COMMITS = (
    *(item[3:] for item in PUBLIC_ADVISORIES),
    ("CVE-2026-61709", "openfga/openfga", "171806c93b86bca29e021" "2ceb8b6ee9c48eb9ac3"),
    ("CVE-2026-81871", "open-telemetry/opentelemetry-go", "c65d435b43e5e6b82310e" "6b18dd4cdcb8ac63a0c"),
    ("CVE-2026-81870", "open-telemetry/opentelemetry-go", "3a1412d2b3bc4e4231fb" "eac2ed42117ae541bb38"),
    ("CVE-2026-81872", "open-telemetry/opentelemetry-go", "ba71b09e6ed272e93a66" "9aeaec1e98b1df4cc582"),
)
PUBLIC_REVISIONS = {
    "com.otziv.publication.revision": "632ea46485e75174dbda" "c0d3b88e100231705212",
    "org.opencontainers.image.revision": "81407c71e96e8351b460" "0164c1b4d30c8baf41d6",
}


def public_inventory():
    """Extract only reviewed JSON semantics from hash-pinned original reports."""
    documents = {}
    for path, expected in FILE_SHA256.items():
        data = (ROOT / path).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise AssertionError("Reviewed evidence bytes changed: " + path)
        documents[path] = json.loads(data)
    raw, cdx = documents[RAW_SCAN], documents[EVIDENCE]
    digest_rows = [package for result in raw["Results"] for package in result.get("Packages", [])
                   if re.fullmatch(r"sha1:[a-f0-9]{40}", package.get("Digest", ""))]
    assert len(digest_rows) == 33
    package_hashes = {}
    for package in digest_rows:
        purl = package["Identifier"]["PURL"]
        expected = "pkg:apk/alpine/" + package["Name"] + "@" + package["Version"]
        assert purl.startswith(expected + "?") and "distro=3.24.1" in purl
        assert purl not in package_hashes
        package_hashes[purl] = package["Digest"][5:]
    cdx_hashes = {}
    for component in cdx["components"]:
        hashes = [item["content"] for item in component.get("hashes", []) if item["alg"] == "SHA-1"]
        if hashes:
            assert len(hashes) == 1 and component["purl"] not in cdx_hashes
            cdx_hashes[component["purl"]] = hashes[0]
    assert package_hashes == cdx_hashes

    # Each upstream commit is an exact advisory URL for the named CVE in both formats.
    commits = set()
    for cve, repository, value in PUBLIC_COMMITS:
        assert re.fullmatch(r"[a-f0-9]{40}", value)
        url = "https://github.com/" + repository + "/commit/" + value
        assert any(v["VulnerabilityID"] == cve and url in v.get("References", [])
                   for result in raw["Results"] for v in result.get("Vulnerabilities", []))
        assert any(v["id"] == cve and any(a["url"] == url for a in v.get("advisories", []))
                   for v in cdx["vulnerabilities"])
        commits.add(value)
    labels = raw["Metadata"]["ImageConfig"]["config"]["Labels"]
    properties = {item["name"]: item["value"] for item in cdx["metadata"]["component"]["properties"]}
    for label, value in PUBLIC_REVISIONS.items():
        assert labels[label] == value
        assert properties["aquasecurity:trivy:Labels:" + label] == value
    scan_values = set(package_hashes.values()) | commits | set(PUBLIC_REVISIONS.values())
    assert len(package_hashes) == 33 and len(commits) == 7 and len(scan_values) == 42
    values = {RAW_SCAN: scan_values, EVIDENCE: scan_values.copy()}

    for path in SPDX_FILES:
        spdx = documents[path]
        assert spdx["predicateType"] == "https://spdx.dev/Document"
        packages = [p for p in spdx["predicate"]["packages"] if p.get("packageVerificationCode")]
        assert len(packages) == 33
        empty_sha1 = hashlib.sha1(b"").hexdigest()
        for package in packages:
            assert package["packageVerificationCode"] == {"packageVerificationCodeValue": empty_sha1}
            purls = [x["referenceLocator"] for x in package["externalRefs"]
                     if x["referenceCategory"] == "PACKAGE-MANAGER" and x["referenceType"] == "purl"]
            assert len(purls) == 1
            assert unquote(purls[0]).startswith("pkg:apk/alpine/" + package["name"] + "@" + package["versionInfo"] + "?")
            assert "distro=alpine-3.24.1" in purls[0]
            assert package["downloadLocation"].startswith("https://")
            assert any(row["Name"] == package["name"] and row["Version"] == package["versionInfo"]
                       for row in digest_rows)
        values[path] = {empty_sha1}
    return values


class C41PublicHashGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inventory = public_inventory()
        cls.blocks = [block for block in tomllib.loads((ROOT / ".gitleaks.toml").read_text(encoding="utf-8"))["allowlists"]
                      if block.get("description", "").startswith("C41 Grafana reviewed public ")]

    def allowed(self, path, value, rule=RULE):
        return any(rule in block["targetRules"]
                   and any(re.search(pattern, path) for pattern in block["paths"])
                   and any(re.search(pattern, value) for pattern in block["regexes"])
                   for block in self.blocks)

    def test_exact_evidence_lines_are_public_upstream_advisories(self):
        raw = (ROOT / EVIDENCE).read_bytes()
        self.assertEqual(EVIDENCE_SHA256, hashlib.sha256(raw).hexdigest())
        document = json.loads(raw)
        lines = raw.decode("utf-8").splitlines()
        for vulnerability, advisory, line, cve, repository, value in PUBLIC_ADVISORIES:
            url = "https://github.com/" + repository + "/commit/" + value
            self.assertEqual(cve, document["vulnerabilities"][vulnerability]["id"])
            self.assertEqual(url, document["vulnerabilities"][vulnerability]["advisories"][advisory]["url"])
            self.assertEqual('"url": "' + url + '"', lines[line - 1].strip())

    def test_allowlist_requires_exact_rule_path_and_complete_public_value(self):
        self.assertEqual(4, len(self.blocks))
        expected = {("(^|/)" + re.escape(path) + "$", "^" + value + "$")
                    for path, values in self.inventory.items() for value in values}
        actual = set()
        for block in self.blocks:
            self.assertEqual([RULE], block["targetRules"])
            self.assertEqual("AND", block["condition"])
            self.assertEqual("secret", block["regexTarget"])
            self.assertEqual(1, len(block["paths"]))
            self.assertEqual(len(block["regexes"]), len(set(block["regexes"])))
            for value in block["regexes"]:
                self.assertRegex(value, r"^\^[a-f0-9]{40}\$$")
                actual.add((block["paths"][0], value))
        self.assertEqual(expected, actual)

    def test_public_package_and_advisory_context_is_bound_in_both_formats(self):
        self.assertEqual({RAW_SCAN: 42, EVIDENCE: 42, **{p: 1 for p in SPDX_FILES}},
                         {path: len(values) for path, values in self.inventory.items()})
        self.assertEqual(self.inventory[RAW_SCAN], self.inventory[EVIDENCE])

    def test_other_paths_values_rules_and_prefixed_tokens_stay_blocked(self):
        for evidence, values in self.inventory.items():
            for value in values:
                self.assertTrue(self.allowed(evidence, value))
                for path in [evidence + ".copy", evidence.replace("c41-grafana", "c40-grafana"),
                             evidence.replace("c41-grafana", "c42-grafana"),
                             evidence.replace("c41-grafana", "c41-nginx"), "application/settings.json"]:
                    self.assertFalse(self.allowed(path, value))
                for candidate in ["0123456789abcdef" * 2 + "01234567", "sgp_" + value,
                                  "sgp_local_" + value, value + "0", "0" + value]:
                    self.assertFalse(self.allowed(evidence, candidate))
                self.assertFalse(self.allowed(evidence, value, "generic-api-key"))

    def test_real_scanner_preserves_positive_and_negative_controls(self):
        scanner = os.environ.get("GITLEAKS_BIN") or shutil.which("gitleaks")
        docker = shutil.which("docker") if os.environ.get("CI") == "true" else None
        if not scanner and not docker:
            if os.environ.get("CI") == "true":
                self.fail("CI requires Gitleaks or Docker for scanner controls")
            self.skipTest("Set GITLEAKS_BIN to the verified Gitleaks 8.30.1 executable")
        image = re.search(r"ghcr\.io/gitleaks/gitleaks@sha256:[a-f0-9]{64}",
                          (ROOT / ".github/workflows/secret-scan.yml").read_text(encoding="utf-8")).group()
        identity = ["--user", str(os.getuid()) + ":" + str(os.getgid())] if not scanner else []
        command = [scanner] if scanner else [docker, "run", "--rm", "--network", "none", *identity, image]
        self.assertEqual("8.30.1", subprocess.check_output(command + ["version"], text=True).strip().lstrip("v"))
        with tempfile.TemporaryDirectory(prefix="otziv-c41-secret-guard-") as directory:
            base = Path(directory)
            source = base / "source"
            for evidence, values in self.inventory.items():
                path = source / evidence
                path.parent.mkdir(parents=True, exist_ok=True)
                # Include raw and encoded values. This actually exercises the default
                # decoder instead of testing only a non-encoded directory fragment.
                encoded = ["".join("%%%02X" % ord(c) for c in value) for value in sorted(values)]
                path.write_text("sourcegraph public metadata\n" + "\n".join(sorted(values) + encoded) + "\n", encoding="utf-8")

            def scan(mode):
                report = base / "report.json"
                command = [scanner] if scanner else [
                    docker, "run", "--rm", "--network", "none", *identity, "--read-only", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--mount", "type=bind,source=" + str(base) + ",target=/fixture",
                    "--mount", "type=bind,source=" + str(ROOT / ".gitleaks.toml") + ",target=/config.toml,readonly", image]
                result = subprocess.run(command + [mode, str(source) if scanner else "/fixture/source",
                                         "--config", str(ROOT / ".gitleaks.toml") if scanner else "/config.toml",
                                         "--enable-rule", RULE, "--no-banner", "--report-format", "json",
                                         "--report-path", str(report) if scanner else "/fixture/report.json"]
                                        + (["--log-opts=HEAD"] if mode == "git" else []),
                                        capture_output=True, text=True, timeout=120)
                self.assertIn(result.returncode, (0, 1))
                return json.loads(report.read_text(encoding="utf-8"))

            def commit_fixture():
                for arguments in (["init", "--quiet"], ["add", "."],
                                  ["-c", "user.name=Scanner fixture", "-c", "user.email=fixture@example.invalid",
                                   "-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "Synthetic scanner controls"]):
                    subprocess.run(["git", "-C", str(source), *arguments], check=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30)

            self.assertEqual([], scan("dir"))
            commit_fixture()
            self.assertEqual([], scan("git"))
            value = PUBLIC_ADVISORIES[0][-1]
            unknown = "0123456789abcdef" * 2 + "01234567"
            encoded = "".join("%%%02X" % ord(c) for c in unknown)
            with (source / EVIDENCE).open("a", encoding="utf-8") as stream:
                stream.write(unknown + "\nsgp_" + value + "\n" + encoded + "\n")
            wrong_path = EVIDENCE + ".copy"
            (source / wrong_path).write_text("sourcegraph\n" + value + "\n", encoding="utf-8")
            commit_fixture()
            for mode in ("dir", "git"):
                with self.subTest(mode=mode):
                    findings = scan(mode)
                    self.assertEqual({RULE}, {item["RuleID"] for item in findings})
                    self.assertEqual({unknown, "sgp_" + value, value}, {item["Secret"] for item in findings})
                    self.assertTrue(any(item["Secret"] == unknown and "decoded:percent" in item.get("Tags", []) for item in findings))
                    self.assertTrue(any(item["File"].replace("\\", "/").endswith(wrong_path) and item["Secret"] == value for item in findings))


if __name__ == "__main__":
    unittest.main()
