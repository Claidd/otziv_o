#!/usr/bin/env python3
"""Build tiny signed APK fixtures without historical APKs or release secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def run(args):
    result = subprocess.run([str(x) for x in args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('Android fixture tool failed: ' + Path(str(args[0])).name)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--sdk', type=Path)
    args = parser.parse_args()
    local_properties = Path(__file__).resolve().parents[1] / 'android/local.properties'
    configured = None
    if local_properties.exists():
        for line in local_properties.read_text(encoding='utf-8').splitlines():
            if line.startswith('sdk.dir='):
                configured = line.split('=', 1)[1].replace('\\:', ':').replace('\\\\', '\\')
    candidates = [args.sdk, os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'), configured, Path.home() / 'AppData/Local/Android/Sdk', Path.home() / 'Android/Sdk', Path.home() / 'Library/Android/sdk']
    sdk = next((Path(p) for p in candidates if p and (Path(p) / 'build-tools').is_dir()), None)
    if sdk is None:
        raise RuntimeError('Android SDK is required; set ANDROID_HOME')
    tools = sorted((sdk / 'build-tools').iterdir(), key=lambda p: tuple(int(x) for x in p.name.split('.') if x.isdigit()), reverse=True)
    build = next((p for p in tools if (p / 'lib/apksigner.jar').is_file()), None)
    if build is None:
        raise RuntimeError('Android Build-Tools with apksigner are required')
    platforms = sorted((sdk / 'platforms').glob('android-*/android.jar'), key=lambda p: int(p.parent.name.split('-')[1]), reverse=True)
    if not platforms:
        raise RuntimeError('An Android platform android.jar is required')
    java = shutil.which('java')
    keytool = shutil.which('keytool')
    if not java or not keytool:
        raise RuntimeError('Java and keytool must be on PATH')
    args.out.mkdir(parents=True, exist_ok=False)
    store = args.out / 'fixture.p12'
    # These throwaway credentials protect only a newly generated test key.
    run([keytool, '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-storetype', 'PKCS12', '-keystore', store, '-storepass', 'fixture-only', '-keypass', 'fixture-only', '-dname', 'CN=Ephemeral APK verifier fixture', '-noprompt'])
    cert = run([keytool, '-exportcert', '-alias', 'fixture', '-keystore', store, '-storepass', 'fixture-only'])
    fingerprint = hashlib.sha256(cert).hexdigest().upper()
    aapt = build / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
    for kind, code, debug in [('release', 62, 'false'), ('debug', 53, 'true')]:
        manifest = args.out / (kind + '-manifest.xml')
        manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.hunt.otziv" android:versionCode="{code}" android:versionName="1.0.{code}"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="35"/><application android:label="Verifier fixture" android:debuggable="{debug}" android:hasCode="false"/></manifest>', encoding='utf-8')
        unsigned = args.out / (kind + '-unsigned.apk')
        signed = args.out / (kind + '.apk')
        run([aapt, 'link', '-o', unsigned, '--manifest', manifest, '-I', platforms[0]])
        run([java, '-jar', build / 'lib/apksigner.jar', 'sign', '--ks', store, '--ks-key-alias', 'fixture', '--ks-pass', 'pass:fixture-only', '--key-pass', 'pass:fixture-only', '--out', signed, unsigned])
    result = {'releaseApk': str((args.out / 'release.apk').resolve()), 'debugApk': str((args.out / 'debug.apk').resolve()), 'signerSha256': fingerprint}
    (args.out / 'fixtures.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
