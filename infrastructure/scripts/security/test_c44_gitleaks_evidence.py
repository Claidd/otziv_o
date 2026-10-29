"""Keep reviewed public C44 hashes scoped to their exact rule, path and value."""
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
FIXTURE = Path(__file__).with_name("fixtures") / "c44-grafana-public-hashes.json"
RULE = "sourcegraph-access-token"


def public_values(item):
    evidence = (ROOT / item["path"]).read_bytes()
    if hashlib.sha256(evidence).hexdigest() != item["sha256"]:
        raise AssertionError("Reviewed evidence bytes changed: " + item["path"])
    values = set()

    def walk(value, path="$"):
        if isinstance(value, dict):
            for key, child in value.items():
                walk(child, path + "." + key)
        elif isinstance(value, list):
            for child in value:
                walk(child, path + "[]")
        elif isinstance(value, str) and path in item["verifiedJsonContexts"]:
            # Match the pinned rule's bare-hash delimiter, not embedded URL path segments.
            values.update(re.findall(r"\b[a-f0-9]{40}\b(?=[`'\"\s;]|\\[nr]|$)", value))

    walk(json.loads(evidence))
    if len(values) != item["publicValueCount"]:
        raise AssertionError("Reviewed public hash inventory changed: " + item["path"])
    return sorted(values)


class C44PublicHashGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
        for item in cls.fixture["files"]:
            item["publicValues"] = public_values(item)
        cls.blocks = [item for item in tomllib.loads((ROOT / ".gitleaks.toml").read_text(encoding="utf-8"))["allowlists"]
                      if item.get("description", "").startswith("C44 Grafana reviewed public ")]

    def allowed(self, path, value, rule=RULE):
        return any(rule in block["targetRules"]
                   and any(re.search(pattern, path) for pattern in block["paths"])
                   and any(re.search(pattern, value) for pattern in block["regexes"])
                   for block in self.blocks)

    def test_exact_public_evidence_is_unchanged_and_covers_all_ci_findings(self):
        self.assertEqual(2, sum(item["findings"] for item in self.fixture["files"]))
        for item in self.fixture["files"]:
            evidence = (ROOT / item["path"]).read_bytes()
            self.assertEqual(item["sha256"], hashlib.sha256(evidence).hexdigest())
            strings = []

            def walk(value, path="$"):
                if isinstance(value, dict):
                    for key, child in value.items():
                        walk(child, path + "." + key)
                elif isinstance(value, list):
                    for child in value:
                        walk(child, path + "[]")
                elif isinstance(value, str):
                    strings.append((path, value))

            walk(json.loads(evidence))
            for value in item["publicValues"]:
                self.assertRegex(value, r"^[a-f0-9]{40}$")
                self.assertTrue(any(value in text and path in item["verifiedJsonContexts"]
                                    for path, text in strings), item["path"])

    def test_allowlists_require_exact_rule_path_and_complete_value(self):
        self.assertEqual(4, len(self.blocks))
        actual = set()
        for block in self.blocks:
            self.assertEqual([RULE], block["targetRules"])
            self.assertEqual("AND", block["condition"])
            self.assertEqual("secret", block["regexTarget"])
            self.assertEqual(1, len(block["paths"]))
            for value in block["regexes"]:
                self.assertRegex(value, r"^\^[a-f0-9]{40}\$$")
                actual.add((block["paths"][0], value))
        expected = {("(^|/)" + re.escape(item["path"]) + "$", "^" + value + "$")
                    for item in self.fixture["files"] for value in item["publicValues"]}
        self.assertEqual(expected, actual)

    def test_changed_values_paths_rules_and_token_prefixes_remain_blocked(self):
        for item in self.fixture["files"]:
            value = item["publicValues"][0]
            self.assertTrue(self.allowed(item["path"], value))
            for wrong_path in [item["path"] + ".copy", item["path"].replace("c44-grafana", "c41-grafana"),
                               "application/settings.json"]:
                self.assertFalse(self.allowed(wrong_path, value))
            for wrong_value in ["0123456789abcdef" * 2 + "01234567", "sgp_" + value,
                                "sgp_local_" + value, value + "0", "0" + value]:
                self.assertFalse(self.allowed(item["path"], wrong_value))
            self.assertFalse(self.allowed(item["path"], value, "generic-api-key"))

    def test_real_scanner_keeps_negative_controls(self):
        scanner = os.environ.get("GITLEAKS_BIN") or shutil.which("gitleaks")
        docker = shutil.which("docker") if os.environ.get("CI") == "true" else None
        if not scanner and not docker:
            if os.environ.get("CI") == "true":
                self.fail("CI requires native Gitleaks or Docker for the negative scanner controls")
            self.skipTest("Set GITLEAKS_BIN to the verified Gitleaks 8.30.1 executable")
        image = re.search(r"ghcr\.io/gitleaks/gitleaks@sha256:[a-f0-9]{64}",
                          (ROOT / ".github/workflows/secret-scan.yml").read_text(encoding="utf-8")).group()
        identity = ["--user", str(os.getuid()) + ":" + str(os.getgid())] if not scanner else []
        command = [scanner] if scanner else [docker, "run", "--rm", "--network", "none"] + identity + [image]
        version = subprocess.check_output(command + ["version"], text=True).strip().lstrip("v")
        self.assertEqual(self.fixture["scannerVersion"], version)
        with tempfile.TemporaryDirectory(prefix="otziv-c44-secret-guard-") as directory:
            base = Path(directory)
            source = base / "source"
            for item in self.fixture["files"]:
                path = source / item["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("sourcegraph public metadata\n" + "\n".join(item["publicValues"]) + "\n", encoding="utf-8")

            def scan():
                report = base / "report.json"
                command = [scanner] if scanner else [
                    docker, "run", "--rm", "--network", "none", *identity, "--read-only", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges:true",
                    "--mount", "type=bind,source=" + str(base) + ",target=/fixture",
                    "--mount", "type=bind,source=" + str(ROOT / ".gitleaks.toml") + ",target=/config.toml,readonly",
                    image]
                result = subprocess.run(command + ["dir", str(source) if scanner else "/fixture/source",
                                         "--config", str(ROOT / ".gitleaks.toml") if scanner else "/config.toml",
                                         "--enable-rule", RULE, "--no-banner", "--report-format", "json",
                                         "--report-path", str(report) if scanner else "/fixture/report.json"],
                                        capture_output=True, text=True)
                self.assertIn(result.returncode, (0, 1), result.stderr)
                return json.loads(report.read_text(encoding="utf-8"))

            self.assertEqual([], scan())
            item = self.fixture["files"][0]
            value = item["publicValues"][0]
            unknown = "0123456789abcdef" * 2 + "01234567"
            with (source / item["path"]).open("a", encoding="utf-8") as stream:
                stream.write(unknown + "\nsgp_" + value + "\n")
            wrong_path = item["path"] + ".copy"
            (source / wrong_path).write_text("sourcegraph\n" + value + "\n", encoding="utf-8")
            findings = scan()
            self.assertEqual(3, len(findings))
            self.assertEqual({unknown, "sgp_" + value, value}, {item["Secret"] for item in findings})
            self.assertTrue(any(item["File"].replace("\\", "/").endswith(wrong_path) for item in findings))


if __name__ == "__main__":
    unittest.main()
