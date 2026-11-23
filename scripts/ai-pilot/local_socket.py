"""Check ownership of a loopback listener without requiring netlink privileges."""

import os
from pathlib import Path
import sys


def owns_listener(pid, port):
    if not pid:
        return False
    address = f"0100007F:{port:04X}"
    try:
        sockets = set()
        for fd in Path(f"/proc/{pid}/fd").iterdir():
            try:
                sockets.add(os.readlink(fd))
            except FileNotFoundError:
                continue
        for line in Path(f"/proc/{pid}/net/tcp").read_text().splitlines()[1:]:
            fields = line.split()
            if fields[1] == address and fields[3] == "0A" and f"socket:[{fields[9]}]" in sockets:
                return True
    except OSError:
        return False
    return False


if __name__ == "__main__":
    sys.exit(not owns_listener(int(sys.argv[1]), int(sys.argv[2])))
