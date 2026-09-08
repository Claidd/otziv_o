import argparse
import copy
import importlib.util
import io
import json
import re
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("build_support_audit", Path(__file__).with_name("audit.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
Q = "{" + audit.NS["m"] + "}"


class AuditPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="otziv-audit-policy-")
        self.base = Path(self.temporary.name)
        self.root = self.base / "reactor"
        self.root.mkdir()
        source = Path(__file__).parent
        shutil.copyfile(source / "plugin-policy.json", self.root / "plugin-policy.json")
        for name in (".", *audit.MODULES):
            (self.root / name).mkdir(exist_ok=True)
            shutil.copyfile(source / name / "pom.xml", self.root / name / "pom.xml")
        self.settings = self.base / "settings.xml"
        self.settings.write_text('<settings><servers><server><id>otziv-sonatype</id>'
                                 '<username>${env.SONATYPE_GUIDE_USERNAME}</username>'
                                 '<password>${env.SONATYPE_GUIDE_TOKEN}</password>'
                                 '</server></servers></settings>', encoding="utf-8")
        self.suppression = self.base / "suppression.xml"
        self.suppression.write_text('<suppressions/>', encoding="utf-8")
        self.args = argparse.Namespace(root=str(self.root), maven="mvn-fixture", repository=None,
            settings=str(self.settings), suppression_file=str(self.suppression),
            data_directory=str(self.base / "public-cache"))
        self.env = {"SONATYPE_GUIDE_TOKEN": "public-local-test-fixture", "SONATYPE_GUIDE_USERNAME": "token"}
        self.effective = self.root / "effective.xml"
        self.make_effective()

    def tearDown(self):
        self.temporary.cleanup()

    def make_effective(self):
        projects = ET.Element("projects")
        for name in (".", *audit.MODULES):
            project = audit.parse_xml(self.root / name / "pom.xml")
            if project.find(Q + "groupId") is None:
                ET.SubElement(project, Q + "groupId").text = audit.text(project.find(Q + "parent"), "groupId")
            managed = project.findall("m:build/m:pluginManagement/m:plugins/m:plugin", audit.NS)
            build = project.find(Q + "build")
            old = build.find(Q + "plugins")
            if old is not None:
                build.remove(old)
            active = ET.SubElement(build, Q + "plugins")
            for plugin in managed:
                active.append(copy.deepcopy(plugin))
            projects.append(project)
        ET.ElementTree(projects).write(self.effective, encoding="utf-8", xml_declaration=True)

    def change_effective(self, callback):
        tree = ET.parse(self.effective)
        callback(tree.getroot())
        tree.write(self.effective, encoding="utf-8", xml_declaration=True)

    def test_exact_finite_reactor_and_all_policy_pins_are_accepted(self):
        result = audit.verify_policy(self.root, self.effective)
        self.assertEqual(result["result"], "PASS")
        self.assertEqual(len(result["projects"]), 4)

    def test_missing_module_in_effective_model_fails(self):
        self.change_effective(lambda root: root.remove(root[-1]))
        with self.assertRaises(audit.PolicyError): audit.verify_policy(self.root, self.effective)

    def test_modified_declared_reactor_cannot_narrow_audit(self):
        pom = self.root / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace('<module>test-transport</module>', ''), encoding="utf-8")
        with self.assertRaises(audit.PolicyError): audit.reactor(self.root)

    def test_changed_effective_version_cannot_hide_behind_correct_management(self):
        self.change_effective(lambda root: setattr(root[0].find("m:build/m:plugins/m:plugin/m:version", audit.NS), "text", "0.0.1"))
        with self.assertRaisesRegex(audit.PolicyError, "version drift"): audit.verify_policy(self.root, self.effective)

    def test_new_unreviewed_active_plugin_fails(self):
        def add(root):
            plugin = ET.SubElement(root[0].find("m:build/m:plugins", audit.NS), Q + "plugin")
            ET.SubElement(plugin, Q + "artifactId").text = "unreviewed-fixture-plugin"
            ET.SubElement(plugin, Q + "version").text = "1"
        self.change_effective(add)
        with self.assertRaisesRegex(audit.PolicyError, "Unreviewed"): audit.verify_policy(self.root, self.effective)

    def test_fixed_plugin_dependency_override_is_required_in_actual_realm(self):
        def remove(root):
            for plugin in root[0].findall("m:build/m:plugins/m:plugin", audit.NS):
                if audit.text(plugin, "artifactId") == "maven-resources-plugin":
                    plugin.remove(plugin.find(Q + "dependencies"))
        self.change_effective(remove)
        with self.assertRaisesRegex(audit.PolicyError, "dependency override drift"): audit.verify_policy(self.root, self.effective)

    def test_missing_jtidy_replacement_exclusion_is_rejected_in_managed_or_active_model(self):
        for branch in ("m:build/m:pluginManagement/m:plugins/m:plugin", "m:build/m:plugins/m:plugin"):
            with self.subTest(branch=branch):
                self.make_effective()
                def remove(root):
                    for plugin in root[0].findall(branch, audit.NS):
                        if audit.text(plugin, "artifactId") == "maven-plugin-plugin":
                            for dependency in plugin.findall("m:dependencies/m:dependency", audit.NS):
                                if audit.text(dependency, "artifactId") == "maven-plugin-tools-generators":
                                    dependency.remove(dependency.find(Q + "exclusions"))
                self.change_effective(remove)
                with self.assertRaisesRegex(audit.PolicyError, "exclusion drift"):
                    audit.verify_policy(self.root, self.effective)

    def test_widened_jtidy_replacement_exclusion_is_rejected(self):
        def widen(root):
            for plugin in root[0].findall("m:build/m:plugins/m:plugin", audit.NS):
                if audit.text(plugin, "artifactId") == "maven-plugin-plugin":
                    for dependency in plugin.findall("m:dependencies/m:dependency", audit.NS):
                        if audit.text(dependency, "artifactId") == "maven-plugin-tools-generators":
                            dependency.find("m:exclusions/m:exclusion/m:artifactId", audit.NS).text = "*"
        self.change_effective(widen)
        with self.assertRaisesRegex(audit.PolicyError, "exclusion drift"):
            audit.verify_policy(self.root, self.effective)

    def test_jtidy_replacement_cannot_be_removed_while_excluding_original(self):
        def remove(root):
            for plugin in root[0].findall("m:build/m:plugins/m:plugin", audit.NS):
                if audit.text(plugin, "artifactId") == "maven-plugin-plugin":
                    dependencies = plugin.find(Q + "dependencies")
                    for dependency in list(dependencies):
                        if audit.text(dependency, "groupId") == "com.github.jtidy": dependencies.remove(dependency)
        self.change_effective(remove)
        with self.assertRaisesRegex(audit.PolicyError, "dependency override drift"):
            audit.verify_policy(self.root, self.effective)

    def test_missing_token_rejects_before_any_maven_or_analyzer_invocation(self):
        with self.assertRaisesRegex(audit.PolicyError, "TOKEN is required"):
            audit.configuration(self.args, {}, require_token=True)

    def test_whitespace_token_rejects_without_echoing_value(self):
        env = {**self.env, "SONATYPE_GUIDE_TOKEN": "private value fixture"}
        with self.assertRaises(audit.PolicyError) as caught: audit.configuration(self.args, env)
        self.assertNotIn(env["SONATYPE_GUIDE_TOKEN"], str(caught.exception))

    def test_credentials_never_enter_command(self):
        invocation = audit.command(self.args, audit.configuration(self.args, self.env))
        self.assertNotIn(self.env["SONATYPE_GUIDE_TOKEN"], " ".join(invocation))
        self.assertNotIn("verify", invocation)
        self.assertFalse(any(x.startswith("-P") for x in invocation))
        self.assertEqual(invocation.count(audit.GOAL), 1)
        for name, value in audit.PROPERTIES.items(): self.assertIn("-D" + name + "=" + value, invocation)
        self.assertIn("-N", invocation)

    def test_public_cache_cannot_include_settings(self):
        self.args.data_directory = str(self.base)
        with self.assertRaisesRegex(audit.PolicyError, "outside the public analyzer cache"):
            audit.configuration(self.args, self.env)

    def test_literal_settings_password_is_not_accepted(self):
        self.settings.write_text(self.settings.read_text().replace('${env.SONATYPE_GUIDE_TOKEN}', 'public-fixture-literal'))
        with self.assertRaisesRegex(audit.PolicyError, "environment-only password"):
            audit.configuration(self.args, self.env)

    def test_pinned_setup_java_namespaced_settings_format_is_accepted(self):
        content = self.settings.read_text(encoding="utf-8")
        self.settings.write_text(content.replace('<settings>', '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">'), encoding="utf-8")
        self.assertEqual(audit.configuration(self.args, self.env)["settings"], self.settings)

    def test_setup_java_default_arguments_fail_before_any_auditor_invocation(self):
        argv = ["--root", str(self.root), "--settings", str(self.settings),
                "--suppression-file", str(self.suppression), "--data-directory", self.args.data_directory]
        with patch.dict(audit.os.environ, {**self.env, "MAVEN_ARGS": "-ntp"}, clear=True), \
                patch.object(audit.subprocess, "run") as run, patch("sys.stderr", new_callable=io.StringIO) as errors:
            self.assertEqual(audit.main(argv), 2)
            self.assertIn("MAVEN_ARGS must be empty", errors.getvalue())
            run.assert_not_called()

    def test_explicit_progress_option_does_not_require_implicit_maven_arguments(self):
        config = audit.configuration(self.args, self.env)
        for module in config["projects"]:
            with self.subTest(module=module):
                self.assertIn("-ntp", audit.command(self.args, config, module))

    def test_hidden_maven_arguments_cannot_limit_selected_projects_or_skip_scopes(self):
        with self.assertRaises(audit.PolicyError):
            audit.configuration(self.args, {**self.env, "MAVEN_ARGS": "-pl test-transport -DskipTestScope=true"})

    def test_plan_is_read_only_and_does_not_require_a_real_token(self):
        argv = ["--root", str(self.root), "--settings", str(self.settings), "--suppression-file", str(self.suppression),
                "--data-directory", self.args.data_directory, "--plan"]
        with patch.dict(audit.os.environ, {}, clear=True), patch.object(audit.subprocess, "run") as run, patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(audit.main(argv), 0)
            run.assert_not_called()

    def test_actual_audit_exit_code_is_propagated_after_effective_policy_preflight(self):
        target = self.root / "target"
        target.mkdir()
        shutil.copyfile(self.effective, target / "audit-effective-pom.xml")
        argv = ["--root", str(self.root), "--maven", "mvn-fixture", "--settings", str(self.settings),
                "--suppression-file", str(self.suppression), "--data-directory", self.args.data_directory]
        def completed(command, **_kwargs):
            if audit.GOAL not in command:
                return argparse.Namespace(returncode=0)
            pom = Path(command[command.index("-f") + 1])
            (pom.parent / "target").mkdir(exist_ok=True)
            (pom.parent / "target/dependency-check-report.json").write_text('{"dependencies":[]}', encoding="utf-8")
            return argparse.Namespace(returncode=23 if pom.parent.name == "site-plugin" else 0)
        with patch.dict(audit.os.environ, self.env, clear=True), patch.object(audit.subprocess, "run", side_effect=completed) as run:
            self.assertEqual(audit.main(argv), 23)
            self.assertEqual(run.call_count, 5)
            self.assertIn(audit.HELP_GOAL, run.call_args_list[0].args[0])
            self.assertIn(audit.GOAL, run.call_args_list[1].args[0])
            self.assertEqual([call.args[0][call.args[0].index("-f") + 1] for call in run.call_args_list[1:]],
                [str(self.root / module / "pom.xml") for module in (".", *audit.MODULES)])
            for call in run.call_args_list[1:]:
                self.assertIn("-N", call.args[0])
                for name, value in audit.PROPERTIES.items(): self.assertIn("-D" + name + "=" + value, call.args[0])
            coverage = json.loads((target / "audit-report-coverage.json").read_text())
            self.assertTrue(coverage["complete"])
            self.assertEqual([entry["exitCode"] for entry in coverage["projectExits"]], [0, 0, 23, 0])

    def test_report_coverage_requires_every_module_and_valid_shape(self):
        for pom in audit.reactor(self.root).values():
            (pom.parent / "target").mkdir(exist_ok=True)
            (pom.parent / "target/dependency-check-report.json").write_text('{"dependencies":[]}', encoding="utf-8")
        self.assertTrue(audit.report_coverage(self.root)["complete"])
        report = self.root / "site-plugin/target/dependency-check-report.json"
        report.write_text('{"dependencies":null}', encoding="utf-8")
        self.assertFalse(audit.report_coverage(self.root)["complete"])
        report.unlink()
        self.assertFalse(audit.report_coverage(self.root)["complete"])

    def test_zero_exit_cannot_reuse_stale_reports_as_current_complete_audit(self):
        for pom in audit.reactor(self.root).values():
            (pom.parent / "target").mkdir(exist_ok=True)
            (pom.parent / "target/dependency-check-report.json").write_text('{"dependencies":[]}', encoding="utf-8")
        shutil.copyfile(self.effective, self.root / "target/audit-effective-pom.xml")
        argv = ["--root", str(self.root), "--maven", "mvn-fixture", "--settings", str(self.settings),
                "--suppression-file", str(self.suppression), "--data-directory", self.args.data_directory]
        with patch.dict(audit.os.environ, self.env, clear=True), patch.object(audit.subprocess, "run", return_value=argparse.Namespace(returncode=0)):
            self.assertEqual(audit.main(argv), 2)
        self.assertFalse(audit.report_coverage(self.root)["complete"])


    def standalone_fixture(self):
        repository = self.base / "standalone-repository"
        root = repository / "backend/build-support"
        root.mkdir(parents=True)
        shutil.copyfile(self.root / "plugin-policy.json", root / "plugin-policy.json")
        source = audit.parse_xml(self.root / "test-transport/pom.xml")
        source.find(Q + "groupId").text = "com.hunt.otziv"
        source.find(Q + "artifactId").text = "keycloak-security-generation"
        source.find(Q + "version").text = "1.0.0"
        props = source.find(Q + "properties")
        release = props.find(Q + "maven.compiler.release")
        if release is None:
            release = ET.SubElement(props, Q + "maven.compiler.release")
        release.text = "21"
        build = source.find(Q + "build")
        old = build.find(Q + "plugins")
        build.remove(old)
        plugins = ET.SubElement(build, Q + "plugins")
        for item in build.findall("m:pluginManagement/m:plugins/m:plugin", audit.NS):
            if audit.text(item, "artifactId") == "maven-compiler-plugin":
                plugins.append(copy.deepcopy(item))
        pom = repository / "infrastructure/keycloak/security-generation/pom.xml"
        pom.parent.mkdir(parents=True)
        ET.ElementTree(source).write(pom, encoding="utf-8", xml_declaration=True)
        return root, pom

    def test_missing_reporting_version_cannot_bypass_build_management(self):
        def add(root):
            reports = ET.SubElement(ET.SubElement(root[0], Q + "reporting"), Q + "plugins")
            plugin = ET.SubElement(reports, Q + "plugin")
            ET.SubElement(plugin, Q + "artifactId").text = "maven-site-plugin"
        self.change_effective(add)
        with self.assertRaisesRegex(audit.PolicyError, "unpinned reporting"):
            audit.verify_policy(self.root, self.effective)

    def test_unreviewed_extension_is_rejected(self):
        def add(root):
            extensions = ET.SubElement(root[0].find(Q + "build"), Q + "extensions")
            extension = ET.SubElement(extensions, Q + "extension")
            ET.SubElement(extension, Q + "groupId").text = "fixture"
            ET.SubElement(extension, Q + "artifactId").text = "unreviewed-extension"
            ET.SubElement(extension, Q + "version").text = "1"
        self.change_effective(add)
        with self.assertRaisesRegex(audit.PolicyError, "Unreviewed build extension"):
            audit.verify_policy(self.root, self.effective)

    def test_fixed_standalone_identity_and_java_release_accepted(self):
        root, pom = self.standalone_fixture()
        self.assertEqual(audit.projects_for(root, "keycloak"), {"keycloak": pom})
        self.assertEqual(len(audit.verify_policy(root, pom, "keycloak")["projects"]), 1)

    def test_standalone_cannot_target_arbitrary_path_or_wrong_identity(self):
        root, pom = self.standalone_fixture()
        with self.assertRaises(audit.PolicyError): audit.projects_for(root, "../../other")
        with self.assertRaises(audit.PolicyError): audit.projects_for(self.root, "keycloak")
        tree = ET.parse(pom)
        tree.getroot().find(Q + "artifactId").text = "another-project"
        tree.write(pom, encoding="utf-8", xml_declaration=True)
        with self.assertRaisesRegex(audit.PolicyError, "GAV"): audit.projects_for(root, "keycloak")

    def test_standalone_release_drift_rejected_before_subprocess(self):
        root, pom = self.standalone_fixture()
        tree = ET.parse(pom)
        tree.getroot().find("m:properties/m:maven.compiler.release", audit.NS).text = "17"
        tree.write(pom, encoding="utf-8", xml_declaration=True)
        with self.assertRaisesRegex(audit.PolicyError, "release 21"): audit.projects_for(root, "keycloak")

    def test_standalone_executes_one_full_audit_and_records_real_exit(self):
        root, pom = self.standalone_fixture()
        argv = ["--root", str(root), "--standalone-project", "keycloak", "--maven", "mvn-fixture",
                "--settings", str(self.settings), "--suppression-file", str(self.suppression),
                "--data-directory", self.args.data_directory]
        def completed(command, **_kwargs):
            if audit.HELP_GOAL in command:
                output = Path(next(x.split("=", 1)[1] for x in command if x.startswith("-Doutput=")))
                shutil.copyfile(pom, output)
                return argparse.Namespace(returncode=0)
            self.assertEqual(Path(command[command.index("-f") + 1]), pom)
            self.assertIn("-N", command)
            for name, value in audit.PROPERTIES.items():
                self.assertIn("-D" + name + "=" + value, command)
            (pom.parent / "target/dependency-check-report.json").write_text('{"dependencies":[]}', encoding="utf-8")
            return argparse.Namespace(returncode=19)
        with patch.dict(audit.os.environ, self.env, clear=True), patch.object(audit.subprocess, "run", side_effect=completed) as run:
            self.assertEqual(audit.main(argv), 19)
            self.assertEqual(run.call_count, 2)
        coverage = json.loads((pom.parent / "target/audit-report-coverage.json").read_text(encoding="utf-8"))
        self.assertTrue(coverage["complete"])
        self.assertEqual(coverage["projectExits"], [{"module": "keycloak", "exitCode": 19}])

    def test_standalone_zero_exit_without_fresh_report_is_incomplete(self):
        root, pom = self.standalone_fixture()
        target = pom.parent / "target"
        target.mkdir()
        (target / "dependency-check-report.json").write_text('{"dependencies":[]}', encoding="utf-8")
        shutil.copyfile(pom, target / "audit-effective-pom.xml")
        argv = ["--root", str(root), "--standalone-project", "keycloak", "--maven", "mvn-fixture",
                "--settings", str(self.settings), "--suppression-file", str(self.suppression),
                "--data-directory", self.args.data_directory]
        with patch.dict(audit.os.environ, self.env, clear=True), patch.object(audit.subprocess, "run", return_value=argparse.Namespace(returncode=0)):
            self.assertEqual(audit.main(argv), 2)
        self.assertFalse(audit.report_coverage(root, "keycloak")["complete"])


class AuditWorkflowCompatibilityTest(unittest.TestCase):
    def test_java_setup_does_not_inject_implicit_maven_arguments(self):
        # setup-java v6 defaults to exporting MAVEN_ARGS=-ntp. The audit must
        # retain its rejection of implicit arguments; Maven commands already
        # pass -ntp explicitly, so opt out at the action rather than clear env.
        workflows = Path(__file__).resolve().parents[2] / ".github" / "workflows"
        invocations = 0
        for workflow in workflows.glob("*.yml"):
            for block in re.split(r"(?=^      - )", workflow.read_text(encoding="utf-8"), flags=re.M):
                if "uses: actions/setup-java@" not in block:
                    continue
                invocations += 1
                with self.subTest(workflow=workflow.name, invocation=invocations):
                    self.assertRegex(block, r"(?m)^          show-download-progress: true\s*$")
        self.assertGreaterEqual(invocations, 2, "Both audit JDK selections must be checked")


if __name__ == "__main__":
    unittest.main()
