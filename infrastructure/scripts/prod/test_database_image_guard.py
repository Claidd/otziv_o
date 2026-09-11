import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("database_image_guard", HERE / "database_image_guard.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
MYSQL_ID, PG_ID, OTHER_ID = ["sha256:" + character * 64 for character in "abc"]


def fixture():
    result = {"config": {"name": "otziv-prod", "services": {}, "volumes": {}},
              "images": {}, "containers": {}, "volumes": {}, "queries": []}
    for index, (service, (name, source, destination)) in enumerate(guard.DATABASES.items()):
        volume = "docker_mysql_data" if service == "mysql" else "otziv-prod_keycloak_pg_data"
        image, image_id, container_id = service + ":reviewed", [MYSQL_ID, PG_ID][index], str(index + 1) * 64
        credential_variable = "MYSQL_PASSWORD" if service == "mysql" else "POSTGRES_PASSWORD"
        result["config"]["services"][service] = {"image": image, "container_name": name,
            "environment": {credential_variable: secrets.token_urlsafe()},
            "volumes": [{"type": "volume", "source": source, "target": destination}]}
        result["config"]["volumes"][source] = {"name": volume, "external": service == "mysql"}
        image_config = {"Entrypoint": ["docker-entrypoint.sh"], "Cmd": ["mysqld" if service == "mysql" else "postgres"],
                        "Env": ["PATH=/usr/local/bin:/usr/bin:/bin"], "WorkingDir": "", "User": ""}
        if service == "keycloak-postgres":
            image_config["Env"].append("PGDATA=" + destination)
        result["images"][image] = {"Id": image_id, "Config": image_config}
        result["volumes"][volume] = {"com.docker.compose.project": "otziv-prod", "com.docker.compose.volume": source}
        result["containers"][container_id] = {"Id": container_id, "Name": "/" + name,
            "Image": image_id, "State": {"Status": "running"}, "Config": dict(copy.deepcopy(image_config), Labels={
                "com.docker.compose.project": "otziv-prod", "com.docker.compose.service": service}), "HostConfig": {},
            "Mounts": [{"Type": "volume", "Name": volume, "Destination": destination, "RW": True}]}
    return result


def native_mysql_fixture(image_id=guard.REVIEWED_MYSQL_CONFIG):
    state = fixture()
    image_config = {"Entrypoint": ["/entrypoint.sh"], "Cmd": ["mysqld"],
                    "Env": ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                            "MYSQL_UNIX_PORT=/var/lib/mysql/mysql.sock"]}
    command = ["mysqld", "--user=999", "--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
               "--default-time-zone=+08:00", "--restrict-fk-on-non-standard-key=OFF", "--gtid-mode=OFF",
               "--enforce-gtid-consistency=OFF", "--log-bin=mysql-bin", "--binlog-format=ROW", "--event-scheduler=OFF"]
    runtime = "/var/run/mysqld:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0755"
    service = state["config"]["services"]["mysql"]
    service.update(image=guard.REVIEWED_MYSQL_REFERENCE, user="999:999", command=command, tmpfs=[runtime])
    service["volumes"][0]["volume"] = {"nocopy": True}
    state["images"][guard.REVIEWED_MYSQL_REFERENCE] = {"Id": image_id, "RepoDigests": [guard.REVIEWED_MYSQL_REFERENCE],
                                                    "Os": "linux", "Architecture": "amd64", "Config": image_config}
    container = state["containers"]["1" * 64]
    container["Image"] = image_id
    container["Config"] = dict(copy.deepcopy(image_config), Labels=container["Config"]["Labels"], User="999:999", Cmd=command.copy())
    container["HostConfig"] = {"Tmpfs": {runtime.split(":", 1)[0]: runtime.split(":", 1)[1]},
                               "Mounts": [{"Type": "volume", "Source": "docker_mysql_data", "Target": "/var/lib/mysql",
                                           "VolumeOptions": {"NoCopy": True}}]}
    container["Mounts"].append({"Type": "tmpfs", "Destination": "/var/run/mysqld", "RW": True})
    return state


class FixtureDocker:
    def __init__(self, state):
        self.state = state

    def __call__(self, *args):
        self.state["queries"].append(list(args))
        if list(args[:2]) == self.state.get("failQuery"):
            raise guard.GuardError("Injected metadata failure")
        if args[:2] == ("image", "inspect"):
            if args[2] not in self.state["images"]:
                raise guard.GuardError("Docker metadata query failed")
            return json.dumps([self.state["images"][args[2]]])
        if args[:2] == ("container", "inspect"):
            return json.dumps([self.state["containers"][args[2]]])
        filters = [args[index + 1] for index, value in enumerate(args) if value == "--filter"]
        if args[:2] == ("volume", "ls"):
            names = []
            for name, labels in self.state["volumes"].items():
                if all(labels.get(item[6:].split("=", 1)[0]) == item[6:].split("=", 1)[1] for item in filters):
                    names.append(json.dumps(name))
            return "\n".join(names)
        if args[:2] == ("container", "ls"):
            ids = []
            for cid, item in self.state["containers"].items():
                def matches(value):
                    if value.startswith("name=^/"):
                        return item["Name"] == value[6:-1]
                    key, expected = value[6:].split("=", 1)
                    return item["Config"]["Labels"].get(key) == expected
                if all(matches(value) for value in filters):
                    ids.append(cid)
            return "\n".join(ids)
        raise AssertionError("Unexpected Docker query: " + repr(args))


