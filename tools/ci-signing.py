"""Load optional repository secrets into a private, temporary Gradle signing file."""
from pathlib import Path
import base64
import os
import shutil
import sys


def property_value(value):
    result = []
    for char in value:
        if char in "\\\n\r\t ":
            result.append({"\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t", " ": "\\ "}[char])
        elif ord(char) > 126:
            encoded = char.encode("utf-16-be")
            result.extend("\\u" + encoded[i:i + 2].hex() for i in range(0, len(encoded), 2))
        else:
            result.append(char)
    return "".join(result)


def main():
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve()
    folder = temporary / "shortcuts-signing"
    if sys.argv[1:] == ["--cleanup"]:
        if folder.is_dir():
            if folder.is_symlink():
                raise SystemExit("Unexpected signing directory link")
            shutil.rmtree(folder)
        return
    names = ["SHORTCUTS_KEYSTORE_BASE64", "SHORTCUTS_STORE_PASSWORD", "SHORTCUTS_KEY_ALIAS", "SHORTCUTS_KEY_PASSWORD"]
    values = [os.environ.get(name, "") for name in names]
    if not any(values):
        print("No release signing secrets configured; building an unsigned Release APK.")
        return
    if not all(values):
        raise SystemExit("Configure all four Shortcuts signing secrets, or leave all unset.")
    folder.mkdir(mode=0o700, exist_ok=False)
    keystore = folder / "release.p12"
    keystore.write_bytes(base64.b64decode("".join(values[0].split()), validate=True))
    keystore.chmod(0o600)
    properties = folder / "signing.properties"
    pairs = {"storeFile": keystore.as_posix(), "storeType": "PKCS12", "storePassword": values[1], "keyAlias": values[2], "keyPassword": values[3]}
    properties.write_text("".join(f"{key}={property_value(value)}\n" for key, value in pairs.items()), encoding="ascii")
    properties.chmod(0o600)
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write(f"SHORTCUTS_SIGNING_PROPERTIES={properties}\n")
    print("Private release signing configured for this trusted build.")


if __name__ == "__main__":
    main()
