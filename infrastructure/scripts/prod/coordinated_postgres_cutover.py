"""Select an exact reviewed PostgreSQL transition; its own backup/lock gates remain mandatory."""
import argparse
import importlib
import json
from pathlib import Path
import sys

TRANSITIONS = {
    "ghcr.io/claidd/otziv-security@sha256:32adf590dacac8d07c6dcd5bfbad27d527ae2ddf18e9822a404ba52ce69524a2": "coordinated_postgres_zlib_refresh",
    "ghcr.io/claidd/otziv-security@sha256:b3eb812fca1c3e2d09a033f0059a5b8b66bf3d6567c5ad09d12c4e4d7bd4a21c": "coordinated_postgres_gosu_refresh",
}

def selected_transition(root):
    path = Path(root).resolve(strict=True) / "infrastructure/runtime-security/reviewed-image-activations.json"
    if path.is_symlink():
        raise ValueError("postgres_cutover_activation_symlink")
    entries = [item for item in json.loads(path.read_bytes()).get("images", []) if item.get("component") == "postgres"]
    if len(entries) != 1 or entries[0].get("reference") not in TRANSITIONS:
        raise ValueError("postgres_cutover_target_not_reviewed")
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
        print("Reviewed PostgreSQL cutover failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
