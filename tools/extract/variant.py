"""Build SWF variants of the game for capturing sprite states."""
import struct, zlib, json, sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from swflib import read_tags

def th(code, length):
    return struct.pack('<H', (code << 6) | length) if length < 63 else struct.pack('<HI', (code << 6) | 63, length)

def tag(code, body):
    return th(code, len(body)) + body

def s0(x): return x.encode('utf-8') + b'\0'

class A:
    """tiny AS2 assembler"""
    def __init__(self): self.b = b''
    def raw(self, op, data=b''):
        self.b += bytes([op]) + (struct.pack('<H', len(data)) + data if op >= 0x80 else b''); return self
    def push(self, *vals):
        d = b''
        for v in vals:
            if isinstance(v, bool): d += b'\x05' + bytes([1 if v else 0])
            elif isinstance(v, (int, float)): d += b'\x06' + (lambda q: q[4:] + q[:4])(struct.pack('<d', float(v)))
            elif v is None: d += b'\x02'
            else: d += b'\x00' + s0(v)
        return self.raw(0x96, d)
    def target(self, path): return self.raw(0x8B, s0(path))
    def goto(self, frame): return self.raw(0x81, struct.pack('<H', frame - 1))
    def label(self, l): return self.raw(0x8C, s0(l))
    def stop(self): return self.raw(0x07)
    def play(self): return self.raw(0x06)
    def setprop(self, path, idx, val):
        return self.push(path, idx, val).raw(0x23)
    def tgoto(self, path, frame, play=False):
        self.target(path)
        if isinstance(frame, str): self.label(frame)
        else: self.goto(frame)
        if play: self.play()
        else: self.stop()
        return self.target('')
    def setvar(self, name, val): return self.push(name, val).raw(0x1D)
    def call(self, fname, *args):
        # fname(args) ; pop
        self.push(*reversed(args)) if args else None
        return self.push(len(args), fname).raw(0x3D).raw(0x17)
    def method(self, objpath, mname, *args):
        if args: self.push(*reversed(args))
        self.push(len(args), objpath).raw(0x1C)  # getVariable(objpath) – supports dotted path
        return self.push(mname).raw(0x52).raw(0x17)
    def bytes(self): return tag(12, self.b + b'\0')

def goto_play(frame):
    return b'\x81' + struct.pack('<H', 2) + struct.pack('<H', frame - 1) + b'\x06'

def build(src, at_frame, remove_depths=(), extra_action=None, fps=None, out=None, keep_intro=False):
    d = open(src, 'rb').read()
    body = zlib.decompress(d[8:]) if d[:3] == b'CWS' else d[8:]
    nbits = body[0] >> 3
    hdr = (5 + 4 * nbits + 7) // 8 + 4
    head = bytearray(body[:hdr])
    if fps:
        head[hdr - 4:hdr - 2] = struct.pack('<H', int(fps * 256))
    out_b = bytearray(head)
    frame = 1
    for code, tb, raw in read_tags(body, hdr, len(body)):
        if code == 12 and frame == 4:
            out_b += A().play().bytes(); continue
        if code == 9:  # background color: keep (wmode transparent ignores)
            pass
        if code == 1:
            if frame == 5:
                out_b += tag(12, goto_play(829) + b'\0')
            elif frame == 829:
                out_b += tag(12, b'\x09' + goto_play(at_frame if at_frame < 3266 else 3266) + b'\0') if at_frame != 829 else b''
            if frame == at_frame:
                for dep in remove_depths:
                    out_b += tag(28, struct.pack('<H', dep))
                ea = extra_action or A()
                ready_action(ea)
                out_b += ea.bytes()
                out_b += A().stop().bytes()
            elif 3266 <= frame < at_frame and at_frame > 3303 and frame == 3266:
                pass
            frame += 1
        out_b += raw
        if code == 0: break
    data = bytes(out_b)
    res = b'FWS' + bytes([d[3]]) + struct.pack('<I', len(data) + 8) + data
    if out:
        open(out, 'wb').write(res)
    return res

def ready_action(a=None):
    a = a or A()
    a.raw(0x83, s0('javascript:window.ready=(window.ready||0)+1;void 0') + s0(''))
    return a

