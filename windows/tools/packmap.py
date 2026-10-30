# Natural Earth countries -> compact binary for the Windows app, with the same point thinning as MapView.kt
import json, struct, sys
src, out = sys.argv[1], sys.argv[2]
feats = json.load(open(src, encoding='utf-8'))['features']
buf = [b'KMAP1', struct.pack('<i', len(feats))]
pts_total = 0
for f in feats:
    pr = f.get('properties') or {}
    iso = pr.get('ISO_A2_EH', '') or ''
    if len(iso) != 2: iso = pr.get('ISO_A2', '') or ''
    if len(iso) != 2: iso = pr.get('iso_a2', '') or ''
    iso = (iso.upper() + '  ')[:2] if len(iso) == 2 else '--'
    g = f.get('geometry') or {}
    rings = []
    if g.get('type') == 'Polygon': rings = g['coordinates']
    elif g.get('type') == 'MultiPolygon': rings = [r for p in g['coordinates'] for r in p]
    packed = []
    for r in rings:
        keep = []; px = py = 0.0
        for k, pt in enumerate(r):
            x, y = float(pt[0]), float(pt[1])
            if k == 0 or k == len(r) - 1 or abs(x - px) + abs(y - py) > .06:
                keep.append((x, y)); px, py = x, y
        packed.append(keep)
    buf.append(iso.encode('ascii') + struct.pack('<H', len(packed)))
    for keep in packed:
        buf.append(struct.pack('<H', len(keep)))
        for x, y in keep:
            buf.append(struct.pack('<hh', round(x * 100), round(y * 100))); pts_total += 1
open(out, 'wb').write(b''.join(buf))
print(len(feats), 'countries', pts_total, 'points')
