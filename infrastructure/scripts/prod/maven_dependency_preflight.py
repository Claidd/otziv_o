"""Resolve pinned Maven dependencies before tests; retry only resolution failures."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
RESOLUTION_FAILURE = re.compile(
    r"Non-resolvable (?:parent|import) POM|Could not (?:transfer|find) artifact|"
    r"Failed to read artifact descriptor|Plugin .+ could not be resolved"
)


def preflight(pom: Path, *, run=subprocess.run, sleep=time.sleep, output=sys.stdout) -> int:
    wrapper = [str(ROOT / "backend" / "mvnw.cmd")] if os.name == "nt" else ["bash", "./mvnw"]
    # -U rechecks missing releases cached by an earlier HTTP 429/404. Versions,
    # repositories, validation rules and the subsequent verify remain unchanged.
    command = [*wrapper, "-B", "-ntp", "-U", "-Daether.connector.basic.threads=1",
               "-f", str(pom.resolve()), "validate",
               "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline"]
    for attempt in range(1, 4):
        result = run(command, cwd=ROOT / "backend", stdout=subprocess.PIPE,
                     stderr=subprocess.STDOUT, text=True, check=False, timeout=300)
        output.write(result.stdout or "")
        output.flush()
        if result.returncode == 0:
            return 0
        if attempt == 3 or not RESOLUTION_FAILURE.search(result.stdout or ""):
            return result.returncode
        print(f"Maven dependency resolution failed; retry {attempt + 1}/3 after {attempt * 15}s.", file=output, flush=True)
        sleep(attempt * 15)
    raise AssertionError("unreachable")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pom", type=Path, required=True)
    args = parser.parse_args()
    if not args.pom.is_file():
        parser.error("pom must name an existing file")
    return preflight(args.pom)


if __name__ == "__main__":
    raise SystemExit(main())
