import sys, struct
sys.path.insert(0, sys.argv[1])
from swflib import *
s = Swf(sys.argv[2])
out = open(sys.argv[3], 'w')
def P(*a): print(*a, file=out)
P('exports', s.exports)
def dump_timeline(tags, name, maxframe=None):
    frame = 1
    for code, body, raw in tags:
        if code == 1: frame += 1; continue
        if code == 43: P(f'[{name} f{frame}] LABEL', body[:body.index(0)].decode('utf-8','replace'))
        if code == 12:
            P(f'[{name} f{frame}] DoAction')
            for l in Dec().decompile(body): P(l)
        if code == 26:
            r = parse_place2(body)
            if 'clipactions' in r:
                try:
                    for flags, a in clip_actions(r['clipactions'], s.version):
                        P(f'[{name} f{frame}] onClipEvent{event_names(flags)} depth={r["depth"]} cid={r.get("cid")} name={r.get("name")}')
                        for l in Dec().decompile(a): P(l)
                except Exception as e:
                    P('clipaction parse err', e)
dump_timeline(s.tags, 'MAIN')
for sid, (fc, tags) in sorted(s.sprites.items()):
    dump_timeline(tags, f'SPR{sid}' + (f'({s.exports[sid]})' if sid in s.exports else ''))
for code, body, raw in s.tags:
    if code == 34:
        bid = struct.unpack('<H', body[:2])[0]; ao = struct.unpack('<H', body[3:5])[0]
        if ao:
            q = 3 + ao
            while True:
                sz, cond = struct.unpack('<HH', body[q:q+4])
                P(f'[BUTTON {bid}] cond={cond:#x}')
                for l in Dec().decompile(body[q+4:(q+sz) if sz else len(body)]): P(l)
                if not sz: break
                q += sz
