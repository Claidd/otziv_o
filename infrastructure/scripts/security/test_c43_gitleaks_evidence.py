"""Keep reviewed public C43 hashes scoped to their exact rule, path and value."""
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
FIXTURE = Path(__file__).with_name("fixtures") / "c43-alloy-public-hashes.json"
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
            # Git-fragment scanning also decodes percent-encoded advisory URLs.
            # Match the pinned rule's delimiter after the same decoding step.
            values.update(re.findall(r"\b[a-f0-9]{40}\b(?=[`'\"\s;]|\\[nr]|$)", unquote(value)))

    walk(json.loads(evidence))
    if len(values) != item["publicValueCount"]:
        raise AssertionError("Reviewed public hash inventory changed: " + item["path"])
    return sorted(values)


class C43PublicHashGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
        for item in cls.fixture["files"]:
            item["publicValues"] = public_values(item)
        cls.inventory = {item["path"]: item["publicValues"] for item in cls.fixture["files"]}
        cls.blocks = [item for item in tomllib.loads((ROOT / ".gitleaks.toml").read_text(encoding="utf-8"))["allowlists"]
                      if item.get("description", "").startswith("C43 reviewed public ")]

    def allowed(self, path, value, rule=RULE):
        return any(rule in block["targetRules"]
                   and any(re.search(pattern, path) for pattern in block["paths"])
                   and any(re.search(pattern, value) for pattern in block["regexes"])
                   for block in self.blocks)

    def test_exact_public_evidence_is_unchanged_and_covers_all_ci_findings(self):
        self.assertEqual(82, sum(item["findings"] for item in self.fixture["files"]))
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
        self.assertEqual(7, len(self.blocks))
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
            for wrong_path in [item["path"] + ".copy", item["path"].replace("c43-", "c42-"),
                               "application/settings.json"]:
                self.assertFalse(self.allowed(wrong_path, value))
            for wrong_value in ["0123456789abcdef" * 2 + "01234567", "sgp_" + value,
                                "sgp_local_" + value, value + "0", "0" + value]:
                self.assertFalse(self.allowed(item["path"], wrong_value))
            self.assertFalse(self.allowed(item["path"], value, "generic-api-key"))

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
        with tempfile.TemporaryDirectory(prefix="otziv-c43-secret-guard-") as directory:
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
            value = self.fixture["files"][0]["publicValues"][0]
            unknown = "0123456789abcdef" * 2 + "01234567"
            encoded = "".join("%%%02X" % ord(c) for c in unknown)
            with (source / self.fixture["files"][0]["path"]).open("a", encoding="utf-8") as stream:
                stream.write(unknown + "\nsgp_" + value + "\n" + encoded + "\n")
            wrong_path = self.fixture["files"][0]["path"] + ".copy"
            (source / wrong_path).write_text("sourcegraph\n" + value + "\n", encoding="utf-8")
            commit_fixture()
            for mode in ("dir", "git"):
                with self.subTest(mode=mode):
                    findings = scan(mode)
                    self.assertEqual({RULE}, {item["RuleID"] for item in findings})
                    self.assertEqual({unknown, "sgp_" + value, value}, {item["Secret"] for item in findings})
                    self.assertTrue(any(item["Secret"] == unknown and "decoded:percent" in item.get("Tags", []) for item in findings))
                    self.assertTrue(any(item["File"].replace("\\", "/").endswith(wrong_path) and item["Secret"] == value for item in findings))


class C43PublicMavenCoordinateGuardTest(unittest.TestCase):
    def test_only_exact_public_name_line_in_exact_report_is_exempted(self):
        path = "infrastructure/runtime-security/proofs/c43-keycloak/publication/vulnerabilities.json"
        blocks = [b for b in tomllib.loads((ROOT / ".gitleaks.toml").read_text(encoding="utf-8"))["allowlists"]
                  if b.get("description") == "C43 Keycloak exact public Maven package coordinate"]
        self.assertEqual(1, len(blocks))
        block = blocks[0]
        self.assertEqual(["generic-api-key"], block["targetRules"])
        self.assertEqual("AND", block["condition"])
        self.assertEqual("line", block["regexTarget"])
        self.assertEqual(["(^|/)" + re.escape(path) + "$"], block["paths"])
        line = '          "Name": "org.keycloak:keycloak-crypto-' + 'fips1402",'
        data = (ROOT / path).read_text(encoding="utf-8")
        self.assertEqual(2, data.splitlines().count(line))
        packages = [p for r in json.loads(data)["Results"] for p in r.get("Packages", [])
                    if p["Name"] == "org.keycloak:keycloak-crypto-" + "fips1402"]
        self.assertEqual(2, len(packages))
        self.assertTrue(all(p["Identifier"]["PURL"].startswith("pkg:maven/org.keycloak/keycloak-crypto-" + "fips1402@") for p in packages))
        for ending in ["", "\r", "\n"]:
            self.assertTrue(any(re.search(r, line + ending) for r in block["regexes"]))
        for wrong in [line + ' "api_key": "unknown-value"', line.replace("fips1402", "fips1403"),
                      '"api_key": "keycloak-crypto-' + 'fips1402"', 'prefix' + line]:
            self.assertFalse(any(re.search(r, wrong) for r in block["regexes"]))
        self.assertFalse(any(re.search(r, path + ".copy") for r in block["paths"]))


if __name__ == "__main__":
    unittest.main()
