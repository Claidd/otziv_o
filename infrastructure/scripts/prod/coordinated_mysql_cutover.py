"""Select an exact reviewed MySQL transition; its own backup/lock gates remain mandatory."""
import argparse
import importlib
import json
from pathlib import Path
import sys

TRANSITIONS = {
    "ghcr.io/claidd/otziv-security@sha256:200c94f0b78df0916d165b039f56aff795889aec1f0db1fbaf667074730e1bb8": "coordinated_mysql_base_refresh",
    "ghcr.io/claidd/otziv-security@sha256:370d1bbed24bb5f1178ebe2a332f018f8224cf8ac48bc61103bc4d470e9030b2": "coordinated_mysql_libxml2_refresh",
    "ghcr.io/claidd/otziv-security@sha256:5ff5fb9db73125ad1056bad980eb17c25d944c8a5775b53dbfe845b826ae9109": "coordinated_mysql_partition_refresh",
    "ghcr.io/claidd/otziv-security@sha256:52be6ed6c0039196dca88202ffd9714520d509bdde9b2f575238d1ea2d81028a": "coordinated_mysql_kerberos_refresh",
    "ghcr.io/claidd/otziv-security@sha256:445e15dc40f18a25990558d95e8918b66938c87a7b6a27626f88f7d1e74f83d4": "coordinated_mysql_util_linux_refresh",
    "ghcr.io/claidd/otziv-security@sha256:0ad40a668c292ebe70297cd4df66b2e9c5067fad3bfdb54179347a597e1ac48f": "coordinated_mysql_curl_refresh",
    "ghcr.io/claidd/otziv-security@sha256:d8b2c5d02611016eee74b77caede5c57a16cd8a1f379bf5c74d322bfd1b5f75d": "coordinated_mysql_brotli_refresh",
}

def selected_transition(root):
    path = Path(root).resolve(strict=True) / "infrastructure/runtime-security/reviewed-image-activations.json"
    if path.is_symlink():
        raise ValueError("mysql_cutover_activation_symlink")
    entries = [item for item in json.loads(path.read_bytes()).get("images", []) if item.get("component") == "mysql"]
    if len(entries) != 1 or entries[0].get("reference") not in TRANSITIONS:
        raise ValueError("mysql_cutover_target_not_reviewed")
    return TRANSITIONS[entries[0]["reference"]]

def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--root", required=True)
    args, _ = parser.parse_known_args()
    importlib.import_module(selected_transition(args.root)).main()

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Reviewed MySQL cutover failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
