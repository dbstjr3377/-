"""Minimal SWF parser + AS2 decompiler-lite."""
import zlib, struct

class Swf:
    def __init__(self, path):
        d = open(path, 'rb').read()
        self.version = d[3]
        self.body = zlib.decompress(d[8:]) if d[:3] == b'CWS' else d[8:]
        nbits = self.body[0] >> 3
        self.hdr_end = (5 + 4 * nbits + 7) // 8 + 4
        self.rect_bytes = self.body[:self.hdr_end - 4]
        self.tags = list(read_tags(self.body, self.hdr_end, len(self.body)))
        self.chars = {}
        for t in self.tags:
            if t[0] in CHAR_DEF and len(t[1]) >= 2:
                self.chars[struct.unpack('<H', t[1][:2])[0]] = t
        self.sprites = {}
        for code, body, raw in self.tags:
            if code == 39:
                sid, fc = struct.unpack('<HH', body[:4])
                self.sprites[sid] = (fc, list(read_tags(body, 4, len(body))))
        self.exports = {}
        for code, body, raw in self.tags:
            if code == 56:
                n = struct.unpack('<H', body[:2])[0]; p = 2
                for _ in range(n):
                    cid = struct.unpack('<H', body[p:p+2])[0]; p += 2
                    e = body.index(b'\0', p); self.exports[cid] = body[p:e].decode('utf-8', 'replace'); p = e + 1

CHAR_DEF = {2, 6, 10, 11, 13, 14, 20, 21, 22, 32, 33, 34, 35, 36, 37, 39, 46, 48, 60, 62, 75, 83, 84, 90, 7}

def read_tags(b, p, end):
    while p < end:
        start = p
        h = struct.unpack('<H', b[p:p+2])[0]; p += 2
        code, l = h >> 6, h & 63
        if l == 63:
            l = struct.unpack('<I', b[p:p+4])[0]; p += 4
        body = b[p:p+l]; p += l
        yield code, body, b[start:p]
        if code == 0:
            break

