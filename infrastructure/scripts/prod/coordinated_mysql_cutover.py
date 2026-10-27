"""Select an exact reviewed MySQL transition; its own backup/lock gates remain mandatory."""
import argparse
import importlib
import json
from pathlib import Path
import sys

TRANSITIONS = {
    "ghcr.io/claidd/otziv-security@sha256:eda70c6cb0a835e788a5ec96539610e9ef6a574ffc4129c9c5717acd647e159a": "coordinated_mysql_refresh",
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
