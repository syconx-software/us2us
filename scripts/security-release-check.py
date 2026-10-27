#!/usr/bin/env python3
"""Read-only repository/artifact checks. Does not contact production or print secret values.

Usage: python3 scripts/security-release-check.py [--android-manifest merged.xml]
       [--android-bundle app.aab] [--ios-app Release/us2us.app] [--relay-output relay/.output/server]
       [--relay-release /prepared/release]  (requires the matching build Node on PATH)
Exit 1 means a checked condition failed. PENDING gates are never release approval.
"""
import argparse
import json
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
results = []


def check(label, passed, detail=""):
    results.append({"check": label, "status": "PASS" if passed else "FAIL", "detail": detail})


def pending(label, detail):
    results.append({"check": label, "status": "PENDING", "detail": detail})


def android_manifest(path):
    root = ET.parse(path).getroot()
    app = root.find("application")
    check("Android backup disabled", app is not None and app.get(ANDROID + "allowBackup") == "false")
    check("Android full backup disabled", app is not None and app.get(ANDROID + "fullBackupContent") == "false")
    check("Android not debuggable", app is not None and app.get(ANDROID + "debuggable", "false") == "false")
    check("Android no release network override", app is not None and app.get(ANDROID + "networkSecurityConfig") is None)
    if app is not None:
        unexpected = [entry.get(ANDROID + "name", "unknown") for entry in app
                      if entry.tag in ("activity", "service", "receiver", "provider")
                      and entry.get(ANDROID + "exported") == "true"
                      and entry.get(ANDROID + "name") not in (".app.MainActivity", "com.syconx.us2us.app.MainActivity")
                      # AndroidX's profile receiver is intentionally exported to shell/system,
                      # guarded by the platform's privileged DUMP permission. Normal permissions
                      # (e.g. INTERNET) must not turn an unexpected exported receiver into a pass.
                      and not (entry.tag == "receiver"
                               and entry.get(ANDROID + "name") == "androidx.profileinstaller.ProfileInstallReceiver"
                               and entry.get(ANDROID + "permission") == "android.permission.DUMP")]
        check("Android exported components", not unexpected, ", ".join(unexpected))


def ios_info(path):
    with path.open("rb") as source:
        info = plistlib.load(source)
    ats = info.get("NSAppTransportSecurity", {})
    broad = any(ats.get(key, False) for key in (
        "NSAllowsArbitraryLoads", "NSAllowsArbitraryLoadsForMedia", "NSAllowsArbitraryLoadsInWebContent"))
    exceptions = ats.get("NSExceptionDomains", {})
    insecure = any(item.get("NSExceptionAllowsInsecureHTTPLoads", False)
                   or item.get("NSTemporaryExceptionAllowsInsecureHTTPLoads", False)
                   for item in exceptions.values())
    check("iOS ATS has no insecure exception", not broad and not insecure)


def run(args):
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    # Paths only; private files ignored by Git are deliberately never opened here.
    secret_paths = [name for name in tracked if name and (
        (Path(name).name.startswith(".env") and not name.endswith(".example"))
        or Path(name).suffix.lower() in (".p8", ".p12", ".pem", ".key", ".jks", ".keystore")
        and not name.endswith("/debug.keystore"))]
    check("No tracked credential files", not secret_paths, ", ".join(secret_paths))
    private_key = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")
    key_paths = [name for name in tracked if name and (ROOT / name).is_file()
                 and (ROOT / name).stat().st_size < 2_000_000
                 and private_key.search((ROOT / name).read_bytes())]
    check("No tracked PEM private keys", not key_paths, ", ".join(key_paths))
    manifest = args.android_manifest or ROOT / "android/app/src/main/AndroidManifest.xml"
    android_manifest(manifest)
    if args.android_manifest is None:
        pending("Android built manifest", "Only source policy checked; pass --android-manifest after building Release.")
    extraction = ET.parse(ROOT / "android/app/src/main/res/xml/data_extraction_rules.xml").getroot()
    domains = {"root", "file", "database", "sharedpref", "external"}
    for scope in ("cloud-backup", "device-transfer"):
        section = extraction.find(scope)
        excluded = set() if section is None else {e.get("domain") for e in section.findall("exclude") if e.get("path") == "."}
        check("Android " + scope + " excluded", domains <= excluded)
    ios_info((args.ios_app / "Info.plist") if args.ios_app else ROOT / "ios/App/Config/Info.plist")
    if args.ios_app is None:
        pending("iOS built app", "Only source ATS policy checked; pass --ios-app after building Release.")
    else:
        pending("iOS distribution signature", "Info.plist inspection does not verify a distribution signature or provisioning profile.")
    nginx = (ROOT / "relay/deploy/nginx/us2us.conf").read_text()
    check("Nginx template access logs disabled", bool(re.search(r"\baccess_log\s+off\s*;", nginx))
          and not re.search(r"\baccess_log\s+(?!off\s*;)[^;]+;", re.sub(r"#.*", "", nginx)))
    check("Nginx template includes server version suppression", bool(re.search(r"\bserver_tokens\s+off\s*;", re.sub(r"#.*", "", nginx))))
    check("Relay example IP logging disabled", bool(re.search(r"^LOG_IPS=0$", (ROOT / "relay/.env.example").read_text(), re.M)))
    if args.android_bundle:
        with zipfile.ZipFile(args.android_bundle) as bundle:
            names = bundle.namelist()
        check("Android bundle excludes test/desktop native libraries", not any(
            "libsignal_jni_testing" in n or n.endswith((".dylib", ".dll")) for n in names))
        pending("Android upload signature", "Verify the upload signer independently; a successful bundle build does not prove signing.")
    else:
        pending("Android bundle", "No bundle inspected.")
    if args.relay_output:
        package_file = args.relay_output / "package.json"
        if package_file.is_file():
            packages = json.loads(package_file.read_text()).get("dependencies", {})
            check("Relay output manifest excludes known build-only advisory packages", not {"node-forge", "braces"} & set(packages))
        else:
            check("Relay output package exists", False)
    if args.relay_release:
        try:
            verification = subprocess.run(
                ["node", str(ROOT / "scripts/prepare-relay-release.mjs"), "--verify", str(args.relay_release)],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False,
            )
            check("Prepared relay release allowlist, checksums and Node major", verification.returncode == 0)
        except (OSError, subprocess.TimeoutExpired):
            check("Prepared relay release verification available", False)
    pending("Production configuration", "Templates do not attest deployed proxy/CDN/APM, environment, retention or backups.")
    pending("Device and independent review", "Physical TEE/StrongBox/Secure Enclave, recovery and independent protocol review required.")
    print(json.dumps(results, indent=2))
    return 1 if any(item["status"] == "FAIL" for item in results) else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("android-manifest", "android-bundle", "ios-app", "relay-output", "relay-release"):
        parser.add_argument("--" + option, type=Path)
    try:
        sys.exit(run(parser.parse_args()))
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile, subprocess.CalledProcessError):
        print("Security check could not inspect an input; no release approval.", file=sys.stderr)
        sys.exit(2)
