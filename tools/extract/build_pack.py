"""Build the Minecraft resource pack + plugin manifest from captured sprites.
usage: build_pack.py <swf> <capdir> <enddir> <mask_png> <outdir>
"""
import sys, os, json, struct, hashlib, base64, math, subprocess, zipfile, io, shutil
sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image, ImageChops, ImageStat
from swflib import Swf

SWF, CAP, END, MASK, OUT = sys.argv[1:6]
S = 1.5
NS = 'ddinghowa'
PACK = os.path.join(OUT, 'pack')
shutil.rmtree(PACK, ignore_errors=True)
for d in ('textures/item', 'models/item', 'items', 'sounds'):
    os.makedirs(f'{PACK}/assets/{NS}/{d}', exist_ok=True)

frames = {}      # tex id -> rect
tex_by_hash = {}
manifest = {'stage': [550, 413], 'frames': frames, 'groups': {}, 'masks': {}, 'endings': {}, 'sounds': {}}
counter = [0]

def pad16(im):
    w, h = im.size
    W, H = (w + 15) // 16 * 16, (h + 15) // 16 * 16
    if (W, H) == (w, h): return im
    c = Image.new('RGBA', (W, H), (0, 0, 0, 0)); c.paste(im, (0, 0)); return c

def add_texture(prefix, im, x, y, scale):
    """im: cropped RGBA; x,y: stage coords of top-left. returns tex id"""
    im = pad16(im)
    h = hashlib.sha1(im.tobytes() + f'{x:.2f},{y:.2f},{im.size}'.encode()).hexdigest()
    if h in tex_by_hash: return tex_by_hash[h]
    tid = f'{prefix}_{counter[0]}'; counter[0] += 1
    im.save(f'{PACK}/assets/{NS}/textures/item/{tid}.png', optimize=True)
    frames[tid] = {'x': round(x, 3), 'y': round(y, 3), 'w': round(im.size[0] / scale, 3), 'h': round(im.size[1] / scale, 3)}
    tex_by_hash[h] = tid
    return tid

CLICKABLE = lambda g: g.startswith(('ing_', 'dish1', 'dish2', 'btn_', 'help', 'treeclose'))

