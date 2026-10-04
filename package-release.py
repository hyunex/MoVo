#!/usr/bin/env python3
"""Post-sign all unsigned release variants; never build or publish unsigned APKs."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
VERSION = "1.0.33"
CODE = "34"
PACKAGE = "com.example.mpvlibrary"
VARIANTS = {"arm64-v8a": "arm64", "armeabi-v7a": "armv7", "x86_64": "x86_64", "universal": "universal"}


def fail(message):
    raise RuntimeError(message)


def run(args, env=None, binary=False):
    result = subprocess.run([str(x) for x in args], stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env=env, text=not binary)
    if result.returncode:
        # Commands contain references only, never passwords. Do not echo tool stderr:
        # providers may include sensitive configuration in their error messages.
        fail(f"{Path(args[0]).name} {args[1]} failed (exit {result.returncode}); no artifacts published")
    return result.stdout


def secure_file(path):
    if path.is_symlink() or not path.is_file():
        fail(f"Missing regular private file: {path}")
    if path.stat().st_uid != os.getuid():
        fail(f"Private file is not owned by this user: {path}")
    path.chmod(0o600)


def password(path):
    secure_file(path)
    value = path.read_text().rstrip("\r\n")
    if not value or "\n" in value or "\r" in value:
        fail(f"Password file must contain one nonempty line: {path}")
    return value


def fingerprints(text):
    return re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", text)


def one_fingerprint(text, context):
    values = fingerprints(text)
    if len(values) != 1:
        fail(f"Expected one signing certificate for {context}, found {len(values)}")
    return values[0].lower()


def signer(key, alias, variable):
    return ["--ks", str(key), "--ks-key-alias", alias, "--ks-pass", f"env:{variable}",
            "--key-pass", f"env:{variable}"]


def export_certificate(keytool, key, alias, variable, env):
    return run([keytool, "-exportcert", "-keystore", key, "-alias", alias,
                "-storepass:env", variable], env, binary=True)


def check_manifest(aapt, apk, abi, unsigned):
    badging = run([aapt, "dump", "badging", apk])
    expected = f"package: name='{PACKAGE}' versionCode='{CODE}' versionName='{VERSION}'"
    if expected not in badging or "sdkVersion:'26'" not in badging:
        fail(f"Not the expected MoVo {VERSION}/code {CODE}/minSdk 26 APK: {apk}")
    if "application-debuggable" in badging:
        fail(f"Refusing debuggable APK: {apk}")
    tree = run([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"])
    debug = re.findall(r"android:debuggable[^\n]*", tree)
    # Android defaults an absent application debuggable flag to false.
    if len(debug) > 1 or (debug and not re.search(r"\(type 0x12\)0x0(?:\s|$)", debug[0])):
        fail(f"Manifest enables debugging or has an unexpected debug flag: {apk}")
    native = re.search(r"^native-code:\s*(.*)$", badging, re.MULTILINE)
    found = set(re.findall(r"'([^']+)'", native.group(1))) if native else set()
    required = set(VARIANTS) - {"universal"} if abi == "universal" else {abi}
    if found != required:
        fail(f"Unexpected ABI contents for {abi}: {sorted(found)}")
    if unsigned:
        with zipfile.ZipFile(apk) as archive:
            if any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC|SF)", name, re.I)
                   for name in archive.namelist()):
                fail(f"Input already has JAR signatures: {apk}")
        # A v2/v3 APK signing block sits just before the ZIP central directory.
        with apk.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - 65557))
            trailer = stream.read()
            offset = trailer.rfind(b"PK\x05\x06")
            if offset < 0:
                fail(f"Missing ZIP end record: {apk}")
            central = int.from_bytes(trailer[offset + 16:offset + 20], "little")
            if central >= 16:
                stream.seek(central - 16)
                if stream.read(16) == b"APK Sig Block 42":
                    fail(f"Input already has APK signing block: {apk}")
    return badging


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog="Build first: cd mpv-player && ./gradlew assembleRelease. Requires the previous published APK, matching legacy and dedicated private keys, and existing signing lineage; no new signing identity or fresh-install fallback.")
    parser.add_argument("--input-dir", type=Path, default=ROOT / "mpv-player/app/build/outputs/apk/release")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "release-artifacts" / VERSION)
    parser.add_argument("--previous-apk", type=Path, default=os.environ.get("PREVIOUS_APK", "/tmp/MoVo-v1.0.32-arm64.apk"))
    parser.add_argument("--build-tools", type=Path, default=None, help="Android SDK build-tools directory; or set ANDROID_BUILD_TOOLS")
    args = parser.parse_args()
    os.umask(0o077)
    tools = args.build_tools or (Path(os.environ["ANDROID_BUILD_TOOLS"]) if "ANDROID_BUILD_TOOLS" in os.environ else None)
    if tools is None:
        sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", "/opt/android-sdk")))
        candidates = sorted((sdk / "build-tools").glob("*"), key=lambda p: tuple(int(x) for x in re.findall(r"\d+", p.name)))
        tools = candidates[-1] if candidates else None
    if tools is None or not all((tools / name).is_file() for name in ("aapt", "apksigner", "zipalign")):
        fail("Provide --build-tools or ANDROID_BUILD_TOOLS containing aapt, apksigner and zipalign")
    aapt, apksigner, zipalign = (tools / name for name in ("aapt", "apksigner", "zipalign"))
    keytool = shutil.which("keytool")
    if not keytool:
        fail("keytool is required")
    if ROOT.joinpath("VERSION").read_text().strip() != VERSION:
        fail("VERSION does not match release packager")
    if not args.previous_apk.is_file():
        fail(f"Previous published APK is required: {args.previous_apk}; set PREVIOUS_APK or --previous-apk")
    prior_badging = run([aapt, "dump", "badging", args.previous_apk])
    if f"package: name='{PACKAGE}' versionCode='33' versionName='1.0.32'" not in prior_badging:
        fail("Previous APK must be the published MoVo 1.0.32/code 33 with the same package")
    prior_old_evidence = run([apksigner, "verify", "--min-sdk-version", "26",
                              "--max-sdk-version", "27", "--print-certs", args.previous_apk])
    prior_old_cert = one_fingerprint(prior_old_evidence, "previous published APK / API 26-27")
    prior_new_evidence = run([apksigner, "verify", "--min-sdk-version", "28",
                              "--print-certs", args.previous_apk])
    prior_new_cert = one_fingerprint(prior_new_evidence, "previous published APK / API 28+")
    inputs = {abi: args.input_dir / f"app-{abi}-release-unsigned.apk" for abi in VARIANTS}
    actual = set(args.input_dir.glob("*.apk"))
    if actual != set(inputs.values()):
        fail("Input directory must contain exactly unsigned arm64-v8a, armeabi-v7a, x86_64 and universal release APKs")
    for abi, apk in inputs.items():
        check_manifest(aapt, apk, abi, True)
    output = args.output_dir.absolute()
    if output.exists():
        fail(f"Output already exists; preserve published artifacts and choose a new --output-dir: {output}")
    default_old = Path.home() / ".android/debug.keystore"
    old_key = Path(os.environ.get("OLD_KEYSTORE", str(default_old))).expanduser()
    old_alias = os.environ.get("OLD_ALIAS", "androiddebugkey")
    env = os.environ.copy()
    if "OLD_PASS_FILE" in env:
        env["MOVO_OLD_PASSWORD"] = password(Path(env["OLD_PASS_FILE"]).expanduser())
    elif old_key.absolute() == default_old.absolute() and old_alias == "androiddebugkey":
        env["MOVO_OLD_PASSWORD"] = "android"
    else:
        fail("Custom old signing key requires OLD_PASS_FILE (one password for store and key)")
    secure_file(old_key)
    old_der = export_certificate(keytool, old_key, old_alias, "MOVO_OLD_PASSWORD", env)
    old_cert = hashlib.sha256(old_der).hexdigest()
    if old_cert != prior_old_cert:
        fail("OLD_KEYSTORE certificate does not match previous published APK on API 26/27; refusing release")
    private = Path.home() / ".config/movo-signing"
    if private.is_symlink() or private.resolve().is_relative_to(ROOT):
        fail("Signing directory must be a real directory outside the repository")
    if not private.is_dir():
        fail("Existing signing directory is required; restore signing backups, never replace the identity")
    if private.stat().st_uid != os.getuid():
        fail("Signing directory is not owned by this user")
    private.chmod(0o700)
    key = private / "release.jks"
    pass_file = private / "release.pass"
    lineage = private / "release.lineage"
    lock = private / "release.lock"
    if lock.is_symlink():
        fail("Signing lock must not be a symlink")
    with lock.open("a") as lock_stream:
        lock.chmod(0o600)
        fcntl.flock(lock_stream, fcntl.LOCK_EX)
        secure_file(key)
        env["MOVO_RELEASE_PASSWORD"] = password(pass_file)
        secure_file(lineage)
        new_der = export_certificate(keytool, key, "movo-release", "MOVO_RELEASE_PASSWORD", env)
        new_cert = hashlib.sha256(new_der).hexdigest()
        if new_cert != prior_new_cert:
            fail("Stored release key certificate does not match previous published APK on API 28+; refusing release")
        if old_cert == new_cert:
            fail("Dedicated release key must differ from legacy key")
        old_options = signer(old_key, old_alias, "MOVO_OLD_PASSWORD")
        new_options = signer(key, "movo-release", "MOVO_RELEASE_PASSWORD")
        lineage_evidence = run([apksigner, "lineage", "--in", lineage, "--print-certs"])
        if [x.lower() for x in fingerprints(lineage_evidence)] != [old_cert, new_cert]:
            fail("Existing lineage does not exactly match legacy -> dedicated release certificates")
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".release-staging-", dir=output.parent) as temp:
            staging = Path(temp)
            evidence = {"version": VERSION, "versionCode": int(CODE), "oldCertificateSha256": old_cert,
                        "newCertificateSha256": new_cert, "previousApk": str(args.previous_apk.resolve()),
                        "previousApkSha256": hashlib.sha256(args.previous_apk.read_bytes()).hexdigest(),
                        "rotationMinSdk": 28, "minSdk": 26,
                        "requiredPrivateBackups": [str(key), str(pass_file), str(old_key)],
                        "requiredPublicBackup": str(lineage), "apks": {}}
            (staging / "legacy-certificate.der").write_bytes(old_der)
            (staging / "release-certificate.der").write_bytes(new_der)
            shutil.copyfile(lineage, staging / "MoVo-release.lineage")
            (staging / "lineage-certificates.txt").write_text(lineage_evidence)
            for abi, source in inputs.items():
                aligned = staging / f"aligned-{abi}.apk"
                target = staging / f"MoVo-v{VERSION}-{VARIANTS[abi]}.apk"
                run([zipalign, "-f", "-p", "4", source, aligned])
                run([apksigner, "sign", "--min-sdk-version", "26", "--rotation-min-sdk-version", "28",
                     "--debuggable-apk-permitted", "false", "--v1-signing-enabled", "true",
                     "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                     "--v4-signing-enabled", "false", "--lineage", lineage,
                     *old_options, "--next-signer", *new_options, "--out", target, aligned], env)
                run([zipalign, "-c", "-p", "4", target])
                check_manifest(aapt, target, abi, False)
                traces = {}
                for label, bounds, expected in (
                    ("all-supported", ["--min-sdk-version", "26"], new_cert),
                    ("sdk26-27", ["--min-sdk-version", "26", "--max-sdk-version", "27"], old_cert),
                    ("sdk28", ["--min-sdk-version", "28", "--max-sdk-version", "28"], new_cert)):
                    trace = run([apksigner, "verify", "--verbose", "--print-certs", *bounds, target])
                    if one_fingerprint(trace, label) != expected:
                        fail(f"Wrong signing certificate for {target.name} / {label}")
                    traces[label] = trace
                    (staging / f"{target.stem}-{label}.txt").write_text(trace)
                evidence["apks"][target.name] = {"sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                                                    "sdk26-27Certificate": old_cert, "sdk28PlusCertificate": new_cert}
                aligned.unlink()
            (staging / "signing-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
            for item in staging.iterdir():
                item.chmod(0o644)  # Public deliverables only; private assets stay outside repository.
            staging.chmod(0o755)
            staging.rename(output)
        print(f"Published verified release APKs and public certificates/lineage: {output}")
        print(f"Legacy certificate SHA-256 (API 26/27): {old_cert}")
        print(f"Dedicated release certificate SHA-256 (API 28+): {new_cert}")
        print("COMPATIBILITY TRADEOFF: API 26/27 retain the old debug identity for in-place updates; API 28+ use the dedicated key with lineage.")
        print(f"REQUIRED PRIVATE BACKUPS (never distribute): {key}, {pass_file}, {old_key}")
        if "OLD_PASS_FILE" in env:
            print(f"Also back up the old password file privately: {env['OLD_PASS_FILE']}")
        print(f"REQUIRED PUBLIC BACKUP: {lineage} (also delivered in output)")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Release packaging refused: {error}", file=sys.stderr)
        sys.exit(1)
