#!/usr/bin/env python3
"""
Downloads and stages every binary/asset that gets embedded in the APK.
Baixa e prepara todos os binarios/assets embutidos no APK.

None of these files are committed to the repository (they total hundreds of MB and GitHub
rejects files over 100 MB), so run this once before building:
Nada disso vai para o Git (sao centenas de MB e o GitHub rejeita arquivos > 100 MB):

    python scripts/fetch_assets.py              # both architectures / as duas
    python scripts/fetch_assets.py --abi arm64  # phones and tablets / celulares e tablets
    python scripts/fetch_assets.py --abi x64    # emulator / emulador

Needs Python 3.9+ and internet.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import re
import shutil
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- pinned versions
UBUNTU_BASE = "24.04.5"   # https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/
JDK = "25.0.5"            # Temurin LTS, https://adoptium.net/ (build 8)
JDK_BUILD = "8"
MAVEN = "3.9.16"          # https://archive.apache.org/dist/maven/maven-3/
GRADLE = "9.9.1"          # https://services.gradle.org/distributions/
JDTLS = "jdt-language-server-1.64.0-202611091900.tar.gz"  # https://download.eclipse.org/jdtls/snapshots/
MONACO = "0.57.0"         # https://www.npmjs.com/package/monaco-editor
CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem"
TERMUX_POOL = "https://packages.termux.dev/apt/termux-main/pool/main"

# flavor = Gradle product flavor; abi = jniLibs folder; the rest are each vendor's arch name
ABIS = {
    "arm64": dict(flavor="arm64", abi="arm64-v8a", ubuntu="arm64", jdk="aarch64", termux="aarch64"),
    "x64":   dict(flavor="x64", abi="x86_64", ubuntu="amd64", jdk="x64", termux="x86_64"),
}

UA = {"User-Agent": "codedroid-java-fetch-assets/1.0"}
TIMEOUT = 120   # seconds; some mirrors are slow
RETRIES = 5


# ---------------------------------------------------------------------- helpers
def http_get(url: str, retries: int = RETRIES) -> bytes:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=TIMEOUT) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"download failed: {url}: {last}")


def download(url: str, dest: Path, force: bool) -> None:
    if dest.exists() and dest.stat().st_size > 0 and not force:
        print(f"  = {dest.name} (already there)")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    print(f"  v {dest.name}  <- {url}")
    last: Exception | None = None
    for attempt in range(RETRIES):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=TIMEOUT) as r, open(tmp, "wb") as f:
                total = int(r.headers.get("Content-Length") or 0)
                done = 0
                while chunk := r.read(1 << 20):
                    f.write(chunk)
                    done += len(chunk)
                    if total:
                        print(f"\r    {done / 1e6:7.1f} / {total / 1e6:.1f} MB", end="", flush=True)
            print()
            tmp.replace(dest)
            return
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"download failed: {url}: {last}")


def latest_deb(pool_dir_url: str, package: str, arch: str, fallback: str) -> str:
    """Newest <package>_<version>_<arch>.deb listed in an apt pool dir; `fallback` is a known-good version."""
    try:
        html = http_get(pool_dir_url).decode("utf-8", "replace")
        names = sorted(set(re.findall(rf'href="({re.escape(package)}_[^"]*_{arch}\.deb)"', html)))
        if names:
            return names[-1]
    except RuntimeError as e:
        print(f"  ! could not list {pool_dir_url}: {e}")
    print(f"  ! using pinned {package} {fallback}")
    return f"{package}_{fallback}_{arch}.deb"


def deb_extract(deb: bytes, match) -> bytes:
    """Bytes of the first regular file in the .deb (data.tar.xz) whose path ends with / satisfies `match`."""
    if not deb.startswith(b"!<arch>\n"):
        raise ValueError("not a .deb file")
    test = (lambda n: n.endswith(match)) if isinstance(match, str) else match
    pos = 8
    while pos + 60 <= len(deb):
        name = deb[pos:pos + 16].decode().strip().rstrip("/")  # GNU ar termina o nome com "/"
        size = int(deb[pos + 48:pos + 58].decode().strip())
        body = deb[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if not name.startswith("data.tar"):
            continue
        if not name.endswith((".xz", ".gz")):
            sys.exit(f"Unsupported package compression: {name}")
        with tarfile.open(fileobj=io.BytesIO(body), mode="r:xz" if name.endswith(".xz") else "r:gz") as tf:
            for m in tf.getmembers():
                if m.isfile() and test(m.name):
                    f = tf.extractfile(m)
                    assert f is not None
                    return f.read()
    raise FileNotFoundError(str(match))


def write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print(f"  + {path.name}  ({len(data) / 1024:.0f} KB)")


# ------------------------------------------------------------------------ stages
def stage_arch_assets(root: Path, a: dict, force: bool) -> None:
    assets = root / "app" / "src" / a["flavor"] / "assets"
    download(f"https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
             f"ubuntu-base-{UBUNTU_BASE}-base-{a['ubuntu']}.tar.gz", assets / "rootfs.bin", force)
    download(f"https://github.com/adoptium/temurin25-binaries/releases/download/jdk-{JDK}%2B{JDK_BUILD}/"
             f"OpenJDK25U-jdk_{a['jdk']}_linux_hotspot_{JDK}_{JDK_BUILD}.tar.gz", assets / "jdk.bin", force)


def zip_to_targz(zip_path: Path, out_path: Path) -> None:
    """Repacks a .zip as .tar.gz (keeping unix modes), the format the app's installer reads."""
    print(f"  repacking {zip_path.name} -> {out_path.name}")
    tmp = out_path.with_name(out_path.name + ".part")
    with zipfile.ZipFile(zip_path) as zf, gzip.GzipFile(tmp, "wb", compresslevel=6) as gz, \
            tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as tf:
        for info in zf.infolist():
            ti = tarfile.TarInfo(info.filename.rstrip("/"))
            mode = (info.external_attr >> 16) & 0o7777
            ti.mtime = time.mktime(info.date_time + (0, 0, -1))
            if info.is_dir():
                ti.type = tarfile.DIRTYPE
                ti.mode = mode or 0o755
                tf.addfile(ti)
            else:
                data = zf.read(info)
                ti.size = len(data)
                ti.mode = mode or 0o644
                tf.addfile(ti, io.BytesIO(data))
    tmp.replace(out_path)


