"""Build standalone SWFs that render chosen characters with the game's dictionary."""
import struct, zlib, sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from swflib import read_tags, Swf, display_list_timeline, parse_place2
from variant import A, tag, place2, ready_action, s0

EXCLUDE = {0, 1, 4, 5, 12, 15, 18, 19, 26, 28, 43, 45, 59, 70, 24}

class Mini:
    def __init__(self, src):
        d = open(src, 'rb').read()
        self.ver = d[3]
        body = zlib.decompress(d[8:]) if d[:3] == b'CWS' else d[8:]
        nbits = body[0] >> 3; hdr = (5 + 4 * nbits + 7) // 8 + 4
        self.head = body[:hdr]
        self.dict = b''.join(raw for code, tb, raw in read_tags(body, hdr, len(body)) if code not in EXCLUDE)

    def build(self, places, actions=None, out=None, fps=30):
        head = bytearray(self.head)
        head[-4:-2] = struct.pack('<H', int(fps * 256)); head[-2:] = struct.pack('<H', 1)
        b = bytearray(head) + self.dict
        for i, (cid, m, name) in enumerate(places):
            b += place2(i + 1, cid, m, name)
        a = actions or A(); ready_action(a); b += a.bytes()
        b += A().stop().bytes()
        b += tag(1, b'') + tag(0, b'')
        data = bytes(b)
        res = b'FWS' + bytes([self.ver]) + struct.pack('<I', len(data) + 8) + data
        if out: open(out, 'wb').write(res)
        return res

def compose(P, C):
    Pa, Pb, Pc, Pd, Ptx, Pty = P; Ca, Cb, Cc, Cd, Ctx, Cty = C
    return (Pa*Ca + Pc*Cb, Pb*Ca + Pd*Cb, Pa*Cc + Pc*Cd, Pb*Cc + Pd*Cd, Pa*Ctx + Pc*Cty + Ptx, Pb*Ctx + Pd*Cty + Pty)

I = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
