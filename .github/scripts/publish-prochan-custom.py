"""Publish only the custom-signed ProChan extension into MohammedTeir/extensions."""
import gzip
import hashlib
import json
import os
import subprocess
from pathlib import Path

from google.protobuf import json_format
import index_pb2

REPOSITORY = "MohammedTeir/extensions"
FINGERPRINT = "0093bc9bca53a3d10188bb3b7c51f039c57aea57a617b8d2a374145454d19134"
SOURCE_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = Path.cwd()
INFO = SOURCE_ROOT / "src/ar/mangapro/build/keiyoushi-source-info.json"
APK_DIR = SOURCE_ROOT / "src/ar/mangapro/build/outputs/apk/release"


def run(*args):
    subprocess.run(args, check=True)


def main():
    if not os.environ.get("GH_TOKEN"):
        raise RuntimeError("EXTENSIONS_REPO_TOKEN is required")
    info = json.loads(INFO.read_text(encoding="utf-8"))
    if info["name"] != "ProChan":
        raise ValueError("Unexpected source-info name")
    apks = list(APK_DIR.glob("*.apk"))
    if len(apks) != 1:
        raise RuntimeError(f"Expected one release APK, found {len(apks)}")
    apk = apks[0]
    expected_package = "eu.kanade.tachiyomi.extension.ar.mangapro"
    if info["packageName"] != expected_package:
        raise ValueError(f"Unexpected package name: {info['packageName']}")

    tag = f"prochan-v{info['versionCode']}-{hashlib.sha256(apk.read_bytes()).hexdigest()[:12]}"
    url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{apk.name}"
    icon = "https://cdn.jsdelivr.net/gh/MohammedTeir/extensions-source@main/src/ar/mangapro/res/mipmap-xhdpi/ic_launcher.png"
    if not (SOURCE_ROOT / "src/ar/mangapro/res/mipmap-xhdpi/ic_launcher.png").exists():
        icon = "https://cdn.jsdelivr.net/gh/MohammedTeir/extensions-source@main/core/src/main/res/mipmap-xhdpi/ic_launcher.png"

    index = index_pb2.Index(
        name="MohammedTeir Extensions",
        badgeLabel="MT",
        signingKey=FINGERPRINT,
        contact=index_pb2.Contact(website="https://github.com/MohammedTeir/extensions"),
        extensionList=index_pb2.ExtensionList(extensions=[
            index_pb2.Extension(
                name=info["name"],
                packageName=info["packageName"],
                resources=index_pb2.Resources(apkUrl=url, iconUrl=icon),
                extensionLib=info["extensionLib"],
                versionCode=info["versionCode"],
                versionName=info["versionName"],
                contentWarning=info["contentWarning"],
                sources=[
                    index_pb2.Source(
                        id=int(s["id"]), name=s["name"], language=s["lang"],
                        homeUrl=s["baseUrl"], mirrorUrls=s.get("mirrorUrls", []),
                    ) for s in info["sources"]
                ],
            )
        ]),
    )

    # Never overwrite an existing custom feed with a different signing identity.
    descriptor = REPO_ROOT / "repo.json"
    current = json.loads(descriptor.read_text(encoding="utf-8"))
    existing_key = current.get("meta", {}).get("signingKeyFingerprint")
    if existing_key not in (FINGERPRINT, "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"):
        raise RuntimeError("Repository has an unexpected signing fingerprint")

    # Create and publish the release before updating the index.
    check = subprocess.run(["gh", "release", "view", tag, "--repo", REPOSITORY],
                           capture_output=True, text=True)
    if check.returncode != 0:
        run("gh", "release", "create", tag, str(apk), "--repo", REPOSITORY,
            "--title", f"ProChan {info['versionName']}",
            "--notes", f"Custom-signed ProChan, version code {info['versionCode']}.")
    else:
        # The immutable tag includes APK SHA; do not replace an existing asset.
        print(f"Release {tag} already exists")

    (REPO_ROOT / "index.json").write_text(
        json_format.MessageToJson(index, preserving_proto_field_name=True) + "\n",
        encoding="utf-8",
    )
    (REPO_ROOT / "index.pb").write_bytes(
        gzip.compress(index.SerializeToString(deterministic=True), mtime=0)
    )
    descriptor.write_text(json.dumps({
        "index_v2": f"https://github.com/{REPOSITORY}/raw/repo/index.pb",
        "meta": {
            "name": "MohammedTeir Extensions",
            "website": f"https://github.com/{REPOSITORY}",
            "signingKeyFingerprint": FINGERPRINT,
        },
    }, indent=2) + "\n", encoding="utf-8")
    (REPO_ROOT / "index.html").write_text(
        '<!DOCTYPE html><html><head><meta charset="utf-8"><title>Custom extensions</title></head>'
        f'<body><a href="{url}">ProChan</a></body></html>\n', encoding="utf-8"
    )
    (REPO_ROOT / "release-assets.json").write_text(
        json.dumps({info["packageName"]: {"apk": {
            "name": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()
        }}}, indent=2) + "\n", encoding="utf-8"
    )
    legacy_index = [{
        "name": info["name"],
        "pkg": info["packageName"],
        "apk": url,
        "lang": "ar",
        "code": int(info["versionCode"]),
        "version": info["versionName"],
        "nsfw": 1 if info["contentWarning"] == "CONTENT_WARNING_NSFW" else 0,
        "sources": [{
            "name": s["name"],
            "lang": s["lang"],
            "id": str(s["id"]),
            "baseUrl": s["baseUrl"],
        } for s in info["sources"]],
    }]
    (REPO_ROOT / "index.min.json").write_text(
        json.dumps(legacy_index, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Published ProChan metadata: {tag}")


if __name__ == "__main__":
    main()
