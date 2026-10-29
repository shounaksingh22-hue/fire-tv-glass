#!/usr/bin/env python3
"""Tiny ADB client for the TV, built on adb-shell (works around macOS Local
Network privacy blocking the stock adb binary).

  tv.py shell '<command>'          run one shell command, print output
  tv.py screencap out.png          grab a screenshot from the TV
  tv.py push local /remote/path    copy a file (base64 chunks, md5 verified)
  tv.py key KEY [KEY ...]          inject key events (DPAD_DOWN, HOME, ...)

TV address: TV_IP env var, or the first line of .tv-ip in the repo root.
TV_PORT defaults to 5555.
"""
import base64
import hashlib
import os
import sys
import time

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

IP_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".tv-ip")


def tv_ip():
    ip = os.environ.get("TV_IP", "").strip()
    if not ip and os.path.exists(IP_FILE):
        with open(IP_FILE) as f:
            ip = f.readline().strip()
    if not ip:
        sys.exit("No TV address yet. Run ./setup.sh first.")
    return ip


PORT = int(os.environ.get("TV_PORT", "5555"))
KEY = os.path.expanduser("~/.android/adbkey")


def connect():
    if not os.path.exists(KEY):
        os.makedirs(os.path.dirname(KEY), exist_ok=True)
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(tv_ip(), PORT, default_transport_timeout_s=30.0)
    # auth_timeout_s gives you time to accept the prompt on the TV
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=60.0)
    return dev


def shell(dev, cmd, timeout=120):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout)


def push(dev, local, remote, chunk=48 * 1024):
    data = open(local, "rb").read()
    want = hashlib.md5(data).hexdigest()
    tmp = remote + ".b64"
    shell(dev, f"rm -f '{tmp}'")
    b64 = base64.b64encode(data).decode()
    for i in range(0, len(b64), chunk):
        shell(dev, f"echo -n '{b64[i:i + chunk]}' >> '{tmp}'")
    shell(dev, f"base64 -d '{tmp}' > '{remote}' && rm -f '{tmp}'")
    got = shell(dev, f"md5sum '{remote}'").split()[0]
    if got != want:
        sys.exit(f"md5 mismatch: {got} != {want}")
    print(f"pushed {len(data)} bytes, md5 {got} OK")


def screencap(dev, out):
    shell(dev, "screencap -p /sdcard/_cap.png")
    b64 = shell(dev, "base64 /sdcard/_cap.png; rm /sdcard/_cap.png")
    with open(out, "wb") as f:
        f.write(base64.b64decode("".join(b64.split())))
    print(out)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    dev = connect()
    try:
        if cmd == "shell":
            out = shell(dev, " ".join(args))
            sys.stdout.write(out if out.endswith("\n") or not out else out + "\n")
        elif cmd == "screencap":
            screencap(dev, args[0])
        elif cmd == "push":
            push(dev, args[0], args[1])
        elif cmd == "key":
            for k in args:
                if k.startswith("sleep:"):
                    time.sleep(float(k[6:]))
                    continue
                code = k if k.isdigit() else "KEYCODE_" + k.upper()
                shell(dev, f"input keyevent {code}")
                time.sleep(0.35)
        else:
            sys.exit(__doc__)
    finally:
        dev.close()


if __name__ == "__main__":
    main()