class ContinuityTests(unittest.TestCase):
    def setUp(self):
        self.state = fixture()

    def evaluate(self):
        return guard.checked_override(self.state["config"], FixtureDocker(self.state))

    def reject(self, message):
        with self.assertRaisesRegex(guard.GuardError, message):
            self.evaluate()

    def test_unchanged_real_images_emit_only_never_pull_without_recreating_config(self):
        self.assertEqual(self.evaluate(), {"services": {
            "mysql": {"pull_policy": "never"},
            "keycloak-postgres": {"pull_policy": "never"}}})

    def test_tag_alias_same_real_image_is_allowed_and_always_policy_is_replaced(self):
        self.state["images"]["mysql:latest"] = copy.deepcopy(self.state["images"]["mysql:reviewed"])
        self.state["config"]["services"]["mysql"].update(image="mysql:latest", pull_policy="always")
        self.assertEqual(self.evaluate()["services"]["mysql"], {"pull_policy": "never"})

    def test_changed_mysql_and_postgres_each_require_coordinated_activation(self):
        for service in guard.DATABASES:
            with self.subTest(service=service):
                self.state = fixture()
                self.state["images"][service + ":reviewed"]["Id"] = OTHER_ID
                self.reject("Database image change refused")

    def test_stopped_database_with_same_image_and_volume_can_resume(self):
        self.state["containers"]["2" * 64]["State"]["Status"] = "exited"
        self.assertEqual(self.evaluate()["services"]["keycloak-postgres"], {"pull_policy": "never"})

    def test_missing_container_with_existing_volume_is_not_fresh_installation(self):
        del self.state["containers"]["2" * 64]
        self.reject("existing data volume remains")

    def test_absent_container_and_absent_managed_local_volume_allow_fresh_database(self):
        del self.state["containers"]["2" * 64]
        del self.state["volumes"]["otziv-prod_keycloak_pg_data"]
        self.assertEqual(self.evaluate()["services"]["keycloak-postgres"], {"pull_policy": "never"})

    def test_both_fresh_managed_databases_are_permitted_without_existing_storage(self):
        self.state["containers"].clear()
        self.state["volumes"].clear()
        self.state["config"]["volumes"]["mysql_data"]["external"] = False
        self.assertEqual(len(self.evaluate()["services"]), 2)

    def test_fresh_external_or_driver_backed_storage_requires_provisioning(self):
        for change in [{"external": True}, {"driver": "nfs"}, {"driver_opts": {"device": "/existing/data"}}]:
            with self.subTest(change=change):
                self.state = fixture()
                del self.state["containers"]["2" * 64]
                del self.state["volumes"]["otziv-prod_keycloak_pg_data"]
                self.state["config"]["volumes"]["keycloak_pg_data"].update(change)
                self.reject("Fresh database requires")

    def test_renamed_old_labeled_volume_is_not_fresh(self):
        del self.state["containers"]["2" * 64]
        self.state["config"]["volumes"]["keycloak_pg_data"]["name"] = "new_empty_volume"
        self.reject("existing data volume remains")

    def test_missing_image_is_rejected_without_pull(self):
        del self.state["images"]["mysql:reviewed"]
        self.reject("metadata query failed")
        self.assertTrue(all(args[:2] in [["volume", "ls"], ["image", "inspect"]] for args in self.state["queries"]))

    def test_foreign_service_project_and_named_container_fail_closed(self):
        for label in ["com.docker.compose.service", "com.docker.compose.project"]:
            with self.subTest(label=label):
                self.state = fixture()
                self.state["containers"]["1" * 64]["Config"]["Labels"][label] = "other"
                self.reject("another project/service")

    def test_missing_service_wrong_project_and_wrong_name_are_rejected(self):
        self.state["config"]["name"] = "other"
        self.reject("otziv-prod")
        self.state = fixture()
        del self.state["config"]["services"]["mysql"]
        self.reject("Missing database service")
        self.state = fixture()
        self.state["config"]["services"]["mysql"]["container_name"] = "other"
        self.reject("Unexpected container identity")

    def test_ambiguous_containers_and_unstable_states_are_rejected(self):
        self.state["containers"]["3" * 64] = copy.deepcopy(self.state["containers"]["1" * 64])
        self.state["containers"]["3" * 64]["Name"] = "/another-mysql"
        self.reject("Ambiguous")
        self.state = fixture()
        self.state["containers"]["1" * 64]["State"]["Status"] = "removing"
        self.reject("not stable")

    def test_volume_remapping_read_only_and_missing_volume_are_rejected(self):
        for field, value in [("Name", "other"), ("RW", False), ("Type", "bind")]:
            with self.subTest(field=field):
                self.state = fixture()
                self.state["containers"]["1" * 64]["Mounts"][0][field] = value
                self.reject("volume identity changed")
        self.state = fixture()
        del self.state["volumes"]["docker_mysql_data"]
        self.reject("volume identity changed")

    def test_overlapping_data_bind_mount_cannot_hide_old_storage(self):
        self.state["config"]["services"]["mysql"]["volumes"].append({"type": "bind", "source": "/existing", "target": "/var/lib/mysql/data"})
        self.reject("storage mapping")

    def test_same_named_volume_with_subpath_cannot_select_another_database(self):
        for service in guard.DATABASES:
            with self.subTest(service=service):
                self.state = fixture()
                self.state["config"]["services"][service]["volumes"][0]["volume"] = {"subpath": "empty-directory"}
                self.reject("volume subpath")

    def test_existing_subpath_is_not_silently_switched_back_to_volume_root(self):
        for service, cid in [("mysql", "1" * 64), ("keycloak-postgres", "2" * 64)]:
            with self.subTest(service=service):
                self.state = fixture()
                self.state["containers"][cid]["HostConfig"]["Mounts"] = [{
                    "Type": "volume", "Target": guard.DATABASES[service][2], "VolumeOptions": {"Subpath": "old-database"}}]
                self.reject("Existing database volume subpath")

    def test_pgdata_override_and_existing_alternate_pgdata_fail_closed(self):
        for path in ["/var/lib/postgresql/data/new", "/different-volume", "relative", ""]:
            with self.subTest(path=path):
                self.state = fixture()
                self.state["config"]["services"]["keycloak-postgres"]["environment"]["PGDATA"] = path
                self.reject("effective data directory")
                self.state = fixture()
                self.state["containers"]["2" * 64]["Config"]["Env"][1] = "PGDATA=" + path
                self.reject("effective data directory")

    def test_exact_standard_pgdata_and_current_mysql_flags_preserve_normal_deploy(self):
        self.state["config"]["services"]["keycloak-postgres"]["environment"]["PGDATA"] = "/var/lib/postgresql/data"
        flags = ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci", "--default-time-zone=+08:00",
                 "--restrict-fk-on-non-standard-key=OFF", "--binlog-expire-logs-seconds=604800"]
        self.state["config"]["services"]["mysql"]["command"] = flags
        self.state["containers"]["1" * 64]["Config"]["Cmd"] = flags[:-1]
        self.state["config"]["services"]["mysql"]["environment"]["TZ"] = "Asia/Irkutsk"
        self.assertEqual(self.evaluate()["services"]["mysql"], {"pull_policy": "never"})
        self.state["config"]["services"]["mysql"]["command"] = ["mysqld", *flags]
        self.assertEqual(len(self.evaluate()["services"]), 2)

    def test_data_directory_options_defaults_files_and_unknown_commands_are_not_parsed_permissively(self):
        examples = {
            "mysql": [["--datadir=/var/lib/mysql/new"], ["mysqld", "--datadir", "/new"], ["mysqld", "-h/new"],
                      ["--data=/new"], ["--loose-datadir=/new"], ["--defaults-file=/backup/my.cnf"],
                      ["--defaults-extra-file=/backup/my.cnf"], ["--no-defaults"], ["mysqld", "--initialize"],
                      ["sh", "-c", "exec mysqld"], ["--binlog-expire-logs-seconds=1 --datadir=/new"],
                      ["--binlog-expire-logs-seconds=1", "--binlog-expire-logs-seconds=2"], []],
            "keycloak-postgres": [["postgres", "-D", "/var/lib/postgresql/data/new"], ["postgres", "-D/new"],
                                  ["postgres", "--pgdata=/new"], ["postgres", "-c", "data_directory=/new"],
                                  ["postgres", "-c", "config_file=/backup/postgresql.conf"], []],
        }
        for service, commands in examples.items():
            for command in commands:
                for existing in [False, True]:
                    with self.subTest(service=service, command=command, existing=existing):
                        self.state = fixture()
                        if existing:
                            cid = ("1" if service == "mysql" else "2") * 64
                            self.state["containers"][cid]["Config"]["Cmd"] = command
                        else:
                            self.state["config"]["services"][service]["command"] = command
                        self.reject("Database command")

    def test_shell_or_empty_entrypoint_override_and_preexisting_override_fail_closed(self):
        for entrypoint in [["/bin/sh", "-c", "exec mysqld --datadir=/new"], [], "", ["docker-entrypoint.sh"]]:
            with self.subTest(entrypoint=entrypoint):
                self.state = fixture()
                self.state["config"]["services"]["mysql"]["entrypoint"] = entrypoint
                self.reject("entrypoint override")
        self.state = fixture()
        self.state["containers"]["1" * 64]["Config"]["Entrypoint"] = ["/custom-entrypoint.sh"]
        self.reject("Existing database entrypoint")

    def test_environment_default_file_hooks_and_extra_config_mounts_need_coordination(self):
        for service, key in [("mysql", "MYSQL_HOME"), ("mysql", "HOME"), ("mysql", "PATH"),
                             ("mysql", "BASH_ENV"), ("keycloak-postgres", "PGDATA_FILE")]:
            with self.subTest(service=service, key=key):
                self.state = fixture()
                self.state["config"]["services"][service]["environment"][key] = "/alternate"
                self.reject("environment override")
        for target in ["/etc/my.cnf", "/etc/mysql/conf.d", "/usr/local/bin/docker-entrypoint.sh", "/docker-entrypoint-initdb.d"]:
            with self.subTest(target=target):
                self.state = fixture()
                self.state["config"]["services"]["mysql"]["volumes"].append({"type": "bind", "source": "/new", "target": target})
                self.reject("extra mount")
        for field in ["configs", "secrets", "tmpfs", "volumes_from", "pre_start", "post_start"]:
            with self.subTest(field=field):
                self.state = fixture()
                self.state["config"]["services"]["mysql"][field] = ["unreviewed"]
                self.reject("extra storage/launch")

    def test_missing_or_ambiguous_launch_metadata_does_not_assume_a_safe_default(self):
        for field in ["Entrypoint", "Cmd", "Env"]:
            with self.subTest(field=field):
                self.state = fixture()
                del self.state["images"]["mysql:reviewed"]["Config"][field]
                self.reject("metadata|command")
        self.state = fixture()
        self.state["containers"]["2" * 64]["Config"]["Env"] = ["PATH=/usr/local/bin:/usr/bin:/bin"]
        self.reject("missing image defaults")
        self.state = fixture()
        self.state["containers"]["2" * 64]["Config"]["Env"].append("PGDATA=/new")
        self.reject("Ambiguous database environment")

    def test_current_backup_and_csv_mounts_remain_compatible(self):
        for target in ["/backup", "/var/lib/mysql-files"]:
            self.state["config"]["services"]["mysql"]["volumes"].append({"type": "bind", "source": "/data" + target, "target": target})
            self.state["containers"]["1" * 64]["Mounts"].append({"Type": "bind", "Source": "/data" + target, "Destination": target, "RW": True})
        self.assertEqual(len(self.evaluate()["services"]), 2)

    def test_already_activated_reviewed_mysql_native_contract_preserves_ordinary_self_heal(self):
        for image_id in [guard.REVIEWED_MYSQL_CONFIG, guard.REVIEWED_MYSQL_INDEX]:
            for status in ["running", "exited"]:
                for events in ["OFF", "ON"]:
                    with self.subTest(image_id=image_id, status=status, events=events):
                        self.state = native_mysql_fixture(image_id)
                        container = self.state["containers"]["1" * 64]
                        container["State"]["Status"] = status
                        self.state["config"]["services"]["mysql"]["command"][-1] = "--event-scheduler=" + events
                        container["Config"]["Cmd"][-1] = "--event-scheduler=" + events
                        self.assertEqual(self.evaluate(), {"services": {"mysql": {"pull_policy": "never"},
                                                                      "keycloak-postgres": {"pull_policy": "never"}}})

    def test_native_mysql_identity_constants_bind_the_retained_published_oci_index_and_configuration(self):
        publication = HERE.parents[1] / "runtime-security/proofs/c7-published/mysql/publication"
        index = (publication / "registry-index.json").read_bytes()
        config = (publication / "registry-amd64-config.json").read_bytes()
        self.assertEqual("sha256:" + hashlib.sha256(index).hexdigest(), guard.REVIEWED_MYSQL_INDEX)
        self.assertEqual("sha256:" + hashlib.sha256(config).hexdigest(), guard.REVIEWED_MYSQL_CONFIG)
        self.assertEqual(json.loads((publication / "publication.json").read_bytes())["reference"], guard.REVIEWED_MYSQL_REFERENCE)
        self.assertEqual(json.loads(config)["config"]["Entrypoint"], ["/entrypoint.sh"])

    def test_candidate_native_contract_never_authorizes_opening_the_old_running_database(self):
        self.state = native_mysql_fixture()
        self.state["containers"]["1" * 64]["Image"] = MYSQL_ID
        self.reject("Database image change refused")

    def test_native_contract_is_bound_to_actual_immutable_image_and_platform_not_a_tag_or_label(self):
        self.state = native_mysql_fixture()
        image = self.state["images"][guard.REVIEWED_MYSQL_REFERENCE]
        image["Id"] = OTHER_ID
        self.state["containers"]["1" * 64]["Image"] = OTHER_ID
        self.reject("launch override|Database command")
        self.state = native_mysql_fixture(guard.REVIEWED_MYSQL_INDEX)
        self.state["images"][guard.REVIEWED_MYSQL_REFERENCE]["RepoDigests"] = []
        self.reject("immutable index binding")
        self.state = native_mysql_fixture()
        self.state["images"][guard.REVIEWED_MYSQL_REFERENCE]["Architecture"] = "arm64"
        self.reject("platform changed")

    def test_native_activation_missing_container_never_becomes_ordinary_fresh_provisioning(self):
        self.state = native_mysql_fixture()
        del self.state["containers"]["1" * 64]
        self.reject("existing data volume remains")
        del self.state["volumes"]["docker_mysql_data"]
        self.state["config"]["volumes"]["mysql_data"]["external"] = False
        self.reject("requires an existing coordinated activation")

    def test_native_wrong_uid_entrypoint_or_socket_defaults_fail_closed(self):
        for user in ["0", "0:0", "999", "999:1000", "mysql", None]:
            with self.subTest(user=user):
                self.state = native_mysql_fixture()
                self.state["config"]["services"]["mysql"]["user"] = user
                self.reject("native UID/GID")
                self.state = native_mysql_fixture()
                self.state["containers"]["1" * 64]["Config"]["User"] = user
                self.reject("native UID/GID")
        self.state = native_mysql_fixture()
        self.state["config"]["services"]["mysql"]["entrypoint"] = ["/entrypoint.sh"]
        self.reject("entrypoint override")
        self.state = native_mysql_fixture()
        self.state["config"]["services"]["mysql"]["environment"]["MYSQL_UNIX_PORT"] = "/tmp/mysql.sock"
        self.reject("environment override")
        self.state = native_mysql_fixture()
        self.state["images"][guard.REVIEWED_MYSQL_REFERENCE]["Config"]["Env"][1] = "MYSQL_UNIX_PORT=/tmp/mysql.sock"
        self.reject("native image launch metadata")

    def test_native_mysql_standalone_settings_and_storage_options_cannot_be_changed_or_omitted(self):
        changes = [lambda c: c.remove("--gtid-mode=OFF"), lambda c: c.append("--socket=/tmp/mysql.sock"),
                   lambda c: c.append("--datadir=/var/lib/mysql/new"), lambda c: c.append("--defaults-file=/backup/my.cnf"),
                   lambda c: c.append("--enforce-gtid-consistency=ON"), lambda c: c.append("--log-bin=/backup/log"),
                   lambda c: c.remove("--user=999"), lambda c: c.append("--event-scheduler=DISABLED")]
        for change in changes:
            for existing in [False, True]:
                with self.subTest(change=change, existing=existing):
                    self.state = native_mysql_fixture()
                    command = self.state["containers"]["1" * 64]["Config"]["Cmd"] if existing else self.state["config"]["services"]["mysql"]["command"]
                    change(command)
                    self.reject("native command")

    def test_native_event_scheduler_accepts_no_implicit_or_unreviewed_policy(self):
        for value in ["DISABLED", "1", "on", "", "ON --datadir=/new"]:
            for existing in [False, True]:
                with self.subTest(value=value, existing=existing):
                    self.state = native_mysql_fixture()
                    command = self.state["containers"]["1" * 64]["Config"]["Cmd"] if existing else self.state["config"]["services"]["mysql"]["command"]
                    command[-1] = "--event-scheduler=" + value
                    self.reject("native event scheduler")

    def test_native_tmpfs_must_be_the_precise_reviewed_runtime_mount_and_never_hide_data_or_config(self):
        alternatives = ["/var/lib/mysql:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0755",
                        "/etc:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0755",
                        "/var/run/mysqld:rw,noexec,nosuid,size=16m,uid=0,gid=0,mode=0755",
                        "/var/run/mysqld:rw,size=16m,uid=999,gid=999,mode=0777"]
        for alternative in alternatives:
            with self.subTest(alternative=alternative):
                self.state = native_mysql_fixture()
                self.state["config"]["services"]["mysql"]["tmpfs"] = [alternative]
                self.reject("runtime tmpfs")
                self.state = native_mysql_fixture()
                target, options = alternative.split(":", 1)
                self.state["containers"]["1" * 64]["HostConfig"]["Tmpfs"] = {target: options}
                self.reject("runtime tmpfs")
        self.state = native_mysql_fixture()
        self.state["containers"]["1" * 64]["Mounts"][-1]["Type"] = "bind"
        self.reject("extra mount")

    def test_native_config_mount_subpath_and_copyup_changes_require_coordination(self):
        for change, expected in [
            (lambda s: s["config"]["services"]["mysql"]["volumes"].append({"type": "bind", "source": "/changed", "target": "/etc/my.cnf"}), "extra mount"),
            (lambda s: s["config"]["services"]["mysql"]["volumes"][0]["volume"].update(subpath="old"), "volume subpath"),
            (lambda s: s["config"]["services"]["mysql"]["volumes"][0]["volume"].update(nocopy=False), "requires nocopy"),
            (lambda s: s["containers"]["1" * 64]["HostConfig"]["Mounts"][0]["VolumeOptions"].update(NoCopy=False), "requires nocopy"),
        ]:
            with self.subTest(change=change):
                self.state = native_mysql_fixture(); change(self.state); self.reject(expected)

    def test_actual_parent_or_child_data_mount_is_rejected(self):
        for target in ["/var/lib", "/var/lib/mysql/data"]:
            with self.subTest(target=target):
                self.state = fixture()
                self.state["containers"]["1" * 64]["Mounts"].append({"Type": "bind", "Source": "/existing", "Destination": target, "RW": True})
                self.reject("volume identity changed")

    def test_fresh_databases_cannot_share_a_new_volume(self):
        self.state["containers"].clear()
        self.state["volumes"].clear()
        self.state["config"]["volumes"]["mysql_data"]["external"] = False
        self.state["config"]["volumes"]["keycloak_pg_data"]["name"] = "docker_mysql_data"
        self.reject("must not share")

    def test_unresolved_digest_build_and_metadata_failure_paths(self):
        self.state["images"]["mysql:reviewed"]["Id"] = "mysql:latest"
        self.reject("immutable identity")
        self.state = fixture()
        self.state["config"]["services"]["mysql"]["build"] = {"context": "."}
        self.reject("builds/providers")
        for command in [["volume", "ls"], ["image", "inspect"], ["container", "ls"], ["container", "inspect"]]:
            with self.subTest(command=command):
                self.state = fixture()
                self.state["failQuery"] = command
                self.reject("Injected metadata failure")

    def test_docker_adapter_never_runs_mutations_and_redacts_error_body(self):
        with patch.object(guard.subprocess, "run") as run:
            for command in [("image", "pull"), ("volume", "rm"), ("container", "start")]:
                with self.assertRaisesRegex(guard.GuardError, "Unapproved"):
                    guard.DockerReadOnly()(*command, "x")
            run.assert_not_called()
            run.return_value = subprocess.CompletedProcess([], 1, "", "SECRET_VALUE")
            with self.assertRaisesRegex(guard.GuardError, "metadata query failed") as raised:
                guard.DockerReadOnly()("image", "inspect", "mysql:reviewed")
            self.assertNotIn("SECRET_VALUE", str(raised.exception))

    def test_cli_rejects_bad_json_without_partial_override(self):
        with patch.object(sys, "argv", ["guard"]), patch.object(sys, "stdin", io.StringIO("{")), \
             patch.object(sys, "stdout", io.StringIO()) as out, patch.object(sys, "stderr", io.StringIO()):
            self.assertEqual(guard.main(), 1)
            self.assertEqual(out.getvalue(), "")