class Bits:
    def __init__(self, b, p=0):
        self.b = b; self.pos = p * 8
    def u(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | ((self.b[self.pos >> 3] >> (7 - (self.pos & 7))) & 1); self.pos += 1
        return v
    def s(self, n):
        if n == 0: return 0
        v = self.u(n)
        if v & (1 << (n - 1)): v -= 1 << n
        return v
    def align(self):
        self.pos = (self.pos + 7) & ~7
    @property
    def byte(self):
        self.align(); return self.pos >> 3

def read_matrix(bits):
    sx = sy = 1.0; r0 = r1 = 0.0
    if bits.u(1):
        n = bits.u(5); sx = bits.s(n) / 65536; sy = bits.s(n) / 65536
    if bits.u(1):
        n = bits.u(5); r0 = bits.s(n) / 65536; r1 = bits.s(n) / 65536
    n = bits.u(5); tx = bits.s(n) / 20; ty = bits.s(n) / 20
    return (sx, r0, r1, sy, tx, ty)

def parse_place2(body, version=6):
    flags = body[0]; depth = struct.unpack('<H', body[1:3])[0]; p = 3
    r = {'depth': depth, 'move': bool(flags & 1)}
    if flags & 2:
        r['cid'] = struct.unpack('<H', body[p:p+2])[0]; p += 2
    bits = Bits(body, p)
    if flags & 4:
        r['matrix'] = read_matrix(bits); p = bits.byte
    if flags & 8:
        bits = Bits(body, p)
        has_add = bits.u(1); has_mul = bits.u(1); n = bits.u(4)
        cx = {}
        if has_mul: cx['mul'] = [bits.s(n) for _ in range(4)]
        if has_add: cx['add'] = [bits.s(n) for _ in range(4)]
        r['cxform'] = cx; p = bits.byte
    if flags & 16:
        r['ratio'] = struct.unpack('<H', body[p:p+2])[0]; p += 2
    if flags & 32:
        e = body.index(b'\0', p); r['name'] = body[p:e].decode('utf-8', 'replace'); p = e + 1
    if flags & 64:
        r['clipdepth'] = struct.unpack('<H', body[p:p+2])[0]; p += 2
    if flags & 128:
        r['clipactions'] = body[p:]
    return r

def display_list_timeline(tags, upto_frame):
    """returns {depth: placeinfo} at given (1-based) frame"""
    dl = {}; frame = 1
    for code, body, raw in tags:
        if code == 26:
            r = parse_place2(body)
            if r['depth'] in dl and r['move']:
                old = dl[r['depth']]
                if 'cid' in r and r.get('cid') != old.get('cid'):
                    dl[r['depth']] = {**r}
                else:
                    dl[r['depth']] = {**old, **{k: v for k, v in r.items() if k != 'move'}}
            else:
                dl[r['depth']] = r
        elif code == 28:
            dl.pop(struct.unpack('<H', body[:2])[0], None)
        elif code == 5:
            dl.pop(struct.unpack('<H', body[2:4])[0], None)
        elif code == 1:
            if frame == upto_frame:
                return dl
            frame += 1
    return dl

# ---------------- AS2 decompiler-lite -----------------
class Dec:
    def __init__(self):
        self.out = []
    def decompile(self, a, indent=0, pool=None, regs=None):
        pool = pool if pool is not None else []
        st = []
        p = 0
        lines = []
        ind = '  ' * indent
        regs = regs or {}
        def pop():
            return st.pop() if st else '<?>'
        def emit(s, off):
            lines.append(f'{ind}{off:5d}: {s}')
        while p < len(a):
            off = p
            c = a[p]; p += 1
            if c == 0:
                break
            data = b''
            if c >= 0x80:
                ln = struct.unpack('<H', a[p:p+2])[0]; p += 2; data = a[p:p+ln]; p += ln
            if c == 0x88:
                cnt = struct.unpack('<H', data[:2])[0]
                pool[:] = [x.decode('utf-8', 'replace') for x in data[2:].split(b'\0')[:cnt]]
            elif c == 0x96:
                q = 0
                while q < len(data):
                    t = data[q]; q += 1
                    if t == 0:
                        e = data.index(b'\0', q); st.append(repr(data[q:e].decode('utf-8', 'replace'))); q = e + 1
                    elif t == 1: st.append(fmtnum(struct.unpack('<f', data[q:q+4])[0])); q += 4
                    elif t == 2: st.append('null')
                    elif t == 3: st.append('undefined')
                    elif t == 4: st.append(regs.get(data[q], 'r%d' % data[q])); q += 1
                    elif t == 5: st.append('true' if data[q] else 'false'); q += 1
                    elif t == 6: st.append(fmtnum(struct.unpack('<d', data[q+4:q+8] + data[q:q+4])[0])); q += 8
                    elif t == 7: st.append(str(struct.unpack('<i', data[q:q+4])[0])); q += 4
                    elif t == 8: st.append(repr(pool[data[q]]) if data[q] < len(pool) else '?'); q += 1
                    elif t == 9:
                        i = struct.unpack('<H', data[q:q+2])[0]; st.append(repr(pool[i]) if i < len(pool) else '?'); q += 2
            elif c == 0x1C: st.append(unq(pop()))
            elif c == 0x1D: v = pop(); n = pop(); emit(f'{unq(n)} = {v}', off)
            elif c == 0x4E: n = pop(); o = pop(); st.append(member(o, n))
            elif c == 0x4F: v = pop(); n = pop(); o = pop(); emit(f'{member(o, n)} = {v}', off)
            elif c == 0x3D:
                n = pop(); k = num(pop()); args = [pop() for _ in range(k)]; st.append(f'{unq(n)}({", ".join(args)})')
            elif c == 0x52:
                n = pop(); o = pop(); k = num(pop()); args = [pop() for _ in range(k)]
                st.append(f'{member(o, n) if n not in ("undefined", "\'\'") else o}({", ".join(args)})')
            elif c == 0x40:
                n = pop(); k = num(pop()); args = [pop() for _ in range(k)]; st.append(f'new {unq(n)}({", ".join(args)})')
            elif c == 0x53:
                n = pop(); o = pop(); k = num(pop()); args = [pop() for _ in range(k)]; st.append(f'new {member(o, n)}({", ".join(args)})')
            elif c == 0x42:
                k = num(pop()); args = [pop() for _ in range(k)]; st.append('[' + ', '.join(args) + ']')
            elif c == 0x43:
                k = num(pop()); items = []
                for _ in range(k):
                    v = pop(); n = pop(); items.append(f'{unq(n)}: {v}')
                st.append('{' + ', '.join(items) + '}')
            elif c == 0x17:
                v = pop()
                if v != '<?>': emit(v, off)
            elif c in BIN:
                b = pop(); a2 = pop(); st.append(f'({a2} {BIN[c]} {b})')
            elif c == 0x12: st.append(f'!{pop()}')
            elif c == 0x50: st.append(f'({pop()} + 1)')
            elif c == 0x51: st.append(f'({pop()} - 1)')
            elif c == 0x3C: v = pop(); n = pop(); emit(f'var {unq(n)} = {v}', off)
            elif c == 0x41: n = pop(); emit(f'var {unq(n)}', off)
            elif c == 0x3E: emit(f'return {pop()}', off)
            elif c == 0x87:
                r = data[0]; v = st[-1] if st else '<?>'; emit(f'r{r} := {v}', off)
                st[-1:] = [f'r{r}'] if st else []
            elif c == 0x4C: st.append(st[-1] if st else '<?>')
            elif c == 0x4D:
                if len(st) >= 2: st[-1], st[-2] = st[-2], st[-1]
            elif c == 0x9D:
                o = struct.unpack('<h', data)[0]; emit(f'if {pop()} goto {p + o}', off)
            elif c == 0x99:
                o = struct.unpack('<h', data)[0]; emit(f'goto {p + o}', off)
            elif c in (0x9B, 0x8E):
                e = data.index(b'\0'); name = data[:e].decode('utf-8', 'replace'); q = e + 1
                n = struct.unpack('<H', data[q:q+2])[0]; q += 2
                params = []; fregs = {}
                if c == 0x8E:
                    rc = data[q]; q += 1; fl = struct.unpack('<H', data[q:q+2])[0]; q += 2
                    # preloaded regs
                    ri = 1
                    for flag, nm in ((0x0004, 'this'), (0x0010, 'arguments'), (0x0040, 'super'), (0x0100, '_root'), (0x0200, '_parent'), (0x0400, '_global')):
                        pass
                    order = [(0x0001, 'this', 0x0002), (0x0004, 'arguments', 0x0008), (0x0010, 'super', 0x0020), (0x0040, '_root', None), (0x0080, '_parent', None), (0x0100, '_global', None)]
                    for f, nm, sup in order:
                        if fl & f:
                            fregs[ri] = nm; ri += 1
                    for _ in range(n):
                        r = data[q]; q += 1; e = data.index(b'\0', q); pn = data[q:e].decode('utf-8', 'replace'); q = e + 1
                        params.append(pn)
                        if r: fregs[r] = pn
                else:
                    for _ in range(n):
                        e = data.index(b'\0', q); params.append(data[q:e].decode('utf-8', 'replace')); q = e + 1
                size = struct.unpack('<H', data[q:q+2])[0]
                body = a[p:p+size]; p += size
                sub = self.decompile(body, indent + 1, pool, {**regs, **fregs})
                head = f'function {name}({", ".join(params)}) {{'
                if name:
                    emit(head, off); lines.extend(sub); lines.append(ind + '      }')
                else:
                    lines.append(f'{ind}{off:5d}: <fn>')
                    lines.extend([ind + '      ' + head] + sub + [ind + '      }'])
                    st.append('<fn>')
            elif c == 0x81: emit(f'gotoFrame({struct.unpack("<H", data)[0] + 1})', off)
            elif c == 0x8C: emit(f'gotoLabel({data[:-1].decode()})', off)
            elif c == 0x9F: emit(f'gotoFrame2({pop()}, play={data[0]&1})', off)
            elif c == 0x06: emit('play()', off)
            elif c == 0x07: emit('stop()', off)
            elif c == 0x04: emit('nextFrame()', off)
            elif c == 0x05: emit('prevFrame()', off)
            elif c == 0x09: emit('stopAllSounds()', off)
            elif c == 0x8B: emit(f'setTarget({data[:-1].decode()!r})', off)
            elif c == 0x20: emit(f'setTarget2({pop()})', off)
            elif c == 0x22: i = pop(); t = pop(); st.append(f'getProperty({t}, {PROPS.get(i, i)})')
            elif c == 0x23: v = pop(); i = pop(); t = pop(); emit(f'setProperty({t}, {PROPS.get(i, i)}, {v})', off)
            elif c == 0x26: emit(f'trace({pop()})', off)
            elif c == 0x34: st.append('getTimer()')
            elif c == 0x30: st.append(f'random({pop()})')
            elif c == 0x44: st.append(f'typeof({pop()})')
            elif c == 0x18: st.append(f'int({pop()})')
            elif c == 0x4A: st.append(f'Number({pop()})')
            elif c == 0x4B: st.append(f'String({pop()})')
            elif c == 0x3A: n = pop(); o = pop(); st.append(f'delete {member(o, n)}')
            elif c == 0x24: d = pop(); t = pop(); s = pop(); emit(f'duplicateMovieClip({s}, {t}, {d})', off)
            elif c == 0x25: emit(f'removeMovieClip({pop()})', off)
            elif c == 0x27:
                t = pop(); lock = pop(); con = pop()
                if con not in ('0', 'false', '0.0'):
                    for _ in range(4): pop()
                emit(f'startDrag({t}, {lock})', off)
            elif c == 0x28: emit('stopDrag()', off)
            elif c == 0x83:
                e = data.index(b'\0'); emit(f'getURL({data[:e]!r}, {data[e+1:-1]!r})', off)
            elif c == 0x9A: t = pop(); u = pop(); emit(f'getURL2({u}, {t})', off)
            elif c == 0x8D: emit(f'waitForFrame2(...)', off); pop()
            elif c == 0x94: emit(f'with({pop()}) [size {struct.unpack("<H", data)[0]}]', off)
            elif c == 0x46: emit(f'enumerate {pop()}', off); st.append('<enum>')
            elif c == 0x55: emit(f'enumerate2 {pop()}', off); st.append('<enum>')
            elif c == 0x2B: st.append(f'cast({pop()},{pop()})')
            elif c == 0x54: c2 = pop(); o = pop(); st.append(f'({o} instanceof {c2})')
            elif c == 0x2C: emit('implements', off)
            elif c == 0x69: emit('extends', off)
            elif c == 0x8F: emit('try...', off)
            elif c == 0x2A: emit(f'throw {pop()}', off)
            elif c == 0x0E: b = pop(); a2 = pop(); st.append(f'({a2} == {b})')
            elif c == 0x0F: b = pop(); a2 = pop(); st.append(f'({a2} < {b})')
            elif c == 0x10: b = pop(); a2 = pop(); st.append(f'({a2} && {b})')
            elif c == 0x11: b = pop(); a2 = pop(); st.append(f'({a2} || {b})')
            elif c == 0x13: b = pop(); a2 = pop(); st.append(f'({a2} eqs {b})')
            elif c == 0x14: st.append(f'length({pop()})')
            elif c == 0x21: b = pop(); a2 = pop(); st.append(f'({a2} ~ {b})')
            elif c == 0x15: n = pop(); i = pop(); s = pop(); st.append(f'substr({s},{i},{n})')
            elif c == 0x2E: emit('<?0x2e>', off)
            else:
                emit(f'<op {c:#x}>', off)
        for s in st:
            if s not in ('<?>',):
                lines.append(f'{ind}   ~ stack: {s}')
        return lines

BIN = {0x0A: '+', 0x0B: '-', 0x0C: '*', 0x0D: '/', 0x47: '+', 0x48: '<', 0x49: '==', 0x66: '===', 0x67: '>', 0x3F: '%', 0x60: '&', 0x61: '|', 0x62: '^', 0x63: '<<', 0x64: '>>', 0x65: '>>>', 0x68: 'gt_str'}
PROPS = {str(i): n for i, n in enumerate(['_x', '_y', '_xscale', '_yscale', '_currentframe', '_totalframes', '_alpha', '_visible', '_width', '_height', '_rotation', '_target', '_framesloaded', '_name', '_droptarget', '_url', '_highquality', '_focusrect', '_soundbuftime', '_quality', '_xmouse', '_ymouse'])}

def fmtnum(v):
    return str(int(v)) if v == int(v) else str(v)
def num(s):
    try: return int(float(s))
    except: return 0
def unq(s):
    if len(s) >= 2 and s[0] == s[-1] == "'": return s[1:-1]
    return s
def member(o, n):
    u = unq(n)
    if n != u and u.isidentifier(): return f'{o}.{u}'
    return f'{o}[{n}]'

def clip_actions(data, swfver=6):
    """parse CLIPACTIONS from placeobject2"""
    p = 2  # reserved
    p += 4 if swfver >= 6 else 2  # all event flags
    res = []
    while True:
        flags = struct.unpack('<I', data[p:p+4])[0] if swfver >= 6 else struct.unpack('<H', data[p:p+2])[0]
        p += 4 if swfver >= 6 else 2
        if flags == 0: break
        size = struct.unpack('<I', data[p:p+4])[0]; p += 4
        q = p
        key = None
        if flags & 0x00020000:  # keypress (bit in swf6 layout)
            key = data[q]; q += 1
        res.append((flags, data[q:p+size]))
        p += size
    return res

EVENTS = ['keyUp', 'keyDown', 'mouseUp', 'mouseDown', 'mouseMove', 'unload', 'enterFrame', 'load', 'dragOver', 'rollOut', 'rollOver', 'releaseOutside', 'release', 'press', 'initialize', 'data', '?', '?', '?', '?', '?', '?', 'construct', 'keyPress', 'dragOut']
def event_names(flags):
    # SWF6 32-bit layout: byte0: keyUp,keyDown,mouseUp,mouseDown,mouseMove,unload,enterFrame,load (msb first)
    b0 = flags & 0xff; b1 = (flags >> 8) & 0xff; b2 = (flags >> 16) & 0xff
    n0 = ['keyUp', 'keyDown', 'mouseUp', 'mouseDown', 'mouseMove', 'unload', 'enterFrame', 'load']
    n1 = ['dragOver', 'rollOut', 'rollOver', 'releaseOutside', 'release', 'press', 'initialize', 'data']
    n2 = ['?', '?', '?', '?', '?', 'construct', 'keyPress', 'dragOut']
    out = []
    for i in range(8):
        if b0 & (0x80 >> i): out.append(n0[i])
        if b1 & (0x80 >> i): out.append(n1[i])
        if b2 & (0x80 >> i): out.append(n2[i])
    return out
