"""Finite build-support reactor audit; credentials remain in the environment/settings.

The only network audit is an explicit invocation without --plan/--verify-effective.
No lifecycle/profile is activated, so the app profile is not executed twice.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
GOAL = "com.hunt.build:dependency-check-effective-maven-plugin:1.0.0:check"
HELP_GOAL = "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom"
MODULES = ("dependency-audit", "site-plugin", "test-transport")
PROPERTIES = {
    "dependency-check.skip": "false",
    "odc.plugins.scan": "true",
    "odc.dependencies.scan": "true",
    "skipTestScope": "false",
    "skipProvidedScope": "false",
    "skipSystemScope": "false",
    "skipRuntimeScope": "false",
    "failBuildOnCVSS": "7.0",
    "failOnError": "true",
    "formats": "HTML,JSON",
    "ossIndexAnalyzerEnabled": "true",
    "ossIndexServerId": "otziv-sonatype",
    "ossIndexWarnOnlyOnRemoteErrors": "false",
    "ossIndexAnalyzerUseCache": "true",
    "ossIndexAnalyzerCacheValidForHours": "24",
    "nvdDatafeedUrl": "https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz",
    "autoUpdate": "true",
}


class PolicyError(ValueError):
    pass


def parse_xml(path: Path) -> ET.Element:
    data = path.read_bytes()
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise PolicyError("DTD/entity declarations are not allowed in audit configuration")
    return ET.fromstring(data)


def text(element: ET.Element, name: str, default: str = "") -> str:
    return element.findtext("m:" + name, default, NS).strip()


def key(element: ET.Element) -> str:
    return text(element, "groupId", "org.apache.maven.plugins") + ":" + text(element, "artifactId")


def reactor(root: Path) -> dict[str, Path]:
    root = root.resolve(strict=True)
    project = parse_xml(root / "pom.xml")
    actual = [node.text.strip() for node in project.findall("m:modules/m:module", NS)]
    if actual != list(MODULES):
        raise PolicyError("The audited reactor must contain exactly the three reviewed modules in order")
    result = {".": root / "pom.xml"}
    for name in MODULES:
        module = (root / name / "pom.xml").resolve(strict=True)
        if not module.is_relative_to(root):
            raise PolicyError("A reactor module escaped the build-support directory")
        result[name] = module
    return result


def projects_for(root: Path, standalone: str | None = None) -> dict[str, Path]:
    if standalone is None:
        return reactor(root)
    if standalone != "keycloak":
        raise PolicyError("Unreviewed standalone audit project")
    # This fixed repository seam cannot target an arbitrary POM via a CLI argument.
    root = root.resolve(strict=True)
    if root.name != "build-support" or root.parent.name != "backend":
        raise PolicyError("Standalone audit requires the repository backend/build-support location")
    repository = root.parent.parent
    path = (repository / "infrastructure/keycloak/security-generation/pom.xml").resolve(strict=True)
    if not path.is_relative_to(repository):
        raise PolicyError("Standalone project escaped the repository")
    source = parse_xml(path)
    if (text(source, "groupId"), text(source, "artifactId"), text(source, "version")) != (
            "com.hunt.otziv", "keycloak-security-generation", "1.0.0"):
        raise PolicyError("Unexpected standalone project GAV")
    if source.findtext("m:properties/m:maven.compiler.release", "", NS) != "21":
        raise PolicyError("Standalone issuer must retain Java release 21")
    return {"keycloak": path}


def verify_policy(root: Path, effective: Path, standalone: str | None = None) -> dict:
    expected_projects = projects_for(root, standalone)
    policy = json.loads((root / "plugin-policy.json").read_text(encoding="utf-8"))
    rules = {item["coordinate"]: item for item in policy["plugins"]}
    if len(rules) != len(policy["plugins"]):
        raise PolicyError("Duplicate plugin policy coordinate")
    document = parse_xml(effective)
    projects = list(document) if document.tag.endswith("projects") else [document]
    expected_ids = {}
    for name, path in expected_projects.items():
        project = parse_xml(path)
        group = text(project, "groupId") or text(project.find("m:parent", NS), "groupId")
        expected_ids[group + ":" + text(project, "artifactId")] = (name, text(project, "version"))
    observed = {}
    for project in projects:
        identity = text(project, "groupId") + ":" + text(project, "artifactId")
        if identity not in expected_ids or identity in observed:
            raise PolicyError("Unexpected or duplicate project in the effective reactor")
        expected_name, expected_version = expected_ids[identity]
        if text(project, "version") != expected_version:
            raise PolicyError("Effective project version drift")
        if standalone and project.findtext("m:properties/m:maven.compiler.release", "", NS) != "21":
            raise PolicyError("Effective standalone Java release drift")
        plugins = project.findall("m:build/m:plugins/m:plugin", NS)
        managed = {key(item): item for item in project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)}
        # Every independent POM must inherit no hidden policy: all common pins are explicit.
        for coordinate, rule in rules.items():
            if standalone and coordinate not in managed:
                continue  # standalone does not need to activate or manage unused bootstrap tooling
            if coordinate not in managed or text(managed[coordinate], "version") != rule["version"]:
                raise PolicyError("Missing or changed managed plugin pin: " + coordinate)
            actual_dependencies = {key(dep): text(dep, "version") for dep in managed[coordinate].findall("m:dependencies/m:dependency", NS)}
            for dependency, version in rule.get("dependencies", {}).items():
                if actual_dependencies.get(dependency) != version:
                    raise PolicyError("Managed plugin dependency override drift: " + coordinate + " / " + dependency)
        active = []
        for plugin in plugins:
            coordinate = key(plugin)
            if coordinate not in rules:
                raise PolicyError("Unreviewed active build plugin: " + coordinate)
            rule = rules[coordinate]
            if text(plugin, "version") != rule["version"]:
                raise PolicyError("Effective plugin version drift: " + coordinate)
            dependencies = {key(dep): text(dep, "version") for dep in plugin.findall("m:dependencies/m:dependency", NS)}
            for dependency, version in rule.get("dependencies", {}).items():
                if dependencies.get(dependency) != version:
                    raise PolicyError("Effective plugin dependency override drift: " + coordinate + " / " + dependency)
            active.append({"coordinate": coordinate, "version": rule["version"], "dependencies": dependencies})
        reporting = []
        for plugin in project.findall("m:reporting/m:plugins/m:plugin", NS):
            coordinate = key(plugin)
            if coordinate not in rules or text(plugin, "version") != rules[coordinate]["version"]:
                raise PolicyError("Unreviewed or unpinned reporting plugin: " + coordinate)
            reporting.append({"coordinate": coordinate, "version": text(plugin, "version")})
        extensions = []
        extension_rules = policy.get("extensions", {})
        for extension in project.findall("m:build/m:extensions/m:extension", NS):
            coordinate = key(extension)
            if extension_rules.get(coordinate) != text(extension, "version"):
                raise PolicyError("Unreviewed build extension: " + coordinate)
            extensions.append({"coordinate": coordinate, "version": text(extension, "version")})
        observed[identity] = {"module": expected_name, "activePlugins": active,
                              "reportingPlugins": reporting, "extensions": extensions}
    if set(observed) != set(expected_ids):
        raise PolicyError("Effective POM did not contain every audited project")
    return {"result": "PASS", "projects": observed, "policySha256": digest(root / "plugin-policy.json"),
            "effectivePomSha256": digest(effective)}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def absolute_file(value: str, label: str) -> Path:
    path = Path(value)
    if not path.is_absolute() or not path.is_file():
        raise PolicyError(label + " must be an existing absolute file path")
    return path.resolve()


def configuration(args, environment: dict[str, str], require_token: bool = True) -> dict:
    root = Path(args.root).resolve(strict=True)
    projects_for(root, getattr(args, "standalone_project", None))
    settings = absolute_file(args.settings, "Maven settings")
    suppression = absolute_file(args.suppression_file, "Suppression file")
    data = Path(args.data_directory)
    if not data.is_absolute():
        raise PolicyError("Public analyzer data directory must be absolute")
    data = data.resolve()
    if settings.is_relative_to(data) or suppression.is_relative_to(data):
        raise PolicyError("Settings and policy must remain outside the public analyzer cache")
    # setup-java produces env references; the runner never receives a credential argument.
    servers = parse_xml(settings).findall("m:servers/m:server", {"m": "http://maven.apache.org/SETTINGS/1.0.0"})
    if not servers:  # Maven also accepts namespace-free settings.
        servers = parse_xml(settings).findall("servers/server")
    matched = []
    for server in servers:
        values = {child.tag.split("}")[-1]: (child.text or "").strip() for child in server}
        if values.get("id") == "otziv-sonatype":
            matched.append(values)
    if len(matched) != 1 or matched[0].get("password") != "${env.SONATYPE_GUIDE_TOKEN}":
        raise PolicyError("Expected the approved Sonatype settings server with an environment-only password")
    token = environment.get("SONATYPE_GUIDE_TOKEN", "")
    if require_token and not re.fullmatch(r"\S+", token):
        raise PolicyError("SONATYPE_GUIDE_TOKEN is required and must contain no whitespace")
    if require_token and not environment.get("SONATYPE_GUIDE_USERNAME", "").strip():
        raise PolicyError("SONATYPE_GUIDE_USERNAME is required by the Maven settings server")
    for variable in ("MAVEN_ARGS", "MAVEN_CONFIG"):
        if environment.get(variable, "").strip():
            raise PolicyError(variable + " must be empty: implicit Maven arguments cannot narrow the audit")
    for location in (root / ".mvn/maven.config", root.parent / ".mvn/maven.config"):
        if location.exists() and location.read_text(encoding="utf-8").strip():
            raise PolicyError("Implicit Maven project arguments are not supported by the complete-audit runner")
    parse_xml(suppression)
    return {"root": root, "settings": settings, "suppression": suppression, "data": data,
            "projects": projects_for(root, getattr(args, "standalone_project", None))}


def command(args, config: dict, module: str = ".") -> list[str]:
    selected = config.get("projects") or reactor(config["root"])
    if module not in selected:
        raise PolicyError("Unreviewed audit project")
    result = [args.maven, "-B", "-ntp", "-N", "-s", str(config["settings"]),
              "-f", str(selected[module])]
    if args.repository:
        repository = Path(args.repository)
        if not repository.is_absolute():
            raise PolicyError("Maven repository must be absolute")
        result.append("-Dmaven.repo.local=" + str(repository.resolve()))
    result.extend("-D" + name + "=" + value for name, value in PROPERTIES.items())
    result += ["-DsuppressionFiles=" + str(config["suppression"]), "-DdataDirectory=" + str(config["data"]), GOAL]
    return result


def report_coverage(root: Path, standalone: str | None = None) -> dict:
    reports = []
    for name, pom in projects_for(root, standalone).items():
        report = pom.parent / "target/dependency-check-report.json"
        item = {"module": name, "present": report.is_file()}
        if report.is_file():
            try:
                value = json.loads(report.read_text(encoding="utf-8"))
                dependencies = value["dependencies"]
                if not isinstance(dependencies, list):
                    raise ValueError("Unexpected report dependencies shape")
                item.update(valid=True, sha256=digest(report), dependencies=len(dependencies))
            except (OSError, ValueError, KeyError):
                item["valid"] = False
        reports.append(item)
    return {"complete": all(item.get("valid") for item in reports), "reports": reports}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent))
    parser.add_argument("--maven", default="mvn.cmd" if os.name == "nt" else "mvn")
    parser.add_argument("--repository")
    parser.add_argument("--settings")
    parser.add_argument("--suppression-file")
    parser.add_argument("--data-directory")
    parser.add_argument("--standalone-project", choices=("keycloak",), help="Audit the fixed Java 21 issuer project separately")
    parser.add_argument("--verify-effective", help="Validate an actual Maven effective reactor without running an audit")
    parser.add_argument("--plan", action="store_true", help="Print the nonsecret command without invoking Maven/analyzers")
    args = parser.parse_args(argv)
    try:
        root = Path(args.root).resolve(strict=True)
        if args.verify_effective:
            print(json.dumps(verify_policy(root, Path(args.verify_effective), args.standalone_project), indent=2))
            return 0
        if not all((args.settings, args.suppression_file, args.data_directory)):
            raise PolicyError("Settings, suppression file and public data directory are required")
        config = configuration(args, dict(os.environ), require_token=not args.plan)
        selected = config["projects"]
        invocations = [command(args, config, name) for name in selected]
        if args.plan:
            print(json.dumps({"mode": "PLAN_ONLY", "commands": invocations, "modules": list(selected),
                              "providerCalls": False}, indent=2))
            return 0
        # This is an intentional direct goal, so validate credentials and effective policy here.
        # The backend's lifecycle audit remains a separate AND condition in the same CI job.
        target = (next(iter(selected.values())).parent if args.standalone_project else root) / "target"
        target.mkdir(exist_ok=True)
        effective = target / "audit-effective-pom.xml"
        preflight = [args.maven, "-B", "-ntp", "-s", str(config["settings"]), "-f", str(next(iter(selected.values())))]
        if args.standalone_project:
            preflight.append("-N")
        if args.repository:
            preflight.append("-Dmaven.repo.local=" + str(Path(args.repository).resolve()))
        preflight += [HELP_GOAL, "-Doutput=" + str(effective)]
        status = subprocess.run(preflight, cwd=root, check=False).returncode
        if status:
            return status
        policy_result = verify_policy(root, effective, args.standalone_project)
        (target / "audit-policy-verification.json").write_text(json.dumps(policy_result, indent=2) + "\n", encoding="utf-8")
        # Audit each installed project nonrecursively after the one complete model preflight.
        # A finding in one plugin project must not prevent analysis of the others.
        # Clear ONLY this invocation's known report files so stale output cannot satisfy coverage.
        for pom in selected.values():
            for name in ("dependency-check-report.json", "dependency-check-report.html"):
                report = pom.parent / "target" / name
                if report.is_file():
                    report.unlink()
        exits = []
        for module, invocation in zip(selected, invocations):
            exits.append({"module": module, "exitCode": subprocess.run(invocation, cwd=root, check=False).returncode})
        status = next((item["exitCode"] for item in exits if item["exitCode"]), 0)
        coverage = report_coverage(root, args.standalone_project)
        coverage["mavenExitCode"] = status
        coverage["projectExits"] = exits
        (target / "audit-report-coverage.json").write_text(json.dumps(coverage, indent=2) + "\n", encoding="utf-8")
        return status or (0 if coverage["complete"] else 2)
    except (PolicyError, OSError, ET.ParseError, ValueError) as error:
        # Configuration errors contain paths/rule names only, never XML contents or env values.
        print("Build-support audit preflight failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