def shell_fixture_main():
    """Private process adapter used by the real Bash script integration tests."""
    state_path = Path(os.environ["DB_GUARD_FIXTURE"])
    state = json.loads(state_path.read_text(encoding="utf-8"))
    events = Path(os.environ["DB_GUARD_EVENTS"])
    mode, arguments = sys.argv[2], sys.argv[3:]
    if mode == "python3":
        require_path = Path(arguments[0]).resolve()
        if require_path.name != "database_image_guard.py" or require_path.read_bytes() != (HERE / "database_image_guard.py").read_bytes():
            raise RuntimeError("Unexpected Python script in shell fixture")
        with patch.object(guard, "DockerReadOnly", lambda: FixtureDocker(state)), \
             patch.object(sys, "argv", [str(require_path)]):
            return guard.main()
    if mode != "docker" or arguments[:1] != ["compose"]:
        raise RuntimeError("Unexpected shell fixture operation")
    if arguments[1:] == ["version"]:
        return 0
    if "config" in arguments:
        if state.get("composeConfigFails"):
            return 23
        print(json.dumps(state["config"]))
        return 0
    operation = next((value for value in arguments if value in {"up", "stop", "ps"}), None)
    if operation not in {"up", "stop", "ps"}:
        raise RuntimeError("Unexpected Compose operation")
    files = [Path(arguments[index + 1]) for index, value in enumerate(arguments) if value == "-f"]
    if len(files) != 2:
        raise RuntimeError("Compose startup omitted the database override")
    override = json.loads(files[-1].read_text(encoding="utf-8"))
    expected = {"services": {"mysql": {"pull_policy": "never"},
                             "keycloak-postgres": {"pull_policy": "never"}}}
    if override != expected:
        raise RuntimeError("Compose startup did not retain the checked database images without pulling")
    with events.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"operation": operation, "arguments": arguments, "override": override}) + "\n")
    if operation == "up" and state.get("upFailsOnce") and (not state.get("failOnlyApp") or arguments[-1] == "app"):
        state["upFailsOnce"] = False
        state_path.write_text(json.dumps(state), encoding="utf-8")
        print("removal already in progress", file=sys.stderr)
        return 1
    if operation == "up" and state.get("upAlwaysFails"):
        return 31
    return 0


class ShellWiringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bash = str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe") if os.name == "nt" else shutil.which("bash")
        if not cls.bash or not Path(cls.bash).exists():
            raise RuntimeError("Bash is required for actual deployment/self-heal guard fixtures")

    def execute(self, mode, change=None, external=False):
        with tempfile.TemporaryDirectory(prefix="otziv-db-guard-") as directory:
            folder = Path(directory)
            state = fixture()
            if change:
                change(state)
            state_path, events = folder / "fixture.json", folder / "events.jsonl"
            state_path.write_text(json.dumps(state), encoding="utf-8")
            (folder / "docker-compose.yaml").write_text("name: otziv-prod\n", encoding="utf-8")
            (folder / ".env").write_text("EXTERNAL_REVIEW_CHECK_ENABLED=" + str(external).lower() + "\n", encoding="utf-8")
            helper = folder / "infrastructure/scripts/prod/database_image_guard.py"
            helper.parent.mkdir(parents=True)
            helper.write_bytes((HERE / "database_image_guard.py").read_bytes())
            # The script path is injected by a shell function, never into production code.
            quote = lambda value: "'" + str(value).replace("'", "'\\''") + "'"
            fixture_python = str(Path(sys.executable)).replace("\\", "/")
            fixture_script = str(Path(__file__).resolve()).replace("\\", "/")
            shell_env = folder / "fixture-env.sh"
            shell_env.write_bytes(("docker() { " + quote(fixture_python) + " " + quote(fixture_script) + " --shell-fixture docker \"$@\"; }\n"
                                   "python3() { " + quote(fixture_python) + " " + quote(fixture_script) + " --shell-fixture python3 \"$@\"; }\n"
                                   "sleep() { :; }\n").encode("utf-8"))
            if mode == "self-heal":
                script = HERE / "otziv-prod-up.sh"
            else:
                source = (HERE / "deploy-prod.ps1").read_text(encoding="utf-8")
                def function(name):
                    start = source.index(name + "() {\n")
                    end = source.index("\n}\n", start) + 3
                    return source[start:end].replace("`$", "$")
                body = "set -Eeuo pipefail\nremote_path=\"$(pwd -P)\"\nenv_file=.env\ncompose_project_name=otziv-prod\ndatabase_guard_temp=''\ndatabase_image_override=''\n"
                body += "trap 'rm -f -- \"$database_guard_temp\"' EXIT\n"
                body += function("compose") + "\n" + function("guard_database_images") + "\n"
                body += function("recreate_service_with_retry") + "\nremove_service_containers() { :; }\n"
                body += "guard_database_images\ncompose up -d --no-deps mysql keycloak-postgres loki tempo\ncompose up -d --no-deps keycloak\nrecreate_service_with_retry app\ncompose --profile external-review up -d --no-deps external-review-worker\n"
                script = folder / "rollout-fixture.sh"
                script.write_bytes(body.encode("utf-8"))
            env = dict(os.environ, BASH_ENV=str(shell_env), DB_GUARD_FIXTURE=str(state_path), DB_GUARD_EVENTS=str(events))
            result = subprocess.run([self.bash, str(script).replace("\\", "/")], cwd=folder, env=env, capture_output=True, text=True, timeout=30)
            records = [json.loads(line) for line in events.read_text(encoding="utf-8").splitlines()] if events.exists() else []
            temps = list(folder.glob(".self-heal-db-images.*")) + list(folder.glob(".deploy-db-images.*"))
            self.assertEqual(temps, [], result.stderr)
            return result, records

    def test_rollout_all_later_up_and_profile_calls_retain_guard_override(self):
        result, events = self.execute("rollout")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([event["operation"] for event in events], ["up"] * 4)

    def test_retry_keeps_same_database_override(self):
        # Trigger the failure at the retry-owned application start, after DB startup.
        source = (HERE / "deploy-prod.ps1").read_text(encoding="utf-8")
        self.assertIn('"`${database_override_args[@]}" "`$@"', source)
        result, events = self.execute("rollout", lambda state: state.update(upFailsOnce=True, failOnlyApp=True))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(events), 5)

    def test_self_heal_default_and_external_review_preserve_full_up_behavior(self):
        for enabled in [False, True]:
            with self.subTest(enabled=enabled):
                result, events = self.execute("self-heal", external=enabled)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([event["operation"] for event in events], ["up"] if enabled else ["stop", "up"])

    def test_changed_database_and_failed_compose_metadata_prevent_any_startup(self):
        for mode in ["rollout", "self-heal"]:
            for change in [lambda state: state["images"]["mysql:reviewed"].update(Id=OTHER_ID),
                           lambda state: state.update(composeConfigFails=True)]:
                with self.subTest(mode=mode, change=change):
                    result, events = self.execute(mode, change)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(events, [])

    def test_storage_redirection_prevents_any_rollout_or_self_heal_startup(self):
        for mode in ["rollout", "self-heal"]:
            for change in [lambda s: s["config"]["services"]["mysql"]["volumes"][0].update(volume={"subpath": "empty"}),
                           lambda s: s["config"]["services"]["keycloak-postgres"]["environment"].update(PGDATA="/new"),
                           lambda s: s["config"]["services"]["mysql"].update(command=["--defaults-file=/backup/my.cnf"])]:
                with self.subTest(mode=mode, change=change):
                    result, events = self.execute(mode, change)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(events, [])

    def test_already_activated_native_mysql_can_restart_but_candidate_over_old_image_never_starts(self):
        for mode in ["rollout", "self-heal"]:
            with self.subTest(mode=mode):
                result, events = self.execute(mode, lambda state: state.update(native_mysql_fixture()))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue(any(event["operation"] == "up" for event in events))
                def candidate_over_old(state):
                    state.update(native_mysql_fixture())
                    state["containers"]["1" * 64]["Image"] = MYSQL_ID
                result, events = self.execute(mode, candidate_over_old)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(events, [])

    def test_self_heal_up_failure_keeps_failure_and_removes_owned_override(self):
        result, events = self.execute("self-heal", lambda state: state.update(upAlwaysFails=True))
        self.assertEqual(result.returncode, 31, result.stderr)
        self.assertEqual([event["operation"] for event in events], ["stop", "up"])

    def test_guard_is_wired_before_any_rollout_compose_start_and_is_bundled(self):
        source = (HERE / "deploy-prod.ps1").read_text(encoding="utf-8")
        guard_call = source.index("\nguard_database_images\n")
        main_start = source.index('backup_dir=".deploy-backups/')
        self.assertGreater(guard_call, main_start)
        for line in source[main_start:].splitlines():
            if line.startswith("compose ") and (" up " in line or " run " in line):
                self.assertLess(guard_call, source.index(line, main_start))
        self.assertIn('"infrastructure\\scripts\\prod\\database_image_guard.py"', source)


