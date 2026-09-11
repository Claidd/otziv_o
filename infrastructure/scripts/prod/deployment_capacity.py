"""Freeze release image references and fail before backup/rollout if disk is insufficient.

The budget retains all current images. Only exact existing layer chains are reused.
Measured layer streams cover download and unpack; SQL data size is a conservative
estimate, not an upper bound on future database/log growth. A 1 GiB reserve remains.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
from image_layer_capacity import chain_ids, measure_archive

GIB = 1024 ** 3
DIGEST = re.compile(r"^[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}$")


def run(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=600)
    if result.returncode:
        raise RuntimeError("Deployment metadata command failed: " + " ".join(args[:3]))
    return result.stdout


def freeze_image(reference):
    # Resolve the registry tag now, before the remote deployment. The VPS receives
    # the digest so an overwritten tag cannot substitute different release bytes.
    run(["docker", "pull", "--platform", "linux/amd64", reference])
    image = json.loads(run(["docker", "image", "inspect", reference]))[0]
    repository = reference.split("@")[0].rsplit(":", 1)[0] if "@" not in reference else reference.split("@")[0]
    if "@" in reference:
        frozen = reference
    else:
        choices = [item for item in image.get("RepoDigests", []) if item.split("@")[0] == repository]
        if len(choices) != 1:
            raise RuntimeError("Published image must have one matching repository digest")
        frozen = choices[0]
    if not DIGEST.fullmatch(frozen):
        raise RuntimeError("Published image identity is missing")
    manifest = json.loads(run(["docker", "buildx", "imagetools", "inspect", "--raw", frozen]))
    if "manifests" in manifest:
        choices = [item for item in manifest["manifests"] if item.get("platform", {}).get("os") == "linux" and item.get("platform", {}).get("architecture") == "amd64"]
        if len(choices) != 1: raise ValueError("Release must contain one linux/amd64 image")
        manifest = json.loads(run(["docker", "buildx", "imagetools", "inspect", "--raw", repository + "@" + choices[0]["digest"]]))
    config_id = manifest["config"]["digest"]
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", config_id): raise ValueError("Image config digest missing")
    with tempfile.TemporaryDirectory(prefix='otziv-release-layers-') as temporary:
        archive = str(Path(temporary) / 'image.tar')
        run(["docker", "image", "save", "--output", archive, frozen])
        layers = measure_archive(archive, config_id, manifest['layers'])
    return {"reference": frozen, "configId": config_id, "layers": layers}


def prepare(compose, revision, application_images):
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("Exact release revision required")
    source = Path(compose).read_text(encoding="utf-8")
    # Include every reviewed default, including inactive profiles; this can only
    # overestimate. Runtime checks separately require each active digest in the plan.
    refs = set(re.findall(r"(?m)^\s+image:\s+(?:\$\{[^}]*:-)?([a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64})", source))
    if not refs:
        raise ValueError("Reviewed Compose image defaults missing")
    images = {ref: freeze_image(ref) for ref in sorted(refs)}
    release = {}
    for service, reference in application_images.items():
        item = freeze_image(reference); images[item["reference"]] = item; release[service] = item["reference"]
    keycloak = re.search(r"\$\{OTZIV_KEYCLOAK_IMAGE:-([^}]+)\}", source)
    if not keycloak or keycloak[1] not in images:
        raise ValueError("Reviewed Keycloak default missing")
    release["keycloak"] = keycloak[1]
    return {"schema": "otziv-deploy-capacity-v2", "revision": revision, "images": list(images.values()), "releaseImages": release}


def validate_plan(plan, revision):
    if plan.get("schema") != "otziv-deploy-capacity-v2" or plan.get("revision") != revision:
        raise ValueError("Capacity plan does not match this release revision")
    images = plan.get("images")
    if not isinstance(images, list) or not images or len(images) > 64:
        raise ValueError("Invalid release image inventory")
    seen = set()
    for item in images:
        if (set(item) != {"reference", "configId", "layers"} or not DIGEST.fullmatch(item["reference"])
                or not re.fullmatch(r"sha256:[a-f0-9]{64}", item["configId"])
                or not isinstance(item['layers'], list) or not 0 < len(item['layers']) <= 128 or item["reference"] in seen):
            raise ValueError("Invalid or duplicate release image identity/size")
        for layer in item['layers']:
            if (set(layer) != {'diffId', 'compressedBytes', 'unpackedBytes'}
                    or not re.fullmatch(r"sha256:[a-f0-9]{64}", layer['diffId'])
                    or any(type(layer[key]) is not int or not 0 < layer[key] <= 30 * GIB
                           for key in ['compressedBytes', 'unpackedBytes'])):
                raise ValueError("Invalid layer size or identity")
        seen.add(item["reference"])
    for ref in plan.get("releaseImages", {}).values():
        if ref not in seen:
            raise ValueError("Release image missing from capacity inventory")
    return images


def budget(plan, revision, present, filesystem, deploy_path, docker_path, bundle_bytes, database_bytes, before_backup, existing_chains=frozenset()):
    images = validate_plan(plan, revision)
    if type(database_bytes) is not int or database_bytes < 0 or type(bundle_bytes) is not int or bundle_bytes < 0:
        raise ValueError("Invalid database or bundle measurement")
    demands = {}
    def add(path, amount, reason):
        device, available = filesystem(path)
        if type(available) is not int or available < 0:
            raise ValueError("Invalid filesystem measurement")
        row = demands.setdefault(device, {"device": device, "paths": [], "availableBytes": available, "requiredBytes": GIB, "reserveBytes": GIB, "components": {}})
        row["availableBytes"] = min(row["availableBytes"], available)
        if path not in row["paths"]: row["paths"].append(path)
        row["requiredBytes"] += amount; row["components"][reason] = row["components"].get(reason, 0) + amount
    missing = [item for item in images if not present(item["reference"])]
    unique_sizes = {}
    for item in missing:
        for chain, layer in zip(chain_ids([layer['diffId'] for layer in item['layers']]), item['layers']):
            if chain not in existing_chains:
                # Retain room for both temporary and final unpack plus download.
                size = layer['compressedBytes'] + 2 * layer['unpackedBytes']
                unique_sizes[chain] = max(unique_sizes.get(chain, 0), size)
    add(docker_path, sum(unique_sizes.values()), "imageDownloadAndUnpack")
    # Old deployed files and the incoming extraction coexist during rollback setup.
    add(deploy_path, 2 * bundle_bytes, "bundleAndRollback")
    if before_backup:
        add(deploy_path, max(256 * 1024 ** 2, 2 * database_bytes + 64 * 1024 ** 2), "encryptedDatabaseBackup")
    rows = list(demands.values())
    return {"schema": "otziv-deploy-capacity-result-v1", "revision": revision,
            "result": "PASS" if all(row["availableBytes"] >= row["requiredBytes"] for row in rows) else "FAIL",
            "filesystems": rows, "missingImages": [item["reference"] for item in missing],
            "missingLayerChains": len(unique_sizes), "existingLayerChains": len(existing_chains),
            "currentImagesPreserved": True, "automaticDeletion": False}


def check(plan, revision, deploy_path, bundle, before_backup):
    deploy_path = str(Path(deploy_path).resolve(strict=True))
    info = json.loads(run(["docker", "info", "--format", "{{json .}}"])); docker_path = info["DockerRootDir"]
    # This host policy currently supports the proven overlay2 store. A different
    # driver requires a reviewed accounting model, not an assumed /var/lib path.
    if info.get("Driver") != "overlay2":
        raise ValueError("Capacity preflight requires the reviewed overlay2 storage driver")
    images = validate_plan(plan, revision)
    config_ids = {item["reference"]: item["configId"] for item in images}
    existing_chains = set()
    image_ids = sorted(set(run(['docker', 'image', 'ls', '--all', '--quiet', '--no-trunc']).split()))
    if any(not re.fullmatch(r'sha256:[a-f0-9]{64}', identity) for identity in image_ids):
        raise ValueError('Invalid installed image identity')
    for start in range(0, len(image_ids), 40):
        installed = json.loads(run(['docker', 'image', 'inspect', *image_ids[start:start+40]]))
        for image in installed:
            existing_chains.update(chain_ids(image['RootFS']['Layers']))
    def present(ref):
        result = subprocess.run(["docker", "image", "inspect", config_ids[ref]], capture_output=True, text=True, timeout=30)
        if result.returncode:
            if "No such image" in result.stderr or "No such object" in result.stderr: return False
            raise RuntimeError("Cannot inspect release image on deployment host")
        return json.loads(result.stdout)[0].get("Id") == config_ids[ref]
    def filesystem(path):
        path = Path(path).resolve(strict=True); stat = os.statvfs(path)
        return str(path.stat().st_dev), stat.f_bavail * stat.f_frsize
    with tarfile.open(bundle, "r:gz") as archive:
        expanded = sum(item.size for item in archive if item.isfile())
    database_bytes = 0
    if before_backup:
        sql = 'SELECT COALESCE(SUM(DATA_LENGTH),0) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()'
        query = 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" --batch --skip-column-names "$MYSQL_DATABASE" -e ' + "'" + sql + "'"
        measured = run(["docker", "exec", "my-mysql", "sh", "-c", query]).strip()
        if not measured.isdecimal(): raise ValueError("Database size measurement failed")
        database_bytes = int(measured)
    return budget(plan, revision, present, filesystem, deploy_path, docker_path, expanded, database_bytes, before_backup, existing_chains)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--compose", required=True); p.add_argument("--revision", required=True); p.add_argument("--output", required=True)
    p.add_argument("--app", required=True); p.add_argument("--nginx", required=True)
    p.add_argument("--external-review-worker"); p.add_argument("--whatsapp")
    p.add_argument("--docker-observer", required=True)
    q = sub.add_parser("check")
    q.add_argument("--plan", required=True); q.add_argument("--revision", required=True)
    q.add_argument("--deploy-path", required=True); q.add_argument("--bundle", required=True); q.add_argument("--before-backup", action="store_true")
    args = parser.parse_args()
    if args.command == "prepare":
        selected = {name: getattr(args, name.replace('-', '_')) for name in ['app','nginx','docker-observer','external-review-worker','whatsapp'] if getattr(args, name.replace('-', '_'))}
        result = prepare(args.compose, args.revision, selected)
        Path(args.output).write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    else:
        result = check(json.loads(Path(args.plan).read_text()), args.revision, args.deploy_path, args.bundle, args.before_backup)
        print(json.dumps(result))
        if result["result"] != "PASS":
            raise RuntimeError("Insufficient disk space: deployment stopped; current services and images are preserved")


if __name__ == "__main__":
    try: main()
    except (RuntimeError, ValueError, OSError, KeyError, subprocess.TimeoutExpired, tarfile.TarError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)
