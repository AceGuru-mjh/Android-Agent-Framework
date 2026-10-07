"""Extract the payload of a .deb (an ar archive) and list its contents.

Usage: python unpack_deb.py <deb-path> <out-dir>

Paths are passed as argv so this file stays pure ASCII: Python decodes
command-line arguments as Unicode, whereas literals inside a .py file get
decoded with the locale encoding (and mangle non-ASCII on Windows).
"""

import io
import os
import sys
import tarfile


def read_ar(path):
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise SystemExit("not an ar archive: %r" % magic)
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode("ascii", "replace").strip()
            size_field = hdr[48:58].decode("ascii", "replace").strip()
            try:
                size = int(size_field)
            except ValueError:
                break
            data = f.read(size)
            if size % 2:
                f.read(1)
            yield name, data


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: unpack_deb.py <deb> <outdir>")
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)

    for name, data in read_ar(src):
        print("member: %s (%d bytes)" % (name, len(data)))
        if not name.startswith("data.tar"):
            continue
        if name.endswith(".xz"):
            mode = "r:xz"
        elif name.endswith(".gz"):
            mode = "r:gz"
        elif name.endswith(".zst"):
            print("  (zstd payload not supported by this script)")
            continue
        else:
            mode = "r:*"
        with tarfile.open(fileobj=io.BytesIO(data), mode=mode) as tf:
            for m in tf.getmembers():
                print("   %-40s %8d %s" % (m.name, m.size, m.type))
            tf.extractall(out)
    print("extracted to %s" % out)


if __name__ == "__main__":
    main()
