#!/usr/bin/env python3
"""
Downloads and stages every binary that gets embedded in the APK.
Baixa e prepara todos os binarios embutidos no APK.

None of these files are committed to the repository (they total hundreds of MB and GitHub
rejects files over 100 MB), so run this once before building:
Nada disso vai para o Git (sao centenas de MB e o GitHub rejeita arquivos > 100 MB):

    python scripts/fetch_assets.py              # both architectures / as duas
    python scripts/fetch_assets.py --abi arm64  # phones and tablets / celulares e tablets
    python scripts/fetch_assets.py --abi x64    # emulator / emulador

Needs Python 3.9+ and internet. Python 3.14+ (or `pip install zstandard`) is required to
read Ubuntu's .zst packages.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import re
import sys
import tarfile
import time
import urllib.request
from pathlib import Path
from typing import Callable, Union

# ---------------------------------------------------------------- pinned versions
UBUNTU_BASE = "24.04.5"   # https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/
CODE_SERVER = "4.145.2"   # https://github.com/coder/code-server/releases
NODE = "26.13.1"          # https://nodejs.org/dist/  (LTS line)
GH_CLI = "2.103.3"        # https://github.com/cli/cli/releases
CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem"
TERMUX_POOL = "https://packages.termux.dev/apt/termux-main/pool/main"

# flavor = Gradle product flavor; abi = jniLibs folder; the rest are each vendor's arch name
ABIS = {
    "arm64": dict(flavor="arm64", abi="arm64-v8a", ubuntu="arm64", cs="arm64", node="arm64",
                  gh="arm64", termux="aarch64", deb_arch="arm64",
                  gcc_pool="https://ports.ubuntu.com/ubuntu-ports/pool/main/g/gcc-14/"),
    "x64":   dict(flavor="x64", abi="x86_64", ubuntu="amd64", cs="amd64", node="x64",
                  gh="amd64", termux="x86_64", deb_arch="amd64",
                  gcc_pool="https://archive.ubuntu.com/ubuntu/pool/main/g/gcc-14/"),
}

UA = {"User-Agent": "codedroid-fetch-assets/1.0"}
Match = Union[str, Callable[[str], bool]]


# ---------------------------------------------------------------------- helpers
TIMEOUT = 120   # seconds; some mirrors are slow
RETRIES = 5


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


def latest_deb(pool_dir_url: str, package: str, arch: str, contains: str = "", fallback: str = "") -> str:
    """
    Newest <package>_<version>_<arch>.deb listed in an apt pool directory. If the listing cannot
    be fetched or parsed, `fallback` (a known-good "<version>") is used instead.
    """
    try:
        html = http_get(pool_dir_url).decode("utf-8", "replace")
        names = sorted(set(re.findall(rf'href="({re.escape(package)}_[^"]*_{arch}\.deb)"', html)))
        names = [n for n in names if contains in n]
        if names:
            return names[-1]
    except RuntimeError as e:
        print(f"  ! could not list {pool_dir_url}: {e}")
    if not fallback:
        raise RuntimeError(f"no {package} for {arch} at {pool_dir_url}")
    print(f"  ! using pinned {package} {fallback}")
    return f"{package}_{fallback}_{arch}.deb"


def deb_data_tar(deb: bytes) -> tarfile.TarFile:
    """Opens the data.tar.* member of a .deb (an `ar` archive) as a TarFile."""
    if not deb.startswith(b"!<arch>\n"):
        raise ValueError("not a .deb file")
    pos = 8
    while pos + 60 <= len(deb):
        name = deb[pos:pos + 16].decode().strip()
        size = int(deb[pos + 48:pos + 58].decode().strip())
        body = deb[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if not name.startswith("data.tar"):
            continue
        if name.endswith(".xz"):
            return tarfile.open(fileobj=io.BytesIO(body), mode="r:xz")
        if name.endswith(".gz"):
            return tarfile.open(fileobj=io.BytesIO(body), mode="r:gz")
        if name.endswith(".zst"):
            try:
                from compression import zstd  # Python 3.14+
                return tarfile.open(fileobj=io.BytesIO(zstd.decompress(body)), mode="r:")
            except ImportError:
                try:
                    import zstandard  # type: ignore
                    raw = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(body)).read()
                    return tarfile.open(fileobj=io.BytesIO(raw), mode="r:")
                except ImportError:
                    sys.exit("Reading .zst packages needs Python 3.14+ or `pip install zstandard`.")
        raise ValueError(f"unsupported data member: {name}")
    raise ValueError("no data.tar.* in .deb")


def deb_extract(deb: bytes, match: Match) -> bytes:
    """Bytes of the first regular file in the .deb whose path ends with `match` (or satisfies it)."""
    test = (lambda n: n.endswith(match)) if isinstance(match, str) else match
    with deb_data_tar(deb) as tf:
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
def stage_big_assets(root: Path, a: dict, force: bool) -> None:
    assets = root / "app" / "src" / a["flavor"] / "assets"
    download(f"https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
             f"ubuntu-base-{UBUNTU_BASE}-base-{a['ubuntu']}.tar.gz", assets / "rootfs.bin", force)
    download(f"https://github.com/coder/code-server/releases/download/v{CODE_SERVER}/"
             f"code-server-{CODE_SERVER}-linux-{a['cs']}.tar.gz", assets / "code-server.bin", force)
    download(f"https://nodejs.org/dist/v{NODE}/node-v{NODE}-linux-{a['node']}.tar.gz", assets / "node.bin", force)
    download(f"https://github.com/cli/cli/releases/download/v{GH_CLI}/gh_{GH_CLI}_linux_{a['gh']}.tar.gz",
             assets / "gh.bin", force)


def stage_libatomic(root: Path, a: dict, force: bool) -> None:
    """Node's official build needs libatomic.so.1, which minimal Ubuntu does not ship."""
    dest = root / "app" / "src" / a["flavor"] / "assets" / "libatomic.so.1"
    if dest.exists() and not force:
        print(f"  = {dest.name} (already there)")
        return
    name = latest_deb(a["gcc_pool"], "libatomic1", a["deb_arch"], contains="24.04",
                      fallback="14.2.0-4ubuntu2~24.04.1")
    print(f"  v {name}")
    so = deb_extract(http_get(a["gcc_pool"] + name), lambda n: re.search(r"/libatomic\.so\.1\.\d+\.\d+$", n) is not None)
    write(dest, so)


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
        name = latest_deb(f"{TERMUX_POOL}/{pool}/", pkg, t, fallback=fallback)
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


def stage_ca_bundle(root: Path, force: bool) -> None:
    download(CA_BUNDLE_URL, root / "app" / "src" / "main" / "assets" / "ca-certificates.crt", force)


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

    print("\n[shared] CA certificates")
    stage_ca_bundle(root, args.force)
    for name in targets:
        a = ABIS[name]
        print(f"\n[{name}] proot libraries")
        stage_proot(root, a, args.force)
        print(f"[{name}] libatomic")
        stage_libatomic(root, a, args.force)
        print(f"[{name}] rootfs, code-server, Node.js, GitHub CLI")
        stage_big_assets(root, a, args.force)

    print("\nStaged files:")
    for p in sorted((root / "app" / "src").rglob("*")):
        if p.is_file() and ("assets" in p.parts or "jniLibs" in p.parts):
            print(f"  {p.relative_to(root)}  {p.stat().st_size / 1e6:8.2f} MB  sha256:{sha256(p)}")
    print("\nDone. Next: ./gradlew :app:assembleArm64Debug   (see README)")


if __name__ == "__main__":
    main()
