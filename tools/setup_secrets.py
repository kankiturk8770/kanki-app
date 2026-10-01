#!/usr/bin/env python3
"""
یک‌بار اجرا می‌شود:
۱. کلید امضای فعلی را از مخزن می‌گیرد (همان کلید، تا آپدیت‌ها روی نسخه‌ی قبلی نصب شوند)
۲. رمزش را به یک رمز تصادفی جدید عوض می‌کند (رمز قبلی لو رفته حساب می‌شود)
۳. کلید و رمز جدید را در GitHub Secrets ذخیره می‌کند
۴. یک نسخه‌ی پشتیبان در /root/kanki-keystore-backup می‌گذارد
"""
import base64, json, os, secrets, subprocess, sys, tempfile, urllib.request

GHT = os.environ.get("GHT", "")
U = os.environ.get("U", "")
REPO = f"{U}/kanki-app"
OLD_PASS = "KankiVpn2026Store"
if not GHT or not U:
    sys.exit("❌ توکن یا نام کاربری خالی است")

try:
    from nacl import encoding, public
except ImportError:
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "pynacl", "--break-system-packages"], check=False)
    from nacl import encoding, public


def api(method, path, data=None, raw=False):
    req = urllib.request.Request(
        f"https://api.github.com{path}", method=method,
        data=json.dumps(data).encode() if data is not None else None,
        headers={
            "Authorization": f"Bearer {GHT}",
            "Accept": "application/vnd.github.raw" if raw else "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req) as r:
        body = r.read()
        return body if raw else (json.loads(body) if body else {})


# ۱) کلید فعلی از مخزن
b64 = api("GET", f"/repos/{REPO}/contents/app/kanki-release.jks.b64", raw=True).decode()
tmp = tempfile.mkdtemp()
jks = os.path.join(tmp, "k.jks")
open(jks, "wb").write(base64.b64decode(b64))

# ۲) رمز جدید (همان کلید ⇒ امضای اپ عوض نمی‌شود)
new = secrets.token_hex(16)
subprocess.run(["keytool", "-storepasswd", "-keystore", jks, "-storepass", OLD_PASS, "-new", new], check=True)
subprocess.run(["keytool", "-keypasswd", "-keystore", jks, "-alias", "kanki", "-storepass", new,
                "-keypass", OLD_PASS, "-new", new], check=True)

# ۳) ذخیره در GitHub Secrets (رمزنگاری با کلید عمومی مخزن)
pk = api("GET", f"/repos/{REPO}/actions/secrets/public-key")
box = public.SealedBox(public.PublicKey(pk["key"].encode(), encoding.Base64Encoder()))


def put(name, value):
    enc = base64.b64encode(box.encrypt(value.encode())).decode()
    api("PUT", f"/repos/{REPO}/actions/secrets/{name}", {"encrypted_value": enc, "key_id": pk["key_id"]})


put("KANKI_KEYSTORE", base64.b64encode(open(jks, "rb").read()).decode())
put("KANKI_KEY_PASS", new)

# ۴) پشتیبان
bk = "/root/kanki-keystore-backup"
os.makedirs(bk, exist_ok=True)
open(f"{bk}/kanki-release.jks", "wb").write(open(jks, "rb").read())
open(f"{bk}/password.txt", "w").write(new + "\n")
os.chmod(f"{bk}/password.txt", 0o600)
print("✅ کلید و رمز جدید در GitHub Secrets ذخیره شد")
print(f"📦 پشتیبان: {bk}/  (این پوشه را جای امن نگه دار)")