def stage_shared_assets(root: Path, force: bool) -> None:
    assets = root / "app" / "src" / "main" / "assets"
    download(f"https://archive.apache.org/dist/maven/maven-3/{MAVEN}/binaries/apache-maven-{MAVEN}-bin.tar.gz",
             assets / "maven.bin", force)
    download(f"https://download.eclipse.org/jdtls/snapshots/{JDTLS}", assets / "jdtls.bin", force)

    gradle = assets / "gradle.bin"
    if gradle.exists() and not force:
        print(f"  = {gradle.name} (already there)")
    else:
        zpath = root / "app" / "build" / "tmp-fetch" / f"gradle-{GRADLE}-bin.zip"
        download(f"https://services.gradle.org/distributions/gradle-{GRADLE}-bin.zip", zpath, force)
        zip_to_targz(zpath, gradle)
        shutil.rmtree(zpath.parent, ignore_errors=True)

    download(CA_BUNDLE_URL, assets / "ca-certificates.crt", force)


def stage_monaco(root: Path, force: bool) -> None:
    """Vendors the Monaco editor (AMD build, no bundler needed). Language services are not needed."""
    dest = root / "app" / "src" / "main" / "assets" / "web" / "monaco"
    if (dest / "vs" / "loader.js").exists() and not force:
        print("  = monaco (already there)")
        return
    shutil.rmtree(dest, ignore_errors=True)
    print(f"  v monaco-editor {MONACO}")
    data = http_get(f"https://registry.npmjs.org/monaco-editor/-/monaco-editor-{MONACO}.tgz")
    count = 0
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf:
        for m in tf.getmembers():
            if not m.isfile():
                continue
            n = m.name
            if n == "package/LICENSE":
                target = dest / "LICENSE"
            elif n.startswith("package/min/vs/") and not n.endswith(".map") and "/language/" not in n:
                target = dest / n[len("package/min/"):]
            else:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            f = tf.extractfile(m)
            assert f is not None
            target.write_bytes(f.read())
            count += 1
    # Workers are ES modules with hashed names. Give the editor worker a stable name, and drop the
    # language-service workers (TypeScript/HTML/JSON/CSS, ~9 MB): the IDE never creates them.
    assets = dest / "vs" / "assets"
    workers = sorted(assets.glob("editor.worker-*.js"))
    if not workers:
        sys.exit("Monaco layout changed: editor worker not found; update stage_monaco().")
    shutil.copyfile(workers[0], assets / "editor.worker.js")
    for pattern in ("ts.worker-*.js", "html.worker-*.js", "json.worker-*.js", "css.worker-*.js"):
        for f in assets.glob(pattern):
            f.unlink()
    print(f"  + {count} files ({sum(p.stat().st_size for p in dest.rglob('*') if p.is_file()) / 1e6:.1f} MB)")


