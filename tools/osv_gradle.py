#!/usr/bin/env python3
"""Gradle dependency tree (stdin) -> OSV vulnerability check (api.osv.dev). Exit 1 if any vulnerable package."""
import json, re, sys, urllib.request
pkgs = set()
for line in sys.stdin:
    m = re.search(r'([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([^\s:()]+?)(?: -> ([^\s()]+))?(?: \(\*\)| \(c\))?\s*$', line)
    if not m or '--- ' not in line and '\\--- ' not in line:
        continue
    g, a, v, r = m.groups()
    ver = r or v
    if ver and ver[0].isdigit():
        pkgs.add((g, a, ver))
pkgs = sorted(pkgs)
queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in pkgs]
bad = []
for i in range(0, len(queries), 500):
    body = json.dumps({"queries": queries[i:i + 500]}).encode()
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", body, {"Content-Type": "application/json"})
    res = json.load(urllib.request.urlopen(req, timeout=60))["results"]
    for q, r in zip(queries[i:i + 500], res):
        ids = [v["id"] for v in r.get("vulns", [])]
        if ids:
            bad.append((q["package"]["name"], q["version"], ids))
print(f"checked {len(pkgs)} Maven packages")
for n, v, ids in bad:
    print(f"VULNERABLE {n} {v}: {' '.join(ids)}")
sys.exit(1 if bad else 0)
