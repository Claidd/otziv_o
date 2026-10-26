"""Keep C41 public advisory commits scoped to the exact evidence and rule."""
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


class C41PublicHashGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
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
        self.assertEqual(1, len(self.blocks))
        block = self.blocks[0]
        self.assertEqual([RULE], block["targetRules"])
        self.assertEqual("AND", block["condition"])
        self.assertEqual("secret", block["regexTarget"])
        self.assertEqual(["(^|/)" + re.escape(EVIDENCE) + "$"], block["paths"])
        self.assertEqual({"^" + item[-1] + "$" for item in PUBLIC_ADVISORIES}, set(block["regexes"]))
        self.assertEqual(3, len(block["regexes"]))

    def test_other_paths_values_rules_and_prefixed_tokens_stay_blocked(self):
        for *_, value in PUBLIC_ADVISORIES:
            self.assertTrue(self.allowed(EVIDENCE, value))
            for path in [EVIDENCE + ".copy", EVIDENCE.replace("c41-grafana", "c40-grafana"),
                         EVIDENCE.replace("c41-grafana", "c42-grafana"),
                         EVIDENCE.replace("c41-grafana", "c41-nginx"), "application/settings.json"]:
                self.assertFalse(self.allowed(path, value))
            for candidate in ["0123456789abcdef" * 2 + "01234567", "sgp_" + value,
                              "sgp_local_" + value, value + "0", "0" + value]:
                self.assertFalse(self.allowed(EVIDENCE, candidate))
            self.assertFalse(self.allowed(EVIDENCE, value, "generic-api-key"))

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
            path = source / EVIDENCE
            path.parent.mkdir(parents=True)
            path.write_text("sourcegraph public metadata\n" + "\n".join(item[-1] for item in PUBLIC_ADVISORIES) + "\n", encoding="utf-8")

            def scan():
                report = base / "report.json"
                command = [scanner] if scanner else [
                    docker, "run", "--rm", "--network", "none", *identity, "--read-only", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--mount", "type=bind,source=" + str(base) + ",target=/fixture",
                    "--mount", "type=bind,source=" + str(ROOT / ".gitleaks.toml") + ",target=/config.toml,readonly", image]
                result = subprocess.run(command + ["dir", str(source) if scanner else "/fixture/source",
                                         "--config", str(ROOT / ".gitleaks.toml") if scanner else "/config.toml",
                                         "--enable-rule", RULE, "--redact", "--no-banner", "--report-format", "json",
                                         "--report-path", str(report) if scanner else "/fixture/report.json"],
                                        capture_output=True, text=True, timeout=120)
                self.assertIn(result.returncode, (0, 1))
                return json.loads(report.read_text(encoding="utf-8"))

            self.assertEqual([], scan())
            value = PUBLIC_ADVISORIES[0][-1]
            with path.open("a", encoding="utf-8") as stream:
                stream.write("0123456789abcdef" * 2 + "01234567\nsgp_" + value + "\n")
            wrong_path = EVIDENCE + ".copy"
            (source / wrong_path).write_text("sourcegraph\n" + value + "\n", encoding="utf-8")
            findings = scan()
            self.assertEqual(3, len(findings))
            self.assertEqual({RULE}, {item["RuleID"] for item in findings})
            self.assertEqual(2, sum(item["File"].replace("\\", "/").endswith(EVIDENCE) for item in findings))
            self.assertEqual(1, sum(item["File"].replace("\\", "/").endswith(wrong_path) for item in findings))


if __name__ == "__main__":
    unittest.main()