def stage_proot(root: Path, a: dict, force: bool) -> None:
    """
    proot (from Termux) + its loader and two small libraries, named lib*.so so Gradle packs them
    into nativeLibraryDir, the one place Android lets an app exec from.
    """
    out = root / "app" / "src" / a["flavor"] / "jniLibs" / a["abi"]
    wanted = ["libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so"]
    if all((out / w).exists() for w in wanted) and not force:
        print("  = proot libraries (already there)")
        return
    t = a["termux"]

    def deb(pool: str, pkg: str, fallback: str) -> bytes:
        name = latest_deb(f"{TERMUX_POOL}/{pool}/", pkg, t, fallback)
        print(f"  v {name}")
        return http_get(f"{TERMUX_POOL}/{pool}/{name}")

    proot_deb = deb("p/proot", "proot", "5.1.107.102")
    proot = deb_extract(proot_deb, "usr/bin/proot")
    loader = deb_extract(proot_deb, "usr/libexec/proot/loader")
    talloc = deb_extract(deb("libt/libtalloc", "libtalloc", "2.5.0"),
                         lambda n: re.search(r"/libtalloc\.so\.\d+\.\d+\.\d+$", n) is not None)
    shmem = deb_extract(deb("liba/libandroid-shmem", "libandroid-shmem", "0.7"), "usr/lib/libandroid-shmem.so")

    # proot is linked against "libtalloc.so.2", but jniLibs can only hold lib*.so files, so the
    # DT_NEEDED string is renamed in place (same length, NUL padded).
    old, new = b"libtalloc.so.2\0", b"libtalloc.so\0\0\0"
    if proot.count(old) != 1:
        sys.exit("Unexpected proot binary layout: update the DT_NEEDED patch in stage_proot().")
    proot = proot.replace(old, new)

    for fname, data in (("libproot.so", proot), ("libproot-loader.so", loader),
                        ("libtalloc.so", talloc), ("libandroid-shmem.so", shmem)):
        write(out / fname, data)


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()[:16]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--abi", choices=["arm64", "x64", "all"], default="all")
    ap.add_argument("--dest", type=Path, default=Path(__file__).resolve().parent.parent,
                    help="project root (default: this repository)")
    ap.add_argument("--force", action="store_true", help="download again even if the file exists")
    args = ap.parse_args()

    targets = list(ABIS) if args.abi == "all" else [args.abi]
    root: Path = args.dest
    print(f"Project root: {root}")

    print("\n[shared] Maven, Gradle, JDT LS, CA certificates")
    stage_shared_assets(root, args.force)
    print("\n[shared] Monaco editor")
    stage_monaco(root, args.force)
    for name in targets:
        a = ABIS[name]
        print(f"\n[{name}] proot libraries")
        stage_proot(root, a, args.force)
        print(f"[{name}] Ubuntu base + JDK {JDK}")
        stage_arch_assets(root, a, args.force)

    print("\nStaged files (big ones):")
    for p in sorted((root / "app" / "src").rglob("*")):
        if p.is_file() and ("assets" in p.parts or "jniLibs" in p.parts) and "monaco" not in p.parts:
            print(f"  {p.relative_to(root)}  {p.stat().st_size / 1e6:8.2f} MB  sha256:{sha256(p)}")
    print("\nDone. Next: ./gradlew :app:assembleArm64Debug   (see README)")


if __name__ == "__main__":
    main()