def add_mask(tid, im):
    if tid in manifest['masks']: return
    r = frames[tid]
    mw, mh = max(1, round(r['w'])), max(1, round(r['h']))
    a = pad16(im).split()[3].resize((mw, mh), Image.BILINEAR)
    bits = bytearray((mw * mh + 7) // 8)
    px = a.load()
    for yy in range(mh):
        for xx in range(mw):
            if px[xx, yy] > 40:
                i = yy * mw + xx; bits[i >> 3] |= 1 << (i & 7)
    manifest['masks'][tid] = {'w': mw, 'h': mh, 'bits': base64.b64encode(bytes(bits)).decode()}

# ---------- game sprites ----------
groups = json.load(open(os.path.join(CAP, 'groups.json')))
for g, names in groups.items():
    out = {}
    for n in names:
        im = Image.open(os.path.join(CAP, f'{g}__{n}.png')).convert('RGBA')
        bb = im.getbbox()
        if not bb: continue
        c = im.crop(bb)
        tid = add_texture(g.replace('_', '').lower(), c, bb[0] / S, bb[1] / S, S)
        out[n] = tid
        if CLICKABLE(g): add_mask(tid, c)
    manifest['groups'][g] = out

# gauge red bar rect (difference between full and empty)
gf = Image.open(os.path.join(CAP, 'gaugefull__0.png')).convert('RGB')
ge = Image.open(os.path.join(CAP, 'gauge__0.png')).convert('RGB')
diff = ImageChops.difference(gf, ge).convert('L').point(lambda v: 255 if v > 60 else 0)
bb = diff.getbbox()
manifest['gaugeBar'] = {'x': bb[0] / S, 'y': bb[1] / S, 'w': (bb[2] - bb[0]) / S, 'h': (bb[3] - bb[1]) / S}
red = gf.getpixel(((bb[0] + bb[2]) // 2, (bb[1] + bb[3]) // 2))
Image.new('RGBA', (16, 16), red + (255,)).save(f'{PACK}/assets/{NS}/textures/item/solid_red.png')
frames['solid_red'] = {'x': 0, 'y': 0, 'w': 1, 'h': 1}
del manifest['groups']['gaugefull']

# ---------- endings ----------
ES = 0.5
def ending(at):
    fs = sorted(f for f in os.listdir(END) if f.startswith(f'e{at}_'))
    seq = []; prev = None
    for i, f in enumerate(fs):
        im = Image.open(os.path.join(END, f)).convert('RGB')
        if prev is not None and sum(ImageStat.Stat(ImageChops.difference(im, prev)).mean) / 3 < 1.5:
            seq[-1][1] += 250; continue
        prev = im
        small = im.resize((round(550 * ES), round(413 * ES)), Image.LANCZOS).convert('RGBA')
        tid = add_texture('end', small, 0, 0, ES)
        seq.append([tid, 250])
    fin = Image.open(os.path.join(END, f'final{at}.png')).convert('RGBA').crop((0, 0, 825, 620))
    ftid = add_texture('endfinal', fin, 0, 0, S)
    manifest['endings'][str(at)] = {'frames': seq, 'final': ftid}
for at in (3304, 3889, 4401):
    ending(at)

# ---------- mask ----------
m = Image.open(MASK).convert('RGBA')
m = m.resize((round(m.width * 512 / m.height), 512), Image.LANCZOS)
# 착용 아이템은 면 방향 음영(약 0.75배)을 받으므로 미리 밝게 보정
r, g, b, a = m.split()
r, g, b = (ch.point(lambda v: min(255, round(v * 1.3))) for ch in (r, g, b))
m = Image.merge('RGBA', (r, g, b, a))
sq = Image.new('RGBA', (512, 512), (0, 0, 0, 0)); sq.paste(m, ((512 - m.width) // 2, 0), m)
sq.save(f'{PACK}/assets/{NS}/textures/item/chef_mask.png')
manifest['maskAspect'] = m.width / m.height

# ---------- models ----------
# Screen sprite model: unit quad, tilted 50deg about X so the baked lighting normal snaps to UP;
# the plugin's display transformation rotates it back to vertical (see ScreenLayer.java).
TILT = 50.0
def sprite_model(tid):
    return {
        'textures': {'t': f'{NS}:item/{tid}', 'particle': f'{NS}:item/{tid}'},
        'elements': [{
            'from': [0, 0, 8], 'to': [16, 16, 8],
            'rotation': {'angle': TILT, 'axis': 'x', 'origin': [8, 8, 8]},
            'shade': False,
            'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#t'}}
        }]
    }
for tid in list(frames):
    json.dump(sprite_model(tid), open(f'{PACK}/assets/{NS}/models/item/{tid}.json', 'w'))
    json.dump({'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{tid}'}}, open(f'{PACK}/assets/{NS}/items/{tid}.json', 'w'))
manifest['tilt'] = TILT

# mask model: flat plane in front of the face, larger than the head
aspect = manifest['maskAspect']
mw = 18.0; mh = mw / aspect
eye = 0.52  # eye line, fraction from top of image
top = 7.5 + eye * mh
x0 = 8 - mw / 2
# texture is square 512 with image centred horizontally -> uv spans the image only
u0 = (1 - aspect) / 2 * 16; u1 = 16 - u0
mask_model = {
    'textures': {'t': f'{NS}:item/chef_mask', 'particle': f'{NS}:item/chef_mask'},
    'elements': [{
        'from': [x0, top - mh, 1.0], 'to': [x0 + mw, top, 1.0],
        'faces': {'north': {'uv': [u0, 0, u1, 16], 'texture': '#t'},
                  'south': {'uv': [u1, 0, u0, 16], 'texture': '#t'}}
    }],
    'display': {
        'gui': {'rotation': [0, 180, 0], 'translation': [0, 1.5, 0], 'scale': [0.55, 0.55, 0.55]},
        'ground': {'rotation': [0, 180, 0], 'translation': [0, 2, 0], 'scale': [0.4, 0.4, 0.4]},
        'fixed': {'rotation': [0, 180, 0], 'translation': [0, 1.5, 0], 'scale': [0.6, 0.6, 0.6]},
        'thirdperson_righthand': {'rotation': [0, 180, 0], 'translation': [0, 2, 1], 'scale': [0.3, 0.3, 0.3]},
        'thirdperson_lefthand': {'rotation': [0, 180, 0], 'translation': [0, 2, 1], 'scale': [0.3, 0.3, 0.3]},
        'firstperson_righthand': {'rotation': [0, 160, 0], 'translation': [1, 2, 0], 'scale': [0.15, 0.15, 0.15]},
        'firstperson_lefthand': {'rotation': [0, 200, 0], 'translation': [-1, 2, 0], 'scale': [0.15, 0.15, 0.15]}
    }
}
json.dump(mask_model, open(f'{PACK}/assets/{NS}/models/item/chef_mask.json', 'w'), indent=1)
json.dump({'model': {'type': 'minecraft:model', 'model': f'{NS}:item/chef_mask'}}, open(f'{PACK}/assets/{NS}/items/chef_mask.json', 'w'))

# ---------- sounds ----------
s = Swf(SWF)
SND_NAMES = {1: 'hover', 17: 'take', 15: 'fail_bgm', 16: 'falling_water', 18: 'more_complete', 19: 'bad2',
             20: 'need_complete', 21: 'good1', 22: 'good0', 23: 'bad1', 24: 'bad0', 40: 'zing',
             94: 'boil', 101: 'sizzle_short', 122: 'drop', 141: 'meat_drop', 153: 'broil',
             457: 'end_s457', 543: 'arrow_s543', 620: 'end_s620', 631: 'end_s631', 646: 'end_s646', 667: 'end_s667', 690: 'end_s690'}
tmp = os.path.join(OUT, 'snd_tmp'); os.makedirs(tmp, exist_ok=True)
sounds_json = {}
SND_TRIM = {94: (0, 2.021), 16: (2.521, 3.171), 101: (0.506, 1.791)}
def to_ogg(src, name, extra=()):
    dst = f'{PACK}/assets/{NS}/sounds/{name}.ogg'
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', src, *extra, '-ac', '1', '-ar', '44100', '-c:a', 'libvorbis', '-q:a', '3', dst], check=True)
    sounds_json[name] = {'sounds': [{'name': f'{NS}:{name}', 'stream': name.startswith(('end', 'fail', 'zing', 'boil'))}]}
def adpcm_to_wav(body, rate, path):
    # SWF ADPCM decoder (mono)
    data = body
    bits = 0; nbits = 0; pos = 0
    def rd(n):
        nonlocal bits, nbits, pos
        while nbits < n:
            bits = (bits << 8) | (data[pos] if pos < len(data) else 0); pos += 1; nbits += 8
        v = (bits >> (nbits - n)) & ((1 << n) - 1); nbits -= n; return v
    STEP = [7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767]
    IDX = {2: [-1, 2], 3: [-1, -1, 2, 4], 4: [-1, -1, -1, -1, 2, 4, 6, 8], 5: [-1]*8 + [1, 2, 4, 6, 8, 10, 13, 16]}
    out = []
    bits_per = rd(2) + 2
    total = len(data) * 8
    while (pos * 8 - nbits) + 22 < total:
        sample = rd(16); sample = sample - 65536 if sample >= 32768 else sample
        idx = rd(6); out.append(sample)
        for _ in range(4095):
            if (pos * 8 - nbits) + bits_per > total: break
            code = rd(bits_per)
            step = STEP[idx]; sign = 1 << (bits_per - 1); mag = code & (sign - 1)
            diff = step >> (bits_per - 1)
            k = sign >> 1; st = step
            while k:
                if mag & k: diff += st
                st >>= 1; k >>= 1
            sample = sample - diff if code & sign else sample + diff
            sample = max(-32768, min(32767, sample))
            idx = max(0, min(88, idx + IDX[bits_per][mag]))
            out.append(sample)
    import wave
    w = wave.open(path, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
    w.writeframes(b''.join(struct.pack('<h', v) for v in out)); w.close()
for code, body, raw in s.tags:
    if code != 14: continue
    sid = struct.unpack('<H', body[:2])[0]
    if sid not in SND_NAMES: continue
    f = body[2]; fmt = f >> 4; rate = [5512, 11025, 22050, 44100][(f >> 2) & 3]
    name = SND_NAMES[sid]
    if fmt == 2:
        p = f'{tmp}/{name}.mp3'; open(p, 'wb').write(body[9:])
    elif fmt == 1:
        p = f'{tmp}/{name}.wav'; adpcm_to_wav(body[7:], rate, p)
    else:
        continue
    tr = SND_TRIM.get(sid)
    to_ogg(p, name, ('-ss', str(tr[0]), '-to', str(tr[1])) if tr else ())
    manifest['sounds'][str(sid)] = name
# stream audio for endings (main timeline SoundStreamBlocks)
blocks = {}
fr = 1
for code, body, raw in s.tags:
    if code == 1: fr += 1
    elif code == 19: blocks[fr] = body[4:]
for at, end in ((3304, 3888), (3889, 4400), (4401, 4864)):
    data = b''.join(blocks[f] for f in range(at, end + 1) if f in blocks)
    if data:
        p = f'{tmp}/stream{at}.mp3'; open(p, 'wb').write(data)
        to_ogg(p, f'end_stream{at}')
        manifest['endings'][str(at)]['stream'] = f'end_stream{at}'
        first = min(f for f in range(at, end + 1) if f in blocks)
        manifest['endings'][str(at)]['streamDelayMs'] = round((first - at) * 1000 / 30)
# per-frame event sounds in endings
for at, end in ((3304, 3888), (3889, 4400), (4401, 4864)):
    ev = []
    fr = 1
    for code, body, raw in s.tags:
        if code == 1: fr += 1
        elif code == 15 and at <= fr <= end:
            sid = struct.unpack('<H', body[:2])[0]
            if str(sid) in manifest['sounds']: ev.append([round((fr - at) * 1000 / 30), manifest['sounds'][str(sid)]])
    manifest['endings'][str(at)]['events'] = ev
json.dump(sounds_json, open(f'{PACK}/assets/{NS}/sounds.json', 'w'), indent=1)
shutil.rmtree(tmp)

# ---------- meta ----------
json.dump({'pack': {'description': '띵호와 주방장1 - 짜장면 만들기 (Minecraft)', 'min_format': 97, 'max_format': 99}},
          open(f'{PACK}/pack.mcmeta', 'w'), ensure_ascii=False, indent=1)
icon = Image.open(MASK).convert('RGBA'); icon.thumbnail((128, 128))
ic = Image.new('RGBA', (128, 128), (0, 0, 0, 0)); ic.paste(icon, ((128 - icon.width) // 2, 0), icon); ic.save(f'{PACK}/pack.png')

# zip deterministically
zpath = os.path.join(OUT, 'ddinghowa_pack.zip')
with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in sorted(os.walk(PACK)):
        for fn in sorted(files):
            full = os.path.join(root, fn); arc = os.path.relpath(full, PACK)
            zi = zipfile.ZipInfo(arc, (2020, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, open(full, 'rb').read())
json.dump(manifest, open(os.path.join(OUT, 'assets.json'), 'w'), ensure_ascii=False, separators=(',', ':'))
tot = sum(Image.open(f'{PACK}/assets/{NS}/textures/item/{t}.png').size[0] * Image.open(f'{PACK}/assets/{NS}/textures/item/{t}.png').size[1] for t in frames)
print('textures', len(frames), 'px', tot, 'zip', os.path.getsize(zpath))