class ActualComposeConfigurationTests(unittest.TestCase):
    def test_pull_policy_guard_preserves_actual_compose_service_hash(self):
        # config is read-only: this does not pull an image or contact/create a DB.
        if not shutil.which("docker"):
            raise RuntimeError("Docker Compose is required for the real configuration-hash regression")
        with tempfile.TemporaryDirectory(prefix="otziv-db-compose-hash-") as directory:
            folder = Path(directory)
            state = fixture()
            state["config"]["services"]["mysql"]["pull_policy"] = "always"
            base, override, counterexample = folder / "base.json", folder / "guard.json", folder / "config-id.json"
            base.write_text(json.dumps(state["config"]), encoding="utf-8")
            override.write_text(json.dumps(guard.checked_override(state["config"], FixtureDocker(state))), encoding="utf-8")
            counterexample.write_text(json.dumps({"services": {"mysql": {"image": MYSQL_ID}}}), encoding="utf-8")
            command = ["docker", "compose", "-p", "otziv-prod", "-f", str(base)]
            def compose(extra, query):
                result = subprocess.run(command + extra + ["config", *query], capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr)
                return result.stdout
            baseline = compose([], ["--hash", "mysql,keycloak-postgres"])
            guarded = compose(["-f", str(override)], ["--hash", "mysql,keycloak-postgres"])
            unnecessary_recreate = compose(["-f", str(counterexample)], ["--hash", "mysql,keycloak-postgres"])
            self.assertEqual(baseline, guarded)
            self.assertNotEqual(baseline, unnecessary_recreate)
            resolved = json.loads(compose(["-f", str(override)], ["--format", "json"]))
            for service in guard.DATABASES:
                self.assertEqual(resolved["services"][service]["image"], state["config"]["services"][service]["image"])
                self.assertEqual(resolved["services"][service]["pull_policy"], "never")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--shell-fixture":
        raise SystemExit(shell_fixture_main())
    unittest.main()