class BW:
    def __init__(self): self.bits = []
    def u(self, v, n):
        for i in range(n - 1, -1, -1): self.bits.append((v >> i) & 1)
    def s(self, v, n): self.u(v & ((1 << n) - 1), n)
    def bytes(self):
        b = self.bits + [0] * ((8 - len(self.bits) % 8) % 8)
        return bytes(int(''.join(map(str, b[i:i+8])), 2) for i in range(0, len(b), 8))

def nbits_s(*vals):
    n = 1
    for v in vals:
        while not (-(1 << (n - 1)) <= v < (1 << (n - 1))): n += 1
    return n

def enc_matrix(m):
    sx, r0, r1, sy, tx, ty = m
    w = BW()
    if (sx, sy) != (1.0, 1.0):
        a, b = round(sx * 65536), round(sy * 65536); n = nbits_s(a, b); w.u(1, 1); w.u(n, 5); w.s(a, n); w.s(b, n)
    else: w.u(0, 1)
    if (r0, r1) != (0.0, 0.0):
        a, b = round(r0 * 65536), round(r1 * 65536); n = nbits_s(a, b); w.u(1, 1); w.u(n, 5); w.s(a, n); w.s(b, n)
    else: w.u(0, 1)
    a, b = round(tx * 20), round(ty * 20); n = nbits_s(a, b) if (a or b) else 0; w.u(n, 5)
    if n: w.s(a, n); w.s(b, n)
    return w.bytes()

def place2(depth, cid, matrix=None, name=None):
    flags = 0x02 | (0x04 if matrix else 0) | (0x20 if name else 0)
    b = bytes([flags]) + struct.pack('<HH', depth, cid)
    if matrix: b += enc_matrix(matrix)
    if name: b += s0(name)
    return tag(26, b)

def build_scene(src, keep, dl, extra=(), actions=None, out=None, at=3277, fps=None):
    """keep: {depth: name or None}; extra: [(depth,cid,matrix,name)]"""
    d = open(src, 'rb').read()
    body = zlib.decompress(d[8:]) if d[:3] == b'CWS' else d[8:]
    nbits = body[0] >> 3; hdr = (5 + 4 * nbits + 7) // 8 + 4
    head = bytearray(body[:hdr])
    if fps: head[hdr - 4:hdr - 2] = struct.pack('<H', int(fps * 256))
    ob = bytearray(head); frame = 1
    for code, tb, raw in read_tags(body, hdr, len(body)):
        if code == 12 and frame == 4:
            ob += A().play().bytes(); continue
        if code == 1:
            if frame == 5: ob += tag(12, goto_play(829) + b'\0')
            elif frame == 829: ob += tag(12, b'\x09' + goto_play(3266) + b'\0')
            if frame == at:
                for dep in sorted(dl):
                    if dep not in keep or keep[dep]:
                        ob += tag(28, struct.pack('<H', dep))
                    if dep in keep and keep[dep]:
                        r = dl[dep]; ob += place2(dep, r['cid'], r.get('matrix'), keep[dep])
                for dep, cid, m, nm in extra:
                    ob += place2(dep, cid, m, nm)
                ea = actions or A(); ready_action(ea); ob += ea.bytes(); ob += A().stop().bytes()
            frame += 1
        ob += raw
        if code == 0: break
    data = bytes(ob)
    res = b'FWS' + bytes([d[3]]) + struct.pack('<I', len(data) + 8) + data
    if out: open(out, 'wb').write(res)
    return res

def build_at(src, at, keep_depths, dl, out=None):
    d = open(src, 'rb').read()
    body = zlib.decompress(d[8:]) if d[:3] == b'CWS' else d[8:]
    nbits = body[0] >> 3; hdr = (5 + 4 * nbits + 7) // 8 + 4
    ob = bytearray(body[:hdr]); frame = 1
    for code, tb, raw in read_tags(body, hdr, len(body)):
        if code == 12 and frame == 4:
            ob += A().play().bytes(); continue
        if code == 1:
            if frame == 5: ob += tag(12, goto_play(at) + b'\0')
            if frame == at:
                for dep in sorted(dl):
                    if dep not in keep_depths: ob += tag(28, struct.pack('<H', dep))
                ea = A(); ready_action(ea); ob += ea.bytes(); ob += A().stop().bytes()
            frame += 1
        ob += raw
        if code == 0: break
    data = bytes(ob)
    res = b'FWS' + bytes([d[3]]) + struct.pack('<I', len(data) + 8) + data
    if out: open(out, 'wb').write(res)
    return res
