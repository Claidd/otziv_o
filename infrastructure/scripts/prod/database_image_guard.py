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
        mounts = service.get("volumes", [])
        require(isinstance(mounts, list) and all(isinstance(mount, dict) for mount in mounts), f"Invalid database mounts: {service_name}")
        data_mounts = [mount for mount in mounts if isinstance(mount.get("target"), str)
                       and (mount["target"] == data_path or mount["target"].startswith(data_path + "/")
                            or data_path.startswith(mount["target"].rstrip("/") + "/"))]
        require(len(data_mounts) == 1 and data_mounts[0].get("type") == "volume"
                and data_mounts[0].get("source") == volume_key and data_mounts[0].get("target") == data_path
                and not data_mounts[0].get("read_only"), f"Database storage mapping needs a coordinated procedure: {service_name}")
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
            actual = container.get("Mounts", [])
            require(isinstance(actual, list) and all(isinstance(mount, dict) for mount in actual), "Invalid container mount metadata")
            data = [mount for mount in actual if isinstance(mount.get("Destination"), str)
                    and (mount["Destination"] == data_path or mount["Destination"].startswith(data_path + "/")
                         or data_path.startswith(mount["Destination"].rstrip("/") + "/"))]
            require(volume_name in existing_volumes and len(data) == 1 and data[0].get("Type") == "volume"
                    and data[0].get("Name") == volume_name and data[0].get("Destination") == data_path
                    and data[0].get("RW") is True,
                    f"Database data volume identity changed: {service_name}")
        else:
            prior = volume_names(docker("volume", "ls", "--format", "{{json .Name}}",
                                        "--filter", f"label=com.docker.compose.project={PROJECT}",
                                        "--filter", f"label=com.docker.compose.volume={volume_key}"))
            require(volume_name not in existing_volumes and not prior,
                    f"Database container is absent but existing data volume remains: {service_name}; coordinated recovery is required")
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
