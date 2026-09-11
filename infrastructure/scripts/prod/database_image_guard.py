#!/usr/bin/env python3
"""Read-only DB continuity check; emit a private Compose pull-policy override."""
import json
import re
import subprocess
import sys


PROJECT = "otziv-prod"
DATABASES = {
    "mysql": ("my-mysql", "mysql_data", "/var/lib/mysql"),
    "keycloak-postgres": ("keycloak-postgres", "keycloak_pg_data", "/var/lib/postgresql/data"),
}
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}\Z")
CONTAINER_ID = re.compile(r"[0-9a-f]{64}\Z")
NAME = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*\Z")
# Ordinary deployment permits only the existing non-storage MySQL arguments.
# Do not try to blacklist every spelling/abbreviation of datadir/defaults-file.
MYSQL_OPTIONS = {
    "character-set-server": re.compile(r"utf8mb4\Z"),
    "collation-server": re.compile(r"utf8mb4_unicode_ci\Z"),
    "default-time-zone": re.compile(r"\+08:00\Z"),
    "restrict-fk-on-non-standard-key": re.compile(r"OFF\Z"),
    "binlog-expire-logs-seconds": re.compile(r"[0-9]{1,10}\Z"),
}
MUTABLE_ENVIRONMENT = {
    "mysql": {"MYSQL_ROOT_PASSWORD", "MYSQL_DATABASE", "MYSQL_USER", "MYSQL_PASSWORD", "TZ"},
    "keycloak-postgres": {"POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "TZ"},
}
EXTRA_MOUNTS = {"mysql": {"/backup", "/var/lib/mysql-files"}, "keycloak-postgres": set()}
# This immutable OCI index was reviewed with the exact configuration below.
# Classic Docker reports its config ID; containerd's image store reports the
# pinned index ID. A tag, version string or image label never grants this path.
REVIEWED_MYSQL_CONFIG = "sha256:80ee3b50147a329addbaf754abc4dce86dc06636624680d780351e2320a11070"
REVIEWED_MYSQL_INDEX = "sha256:3a3caaab4e71b3bfdec9da21c17c00ed10ca237151919aeddac8e5ce4b8b7baa"
REVIEWED_MYSQL_REFERENCE = "ghcr.io/claidd/otziv-security@" + REVIEWED_MYSQL_INDEX
NATIVE_MYSQL_OPTIONS = {
    "user": "999", "character-set-server": "utf8mb4", "collation-server": "utf8mb4_unicode_ci",
    "default-time-zone": "+08:00", "restrict-fk-on-non-standard-key": "OFF",
    "gtid-mode": "OFF", "enforce-gtid-consistency": "OFF", "log-bin": "mysql-bin", "binlog-format": "ROW",
}
NATIVE_MYSQL_RUNTIME = "/var/run/mysqld"
NATIVE_MYSQL_TMPFS = "rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0755"


class GuardError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise GuardError(message)


class DockerReadOnly:
    def __call__(self, *args):
        # This adapter deliberately exposes no pull, create, start or volume write.
        allowed = {("image", "inspect"), ("container", "ls"),
                   ("container", "inspect"), ("volume", "ls")}
        require(tuple(args[:2]) in allowed, "Unapproved Docker operation")
        try:
            result = subprocess.run(["docker", *args], capture_output=True, text=True,
                                    encoding="utf-8", timeout=30, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise GuardError("Docker metadata query could not complete") from exc
        require(result.returncode == 0, "Docker metadata query failed; no database activation is permitted")
        # Docker error bodies and container environment are never copied to logs.
        return result.stdout


def parsed(text):
    try:
        return json.loads(text)
    except (ValueError, TypeError) as exc:
        raise GuardError("Docker or Compose metadata is not valid JSON") from exc


def one_object(text):
    items = parsed(text)
    require(isinstance(items, list) and len(items) == 1 and isinstance(items[0], dict),
            "Docker inspection did not identify exactly one object")
    return items[0]


def listed_ids(text):
    ids = text.splitlines()
    require(all(CONTAINER_ID.fullmatch(value) for value in ids), "Invalid container inventory")
    require(len(ids) == len(set(ids)), "Duplicate container inventory")
    return set(ids)


def volume_names(text):
    names = [parsed(line) for line in text.splitlines()]
    require(all(isinstance(name, str) and NAME.fullmatch(name) for name in names), "Invalid volume inventory")
    require(len(names) == len(set(names)), "Duplicate volume inventory")
    return set(names)


def environment(values, service_name):
    require(isinstance(values, list) and all(isinstance(value, str) and "=" in value for value in values),
            f"Missing database environment metadata: {service_name}")
    result = {}
    for value in values:
        key, value = value.split("=", 1)
        require(key and key not in result, f"Ambiguous database environment metadata: {service_name}")
        result[key] = value
    return result


def checked_environment(values, defaults, service_name, data_path):
    require(isinstance(values, dict), f"Invalid resolved database environment: {service_name}")
    for key, value in values.items():
        require(isinstance(key, str) and isinstance(value, str), f"Unresolved database environment: {service_name}")
        if key in MUTABLE_ENVIRONMENT[service_name]:
            continue
        if service_name == "keycloak-postgres" and key == "PGDATA":
            require(value == data_path, f"Database effective data directory changed: {service_name}")
        else:
            require(key in defaults and value == defaults[key],
                    f"Database environment override needs a coordinated procedure: {service_name}")
    if service_name == "keycloak-postgres":
        require(values.get("PGDATA", defaults.get("PGDATA")) == data_path,
                f"Database effective data directory changed: {service_name}")


def checked_command(command, service_name, native_mysql=False):
    require(isinstance(command, list) and command and all(isinstance(arg, str) for arg in command),
            f"Database command needs a coordinated procedure: {service_name}")
    if service_name == "keycloak-postgres":
        require(command == ["postgres"], f"Database command needs a coordinated procedure: {service_name}")
        return
    if native_mysql:
        require(command[0] == "mysqld", "Reviewed MySQL native server command required")
        options = {}
        for arg in command[1:]:
            key, separator, value = arg.removeprefix("--").partition("=")
            require(arg.startswith("--") and separator and key not in options, "Reviewed MySQL native command requires exact options")
            options[key] = value
        expiration = options.pop("binlog-expire-logs-seconds", None)
        require(expiration is None or MYSQL_OPTIONS["binlog-expire-logs-seconds"].fullmatch(expiration),
                "Reviewed MySQL binlog retention requires an explicit numeric value")
        # Both explicit launch policies were checked on disposable native 9.7.3:
        # OFF for quiescent preparation, ON for the captured VPS steady state.
        # Runtime SET GLOBAL during a cutover does not change this launch value.
        require(options.pop("event-scheduler", None) in {"OFF", "ON"},
                "Reviewed MySQL native event scheduler requires explicit OFF or ON")
        require(options == NATIVE_MYSQL_OPTIONS, "Reviewed MySQL native command requires exact options")
        return
    options = command[1:] if command[0] == "mysqld" else command
    require(command[0] == "mysqld" or options[0].startswith("--"),
            f"Database command needs a coordinated procedure: {service_name}")
    seen = set()
    for arg in options:
        key, separator, value = arg.removeprefix("--").partition("=")
        require(arg.startswith("--") and separator and key in MYSQL_OPTIONS and key not in seen
                and MYSQL_OPTIONS[key].fullmatch(value),
                f"Database command needs a coordinated procedure: {service_name}")
        seen.add(key)


def checked_launch(service, image_config, service_name, data_path, actual=None, native_mysql=False):
    # Compare inspected default/actual metadata, without executing a command in
    # either image or database container and without logging environment values.
    require(isinstance(image_config, dict), f"Missing database image launch metadata: {service_name}")
    entrypoint = image_config.get("Entrypoint")
    require(isinstance(entrypoint, list) and entrypoint and all(isinstance(arg, str) for arg in entrypoint),
            f"Missing database image entrypoint metadata: {service_name}")
    defaults = environment(image_config.get("Env"), service_name)
    checked_command(image_config.get("Cmd"), service_name)
    checked_environment(defaults, defaults, service_name, data_path)
    if native_mysql:
        require(entrypoint == ["/entrypoint.sh"] and image_config.get("Cmd") == ["mysqld"]
                and not image_config.get("User") and not image_config.get("WorkingDir")
                and defaults.get("MYSQL_UNIX_PORT") == "/var/lib/mysql/mysql.sock",
                "Reviewed MySQL native image launch metadata changed")
        require(service.get("user") == "999:999", "Reviewed MySQL native UID/GID required")
    require(service.get("entrypoint") is None, f"Database entrypoint override needs a coordinated procedure: {service_name}")
    for key, image_key in [("working_dir", "WorkingDir"), ("user", "User")]:
        if native_mysql and key == "user":
            continue
        require(service.get(key) is None or service[key] == image_config.get(image_key, ""),
                f"Database launch override needs a coordinated procedure: {service_name}")
    checked_command(image_config.get("Cmd") if service.get("command") is None else service["command"], service_name, native_mysql)
    checked_environment(service.get("environment", {}), defaults, service_name, data_path)
    # Config/secret/alternate mounts can replace a defaults file or entrypoint
    # even when the data volume itself has retained its name and destination.
    for field in ["configs", "secrets", "volumes_from", "tmpfs", "pre_start", "post_start"]:
        if native_mysql and field == "tmpfs":
            require(service.get("tmpfs") == [NATIVE_MYSQL_RUNTIME + ":" + NATIVE_MYSQL_TMPFS],
                    "Reviewed MySQL native runtime tmpfs required")
            continue
        require(not service.get(field), f"Database extra storage/launch configuration needs a coordinated procedure: {service_name}")
    if actual is not None:
        require(isinstance(actual, dict) and actual.get("Entrypoint") == entrypoint,
                f"Existing database entrypoint requires a coordinated procedure: {service_name}")
        for key in ["User", "WorkingDir"]:
            if native_mysql and key == "User":
                require(actual.get("User") == "999:999", "Existing reviewed MySQL native UID/GID required")
                continue
            require(actual.get(key, "") == image_config.get(key, ""),
                    f"Existing database launch configuration requires a coordinated procedure: {service_name}")
        checked_command(actual.get("Cmd"), service_name, native_mysql)
        actual_environment = environment(actual.get("Env"), service_name)
        require(all(key in actual_environment for key in defaults if key not in MUTABLE_ENVIRONMENT[service_name]),
                f"Existing database environment is missing image defaults: {service_name}")
        checked_environment(actual_environment, defaults, service_name, data_path)


def checked_override(config, docker):
    require(isinstance(config, dict) and config.get("name") == PROJECT,
            "Database guard requires the resolved otziv-prod Compose project")
    services, volumes = config.get("services"), config.get("volumes")
    require(isinstance(services, dict) and isinstance(volumes, dict), "Resolved services/volumes are missing")
    existing_volumes = volume_names(docker("volume", "ls", "--format", "{{json .Name}}"))
    override = {"services": {}}
    selected_volumes = set()
    for service_name, (container_name, volume_key, data_path) in DATABASES.items():
        service = services.get(service_name)
        require(isinstance(service, dict), f"Missing database service: {service_name}")
        require(service.get("container_name") == container_name, f"Unexpected container identity: {service_name}")
        require(not service.get("build") and not service.get("provider"), f"Database builds/providers need a coordinated procedure: {service_name}")
        image = service.get("image")
        require(isinstance(image, str) and 0 < len(image) <= 512 and not image.startswith("-")
                and not any(character.isspace() for character in image), f"Missing or invalid image: {service_name}")
        target_image = one_object(docker("image", "inspect", image))
        target_id = target_image.get("Id", "")
        require(isinstance(target_id, str) and IMAGE_ID.fullmatch(target_id), f"Image has no immutable identity: {service_name}")
        native_mysql = service_name == "mysql" and target_id in {REVIEWED_MYSQL_CONFIG, REVIEWED_MYSQL_INDEX}
        if native_mysql:
            require(target_image.get("Os") == "linux" and target_image.get("Architecture") == "amd64",
                    "Reviewed MySQL platform changed")
            if target_id == REVIEWED_MYSQL_INDEX:
                require(REVIEWED_MYSQL_REFERENCE in target_image.get("RepoDigests", []), "Reviewed MySQL immutable index binding missing")
        checked_launch(service, target_image.get("Config"), service_name, data_path, native_mysql=native_mysql)
        mounts = service.get("volumes", [])
        require(isinstance(mounts, list) and all(isinstance(mount, dict) for mount in mounts), f"Invalid database mounts: {service_name}")
        data_mounts = [mount for mount in mounts if isinstance(mount.get("target"), str)
                       and (mount["target"] == data_path or mount["target"].startswith(data_path + "/")
                            or data_path.startswith(mount["target"].rstrip("/") + "/"))]
        require(len(data_mounts) == 1 and data_mounts[0].get("type") == "volume"
                and data_mounts[0].get("source") == volume_key and data_mounts[0].get("target") == data_path
                and not data_mounts[0].get("read_only"), f"Database storage mapping needs a coordinated procedure: {service_name}")
        options = data_mounts[0].get("volume", {})
        require(isinstance(options, dict) and not options.get("subpath"),
                f"Database volume subpath needs a coordinated procedure: {service_name}")
        if native_mysql:
            require(options.get("nocopy") is True, "Reviewed MySQL data volume requires nocopy")
        require(all(mount is data_mounts[0] or mount.get("target") in EXTRA_MOUNTS[service_name] for mount in mounts),
                f"Database extra mount needs a coordinated procedure: {service_name}")
        volume = volumes.get(volume_key)
        require(isinstance(volume, dict), f"Missing resolved database volume: {service_name}")
        volume_name = volume.get("name")
        require(isinstance(volume_name, str) and NAME.fullmatch(volume_name), f"Database volume has no resolved identity: {service_name}")
        require(volume_name not in selected_volumes, "Database services must not share their data volume")
        selected_volumes.add(volume_name)
        labels = ["--filter", f"label=com.docker.compose.project={PROJECT}",
                  "--filter", f"label=com.docker.compose.service={service_name}"]
        ids = listed_ids(docker("container", "ls", "--all", "--quiet", "--no-trunc", *labels))
        ids |= listed_ids(docker("container", "ls", "--all", "--quiet", "--no-trunc",
                                "--filter", f"name=^/{container_name}$"))
        require(len(ids) <= 1, f"Ambiguous database containers: {service_name}")
        if ids:
            container_id = next(iter(ids))
            container = one_object(docker("container", "inspect", container_id))
            require(container.get("Id") == container_id and container.get("Name") == "/" + container_name,
                    f"Container identity changed during inspection: {service_name}")
            labels = container.get("Config", {}).get("Labels", {})
            require(isinstance(labels, dict) and labels.get("com.docker.compose.project") == PROJECT
                    and labels.get("com.docker.compose.service") == service_name
                    and str(labels.get("com.docker.compose.oneoff", "false")).lower() == "false",
                    f"Database container belongs to another project/service: {service_name}")
            require(container.get("State", {}).get("Status") in {"running", "exited", "created", "paused"},
                    f"Database container state is not stable: {service_name}")
            require(container.get("Image") == target_id,
                    f"Database image change refused for {service_name}; use a separately reviewed coordinated database activation procedure")
            checked_launch(service, target_image.get("Config"), service_name, data_path, container.get("Config"), native_mysql)
            actual = container.get("Mounts", [])
            require(isinstance(actual, list) and all(isinstance(mount, dict) for mount in actual), "Invalid container mount metadata")
            data = [mount for mount in actual if isinstance(mount.get("Destination"), str)
                    and (mount["Destination"] == data_path or mount["Destination"].startswith(data_path + "/")
                         or data_path.startswith(mount["Destination"].rstrip("/") + "/"))]
            require(volume_name in existing_volumes and len(data) == 1 and data[0].get("Type") == "volume"
                    and data[0].get("Name") == volume_name and data[0].get("Destination") == data_path
                    and data[0].get("RW") is True,
                    f"Database data volume identity changed: {service_name}")
            require(not data[0].get("SubPath"), f"Existing database volume subpath requires a coordinated procedure: {service_name}")
            host_config = container.get("HostConfig")
            require(isinstance(host_config, dict), f"Missing database mount metadata: {service_name}")
            if native_mysql:
                require(host_config.get("Tmpfs") == {NATIVE_MYSQL_RUNTIME: NATIVE_MYSQL_TMPFS},
                        "Existing reviewed MySQL runtime tmpfs changed")
            host_mounts = host_config.get("Mounts", [])
            require(isinstance(host_mounts, list) and all(isinstance(mount, dict) for mount in host_mounts),
                    f"Invalid database host mount metadata: {service_name}")
            for mount in host_mounts:
                if mount.get("Target") == data_path:
                    options = mount.get("VolumeOptions", {})
                    require(isinstance(options, dict) and not options.get("Subpath"),
                            f"Existing database volume subpath requires a coordinated procedure: {service_name}")
            if native_mysql:
                bindings = [mount for mount in host_mounts if mount.get("Target") == data_path]
                require(len(bindings) == 1 and bindings[0].get("VolumeOptions", {}).get("NoCopy") is True,
                        "Existing reviewed MySQL data volume requires nocopy")
            require(all(mount is data[0] or mount.get("Destination") in EXTRA_MOUNTS[service_name]
                        or (native_mysql and mount.get("Destination") == NATIVE_MYSQL_RUNTIME and mount.get("Type") == "tmpfs") for mount in actual),
                    f"Existing database extra mount requires a coordinated procedure: {service_name}")
        else:
            prior = volume_names(docker("volume", "ls", "--format", "{{json .Name}}",
                                        "--filter", f"label=com.docker.compose.project={PROJECT}",
                                        "--filter", f"label=com.docker.compose.volume={volume_key}"))
            require(volume_name not in existing_volumes and not prior,
                    f"Database container is absent but existing data volume remains: {service_name}; coordinated recovery is required")
            require(not native_mysql, "Reviewed MySQL requires an existing coordinated activation; ordinary deployment cannot provision it")
            require(not volume.get("external") and volume.get("driver", "local") == "local"
                    and not volume.get("driver_opts"),
                    f"Fresh database requires an absent ordinary local volume: {service_name}; external/driver-backed storage needs coordinated provisioning")
        # Keep the checked image expression: changing it to a config ID would
        # change Compose's service hash and needlessly recreate an unchanged DB.
        # Never pulling retains the inspected local image for all later startup,
        # including dependencies. The deploy lock/timer excludes competing rollouts;
        # external concurrent Docker tag/storage mutations are not coordinated here.
        override["services"][service_name] = {"pull_policy": "never"}
    return override


def main():
    try:
        require(len(sys.argv) == 1, "Database guard accepts only resolved Compose JSON on stdin")
        result = checked_override(parsed(sys.stdin.read()), DockerReadOnly())
        print(json.dumps(result, sort_keys=True))
        return 0
    except (GuardError, AttributeError, TypeError) as exc:
        print(f"Database continuity check failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
