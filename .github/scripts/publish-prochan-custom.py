"""Publish custom-signed ProChan and Comix to MohammedTeir/extensions."""
import gzip
import hashlib
import html
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
EXTENSIONS = (
    ("ar/mangapro", "ProChan", "eu.kanade.tachiyomi.extension.ar.mangapro", "ar"),
    ("en/comix", "Comix", "eu.kanade.tachiyomi.extension.en.comix", "en"),
)


def run(*args):
    subprocess.run(args, check=True)


def main():
    if not os.environ.get("GH_TOKEN"):
        raise RuntimeError("EXTENSIONS_REPO_TOKEN is required")

    descriptor = REPO_ROOT / "repo.json"
    current = json.loads(descriptor.read_text(encoding="utf-8"))
    existing_key = current.get("meta", {}).get("signingKeyFingerprint")
    if existing_key not in (FINGERPRINT, "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"):
        raise RuntimeError("Repository has an unexpected signing fingerprint")

    releases = []
    for path, expected_name, expected_package, lang in EXTENSIONS:
        root = SOURCE_ROOT / "src" / path
        info = json.loads((root / "build/keiyoushi-source-info.json").read_text(encoding="utf-8"))
        if info["name"] != expected_name or info["packageName"] != expected_package:
            raise ValueError(f"Unexpected source metadata for {path}: {info['name']}, {info['packageName']}")
        apks = list((root / "build/outputs/apk/release").glob("*.apk"))
        if len(apks) != 1:
            raise RuntimeError(f"Expected one release APK for {path}, found {len(apks)}")
        apk = apks[0]
        sha = hashlib.sha256(apk.read_bytes()).hexdigest()
        tag = f"{expected_name.lower()}-v{info['versionCode']}-{sha[:12]}"
        url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{apk.name}"
        icon_path = root / "res/mipmap-xhdpi/ic_launcher.png"
        icon = (
            f"https://cdn.jsdelivr.net/gh/MohammedTeir/extensions-source@main/src/{path}/res/mipmap-xhdpi/ic_launcher.png"
            if icon_path.exists()
            else "https://cdn.jsdelivr.net/gh/MohammedTeir/extensions-source@main/core/src/main/res/mipmap-xhdpi/ic_launcher.png"
        )
        releases.append((info, apk, sha, tag, url, icon, lang))

    # Publish both release assets before updating the catalog.
    for info, apk, sha, tag, url, icon, lang in releases:
        check = subprocess.run(
            ["gh", "release", "view", tag, "--repo", REPOSITORY],
            capture_output=True, text=True,
        )
        if check.returncode != 0:
            run(
                "gh", "release", "create", tag, str(apk), "--repo", REPOSITORY,
                "--title", f"{info['name']} {info['versionName']}",
                "--notes", f"Custom-signed {info['name']}, version code {info['versionCode']}.",
            )
        else:
            print(f"Release {tag} already exists")

    extensions = []
    legacy_index = []
    assets = {}
    links = []
    for info, apk, sha, tag, url, icon, lang in releases:
        extensions.append(index_pb2.Extension(
            name=info["name"],
            packageName=info["packageName"],
            resources=index_pb2.Resources(apkUrl=url, iconUrl=icon),
            extensionLib=info["extensionLib"],
            versionCode=info["versionCode"],
            versionName=info["versionName"],
            contentWarning=info["contentWarning"],
            sources=[
                index_pb2.Source(
                    id=int(source["id"]), name=source["name"], language=source["lang"],
                    homeUrl=source["baseUrl"], mirrorUrls=source.get("mirrorUrls", []),
                ) for source in info["sources"]
            ],
        ))
        legacy_index.append({
            "name": info["name"],
            "pkg": info["packageName"],
            "apk": url,
            "lang": lang,
            "code": int(info["versionCode"]),
            "version": info["versionName"],
            "nsfw": 1 if info["contentWarning"] == "CONTENT_WARNING_NSFW" else 0,
            "sources": [{
                "name": source["name"], "lang": source["lang"],
                "id": str(source["id"]), "baseUrl": source["baseUrl"],
            } for source in info["sources"]],
        })
        assets[info["packageName"]] = {"apk": {"name": apk.name, "sha256": sha}}
        links.append(f'<li><a href="{html.escape(url, quote=True)}">{html.escape(info["name"])}</a></li>')

    index = index_pb2.Index(
        name="MohammedTeir Extensions",
        badgeLabel="MT",
        signingKey=FINGERPRINT,
        contact=index_pb2.Contact(website=f"https://github.com/{REPOSITORY}"),
        extensionList=index_pb2.ExtensionList(extensions=extensions),
    )
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
        f'<body><ul>{"".join(links)}</ul></body></html>\n', encoding="utf-8",
    )
    (REPO_ROOT / "release-assets.json").write_text(
        json.dumps(assets, indent=2) + "\n", encoding="utf-8",
    )
    (REPO_ROOT / "index.min.json").write_text(
        json.dumps(legacy_index, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print("Published custom metadata for: " + ", ".join(info["name"] for info, *_ in releases))


if __name__ == "__main__":
    main()
