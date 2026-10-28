"""Collect only distributable APKs; signing files never enter the artifact directory."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
destination = root / "dist"
destination.mkdir(exist_ok=True)
checksums = []
for variant in ["debug", "release"]:
    directory = root / "app/build/outputs/apk" / variant
    metadata = json.loads((directory / "output-metadata.json").read_text())
    element, = metadata["elements"]
    apk = directory / element["outputFile"]
    unsigned = "unsigned" in apk.name
    if not unsigned:
        signer = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/apksigner"
        if os.name == "nt":
            signer = signer.with_suffix(".bat")
        subprocess.run([str(signer), "verify", str(apk)], check=True)
    name = f"Shortcuts-{element['versionName']}-{variant}{'-unsigned' if unsigned else ''}.apk"
    shutil.copyfile(apk, destination / name)
    checksums.append(f"{hashlib.sha256(apk.read_bytes()).hexdigest()}  {name}\n")
(destination / "sha256sums.txt").write_text("".join(checksums), encoding="utf-8")
print("Collected Debug and Release APKs with SHA-256 checksums.")
