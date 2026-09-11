"""Offline bounded proof using preinstalled JDK/JARs; no Maven, network or cache updates."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--classpath-file", required=True, type=Path)
    parser.add_argument("--fixed-jar", required=True, type=Path)
    parser.add_argument("--old-jar", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    proof = Path(__file__).resolve().parent
    repo = proof.parents[3]
    output = args.out.resolve()
    if output == proof or proof in output.parents:
        parser.error("write execution output to separate scratch directory, not frozen evidence")
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    suffix = ".exe" if os.name == "nt" else ""
    java_bin = args.java_home.resolve() / "bin"
    classpath = args.classpath_file.read_text(encoding="utf-8").strip()
    if not classpath or any(not Path(p).is_file() for p in classpath.split(os.pathsep)):
        parser.error("classpath must list existing local JARs using this platform's path separator")
    fixed, old = args.fixed_jar.resolve(), args.old_jar.resolve()
    expected = {fixed: "7cea4360710adb44c588e13afdcdc0171fc1a3e61cc4562703ddb1f0a36f0ca4",
                old: "6fc03e51e73fa884f06e7eae0761e045e56fdeb4e146a4d952e3023cc9e3fb43"}
    for file, digest in expected.items():
        if sha256(file.read_bytes()) != digest:
            parser.error("input binary hash does not match the reviewed artifact")
    records = []

    def run(label, command, expected_exit=0):
        started = time.monotonic()
        result = subprocess.run(command, capture_output=True, timeout=30, cwd=output)
        (output / (label + ".stdout.log")).write_bytes(result.stdout)
        (output / (label + ".stderr.log")).write_bytes(result.stderr)
        records.append({"label": label, "exitCode": result.returncode, "expectedExitCode": expected_exit,
                        "elapsedSeconds": round(time.monotonic() - started, 3),
                        "stdoutSha256": sha256(result.stdout), "stderrSha256": sha256(result.stderr)})
        (output / "execution-records.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
        if result.returncode != expected_exit:
            raise RuntimeError(label + " failed; inspect the separate retained logs")
        print(label + ": PASS", flush=True)

    compiler_args = ["--release", "17", "-cp", classpath + os.pathsep + str(fixed), "-d", str(classes),
                     str(proof / "JTidySuppressionProof.java"), str(proof / "JTidyNestingProof.java")]
    compiler_file = output / "compile.args"
    compiler_file.write_text("\n".join('"' + s.replace("\\", "/") + '"' for s in compiler_args), encoding="utf-8")
    run("compile", [str(java_bin / ("javac" + suffix)), "-J-Xmx256m", "@" + str(compiler_file)])
    run("odc-selector", [str(java_bin / ("java" + suffix)), "-Xmx256m", "-Xss2m", "-cp",
                         str(classes) + os.pathsep + classpath, "JTidySuppressionProof", str(proof / "before.xml"),
                         str(repo / "infrastructure/runtime-security/maven-false-positives.xml"),
                         str(fixed), str(old), str(proof / "raw-c13"), str(output / "selector-result.json")])
    for version, file in [("r938", old), ("1.0.5", fixed)]:
        for mode in ["ordinary", "excessive"]:
            run(version + "-" + mode,
                [str(java_bin / ("java" + suffix)), "-Xms16m", "-Xmx64m", "-Xss2m", "-cp",
                 str(classes) + os.pathsep + str(file), "JTidyNestingProof", mode],
                42 if version == "r938" and mode == "excessive" else 0)
        run(version + "-actual-parser-bytecode", [str(java_bin / ("javap" + suffix)), "-p", "-c", "-classpath",
                                                str(file), "org.w3c.tidy.ParserImpl"])
    run("java-version", [str(java_bin / ("java" + suffix)), "-version"])
    inputs = [{"name": Path(p).name, "sha256": sha256(Path(p).read_bytes())} for p in classpath.split(os.pathsep)]
    (output / "engine-classpath.json").write_text(json.dumps(inputs, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
