"""전체화면 UI(HUD) 방식 리소스팩 빌더.

게임 화면의 모든 그림을 폰트 글리프(최대 240x240 타일)로 만들고, text.vsh 셰이더가
'신호 색'이 붙은 글리프를 화면 세로에 꽉 맞는 위치로 옮긴다.

usage: build_ui_pack.py config.json outdir
"""
import sys, os, json, struct, hashlib, base64, subprocess, zipfile, shutil, re
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image, ImageChops, ImageStat, ImageFont, ImageDraw
from swflib import Swf

CFG = json.load(open(sys.argv[1]))
OUT = sys.argv[2]
NS = 'ddinghowa'
PACK = os.path.join(OUT, 'pack')
shutil.rmtree(PACK, ignore_errors=True)
for d in ('textures/font', 'textures/item', 'models/item', 'items', 'sounds', 'font'):
    os.makedirs(f'{PACK}/assets/{NS}/{d}', exist_ok=True)
os.makedirs(f'{PACK}/assets/minecraft/shaders/core', exist_ok=True)
TMP = os.path.join(OUT, 'tmp'); shutil.rmtree(TMP, ignore_errors=True); os.makedirs(TMP)

TILE = 240
providers = []
manifest = {'font': f'{NS}:ui', 'stage': [550, 413], 'glyphAdv': {}, 'games': {}}

# ------------------------------------------------------------------ codepoints
_next_cp = [0xF0000]
def new_char():
    c = chr(_next_cp[0]); _next_cp[0] += 1
    return c

def f32(x): return np.float32(x)

def bitmap_advance(img_w, img_h, height, alpha):
    """BitmapProvider: advance = (int)(0.5 + actualWidth * scale) + 1, actualWidth = 마지막 불투명 열"""
    a = np.array(alpha)
    cols = np.nonzero(a.max(axis=0) != 0)[0]
    actual = int(cols[-1]) + 1 if len(cols) else 0
    scale = f32(height) / f32(img_h)
    return int(0.5 + float(f32(actual) * scale)) + 1

_tex_counter = [0]
def add_glyph_png(im, height):
    """im: RGBA 타일. height: 폰트 높이(=스테이지 px). returns char"""
    name = f't{_tex_counter[0]}'; _tex_counter[0] += 1
    im.save(f'{PACK}/assets/{NS}/textures/font/{name}.png', optimize=True)
    ch = new_char()
    providers.append({'type': 'bitmap', 'file': f'{NS}:font/{name}.png', 'ascent': 7, 'height': height, 'chars': [ch]})
    manifest['glyphAdv'][ch] = bitmap_advance(im.width, im.height, height, im.split()[3])
    return ch

def frac_m(scale):
    for m in range(1, 50):
        if abs(m / scale - round(m / scale)) < 1e-6: return m
    raise ValueError(scale)

_image_cache = {}
def add_image(im, scale):
    """이미지를 타일 글리프로. returns (tiles [[ch,dx,dy]], w_px, h_px)"""
    m = frac_m(scale)
    minh = int(np.ceil(7 * scale / m)) * m
    W = (im.width + m - 1) // m * m; H = max(minh, (im.height + m - 1) // m * m)
    if (W, H) != im.size:
        c = Image.new('RGBA', (W, H), (0, 0, 0, 0)); c.paste(im, (0, 0)); im = c
    key = hashlib.sha1(im.tobytes() + f'{im.size}{scale}'.encode()).hexdigest()
    if key in _image_cache: return _image_cache[key]
    tiles = []
    for ty in range(0, H, TILE):
        th = min(TILE, H - ty)
        if th < minh:   # 마지막 줄이 너무 얇으면 위로 겹치게
            ty = H - minh; th = minh
        for tx in range(0, W, TILE):
            tw = min(TILE, W - tx)
            t = im.crop((tx, ty, tx + tw, ty + th)).copy()
            if t.getbbox() is None: continue
            # 오른쪽 위 픽셀을 거의 투명(alpha 1)하게 찍어 advance 를 타일 폭으로 고정 (셰이더에서 버려짐)
            px = t.getpixel((tw - 1, 0))
            if px[3] == 0: t.putpixel((tw - 1, 0), (0, 0, 0, 1))
            ch = add_glyph_png(t, round(th / scale))
            tiles.append([ch, round(tx / scale, 3), round(ty / scale, 3)])
    res = (tiles, round(W / scale, 3), round(H / scale, 3))
    _image_cache[key] = res
    return res

def mask_of(im, wpx, hpx):
    mw, mh = max(1, round(wpx)), max(1, round(hpx))
    a = im.split()[3].resize((mw, mh), Image.BILINEAR)
    arr = np.array(a) > 40
    bits = np.packbits(arr.flatten(), bitorder='little')
    return {'w': mw, 'h': mh, 'bits': base64.b64encode(bits.tobytes()).decode()}

# ------------------------------------------------------------------ 글자 / 상자 / 백스페이스
def build_solids():
    sol = {}
    for nm, rgba, h in (('red', CFG['gauge_red'], 9), ('dark', [0, 0, 0, 150], 13)):
        pieces = {}
        w = 1
        while w <= 512:
            img = Image.new('RGBA', (w, h), tuple(rgba))
            img.putpixel((w - 1, 0), tuple(rgba[:3]) + (max(1, rgba[3]),))
            name = f's_{nm}_{w}'
            img.save(f'{PACK}/assets/{NS}/textures/font/{name}.png')
            cp = new_char()
            providers.append({'type': 'bitmap', 'file': f'{NS}:font/{name}.png', 'ascent': 7, 'height': h, 'chars': [cp]})
            pieces[str(w)] = [cp, bitmap_advance(w, h, h, img.split()[3])]
            w *= 2
        sol[nm] = {'h': h, 'pieces': pieces}
    manifest['solids'] = sol

def build_backspaces():
    adv = {}; back = {}
    for i in range(12):
        cp = chr(0xE000 + i); adv[cp] = -(1 << i); back[str(1 << i)] = cp
    adv[' '] = 4
    providers.insert(0, {'type': 'space', 'advances': adv})
    manifest['back'] = back

# ------------------------------------------------------------------ 사운드
sounds_json = {}
_sound_by_hash = {}
def to_ogg(src, name, extra=()):
    dst = f'{TMP}/{name}.ogg'
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', src, *extra, '-ac', '1', '-ar', '44100',
                    '-c:a', 'libvorbis', '-q:a', '3', dst], check=True)
    data = open(dst, 'rb').read()
    h = hashlib.sha1(data).hexdigest()
    if h in _sound_by_hash: return _sound_by_hash[h]
    open(f'{PACK}/assets/{NS}/sounds/{name}.ogg', 'wb').write(data)
    sounds_json[name] = {'sounds': [{'name': f'{NS}:{name}', 'stream': True}]}
    _sound_by_hash[h] = name
    return name

def adpcm_to_wav(data, rate, path):
    bits = 0; nbits = 0; pos = 0
    def rd(n):
        nonlocal bits, nbits, pos
        while nbits < n:
            bits = (bits << 8) | (data[pos] if pos < len(data) else 0); pos += 1; nbits += 8
        v = (bits >> (nbits - n)) & ((1 << n) - 1); nbits -= n; return v
    STEP = [7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767]
    IDX = {2: [-1, 2], 3: [-1, -1, 2, 4], 4: [-1, -1, -1, -1, 2, 4, 6, 8], 5: [-1]*8 + [1, 2, 4, 6, 8, 10, 13, 16]}
    out = []; bp = rd(2) + 2; total = len(data) * 8
    while (pos * 8 - nbits) + 22 < total:
        s = rd(16); s = s - 65536 if s >= 32768 else s; idx = rd(6); out.append(s)
        for _ in range(4095):
            if (pos * 8 - nbits) + bp > total: break
            code = rd(bp); step = STEP[idx]; sign = 1 << (bp - 1); mag = code & (sign - 1)
            diff = step >> (bp - 1); k = sign >> 1; st = step
            while k:
                if mag & k: diff += st
                st >>= 1; k >>= 1
            s = max(-32768, min(32767, s - diff if code & sign else s + diff))
            idx = max(0, min(88, idx + IDX[bp][mag])); out.append(s)
    import wave
    w = wave.open(path, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
    w.writeframes(b''.join(struct.pack('<h', v) for v in out)); w.close()

def extract_sounds(g, gid, swf):
    sounds = {}
    for code, body, raw in swf.tags:
        if code != 14: continue
        sid = struct.unpack('<H', body[:2])[0]
        name = g['sounds'].get(str(sid))
        if not name: continue
        f = body[2]; fmt = f >> 4; rate = [5512, 11025, 22050, 44100][(f >> 2) & 3]
        if fmt == 2:
            p = f'{TMP}/{gid}_{sid}.mp3'; open(p, 'wb').write(body[9:])
        elif fmt == 1:
            p = f'{TMP}/{gid}_{sid}.wav'; adpcm_to_wav(body[7:], rate, p)
        else: continue
        tr = g.get('trims', {}).get(str(sid))
        sounds[name] = to_ogg(p, f'{gid}_{name}', ('-ss', str(tr[0]), '-to', str(tr[1])) if tr else ())
        sounds['sid' + str(sid)] = sounds[name]
    # 메인 타임라인 스트림(시연/엔딩 음악)
    blocks = {}; fr = 1
    for code, body, raw in swf.tags:
        if code == 1: fr += 1
        elif code == 19: blocks[fr] = body[4:]
    return sounds, blocks

def stream_ogg(blocks, a, b, name):
    data = b''.join(blocks[f] for f in range(a, b + 1) if f in blocks)
    if not data: return None, 0
    p = f'{TMP}/{name}.mp3'; open(p, 'wb').write(data)
    first = min(f for f in range(a, b + 1) if f in blocks)
    return to_ogg(p, name), round((first - a) * 1000 / 30)

def timeline_events(swf, a, b, sounds):
    """메인 타임라인 StartSound: [ms, sound] / loops 가 큰 것은 bgm"""
    ev, bgm = [], None
    fr = 1
    for code, body, raw in swf.tags:
        if code == 1: fr += 1
        elif code == 15 and a <= fr <= b:
            sid = struct.unpack('<H', body[:2])[0]; fl = body[2]; p = 3
            if fl & 1: p += 4
            if fl & 2: p += 4
            loops = struct.unpack('<H', body[p:p + 2])[0] if fl & 4 else 1
            nm = sounds.get('sid' + str(sid))
            if not nm: continue
            ms = round((fr - a) * 1000 / 30)
            if loops > 1 and bgm is None: bgm = [ms, nm]
            else: ev.append([ms, nm])
    return ev, bgm

# ------------------------------------------------------------------ 게임별
def build_game(gid, g):
    swf = Swf(g['swf'])
    gm = {'frames': {}, 'groups': {}, 'masks': {}, 'endings': {}}
    S = g['scale']
    groups = json.load(open(os.path.join(g['cap'], 'groups.json')))
    def frame(fid, im, x, y, scale, clickable=False):
        tiles, w, h = add_image(im, scale)
        gm['frames'][fid] = {'x': round(x, 3), 'y': round(y, 3), 'w': w, 'h': h, 't': tiles}
        if clickable: gm['masks'][fid] = mask_of(im, im.width / scale, im.height / scale)
    for grp, names in groups.items():
        out = {}
        for n in names:
            im = Image.open(os.path.join(g['cap'], f'{grp}__{n}.png')).convert('RGBA')
            bb = im.getbbox()
            if not bb: continue
            fid = f'{gid}:{grp}:{n}'
            frame(fid, im.crop(bb), bb[0] / S, bb[1] / S, S,
                  clickable=grp.startswith(('ing_', 'dish1', 'dish2', 'btn_', 'help', 'treeclose')))
            out[n] = fid
        gm['groups'][grp] = out
    # 게이지 빨간 막대 위치
    gf = Image.open(os.path.join(g['cap'], 'gaugefull__0.png')).convert('RGB')
    ge = Image.open(os.path.join(g['cap'], 'gauge__0.png')).convert('RGB')
    bb = ImageChops.difference(gf, ge).convert('L').point(lambda v: 255 if v > 60 else 0).getbbox()
    gm['gaugeBar'] = {'x': bb[0] / S, 'y': bb[1] / S, 'w': (bb[2] - bb[0]) / S, 'h': (bb[3] - bb[1]) / S}
    gm['groups'].pop('gaugefull', None)
    gm['gaugeFrames'] = g['gauge_frames']
    sounds, blocks = extract_sounds(g, gid, swf)
    gm['sounds'] = {k: v for k, v in sounds.items() if not k.startswith('sid')}
    # 엔딩
    ES = 0.5
    for at, end in g['endings']:
        fs = sorted(f for f in os.listdir(g['end']) if f.startswith(f'e{at}_'))
        seq = []; prev = None
        for f in fs:
            im = Image.open(os.path.join(g['end'], f)).convert('RGB')
            if prev is not None and sum(ImageStat.Stat(ImageChops.difference(im, prev)).mean) / 3 < 1.5:
                seq[-1][1] += 250; continue
            prev = im
            small = im.resize((round(550 * ES), round(413 * ES)), Image.LANCZOS).convert('RGBA')
            fid = f'{gid}:end{at}:{len(seq)}'
            frame(fid, small, 0, 0, ES)
            seq.append([fid, 250])
        fin = Image.open(os.path.join(g['end'], f'final{at}.png')).convert('RGBA').crop((0, 0, 825, 620))
        frame(f'{gid}:end{at}:final', fin, 0, 0, 1.5)
        e = {'frames': seq, 'final': f'{gid}:end{at}:final'}
        st, delay = stream_ogg(blocks, at, end, f'{gid}_end{at}')
        if st: e['stream'] = st; e['streamDelayMs'] = delay
        ev, bgm = timeline_events(swf, at, end, sounds)
        e['events'] = ev + ([bgm] if bgm else [])
        gm['endings'][str(at)] = e
    gm['endingOrder'] = [str(a) for a, b in g['endings']]
    # 시연
    a, b = g['demo_range']
    DS = 0.6
    DW, DH = round(550 * DS), round(413 * DS)
    fs = sorted(f for f in os.listdir(g['demo']) if f.startswith('d_') and f.endswith('.png'))
    seq = []; prev = None; base_img = None; base_id = None
    for i, f in enumerate(fs):
        if a + 6 * i > b: break
        im = Image.open(os.path.join(g['demo'], f)).convert('RGB').crop((0, 0, 550, 413)).resize((DW, DH), Image.LANCZOS)
        if prev is not None and sum(ImageStat.Stat(ImageChops.difference(im, prev)).mean) / 3 < 0.4:
            seq[-1][2] += 200; continue
        prev = im
        bbx = None
        if base_img is not None:
            bbx = ImageChops.difference(im, base_img).convert('L').point(lambda v: 255 if v > 10 else 0).getbbox()
        if base_img is None or (bbx and (bbx[2] - bbx[0]) * (bbx[3] - bbx[1]) > 0.5 * DW * DH):
            base_img = im
            base_id = f'{gid}:demo:{len(seq)}'
            frame(base_id, im.convert('RGBA'), 0, 0, DS)
            seq.append([base_id, None, 200]); continue
        if not bbx:
            seq.append([base_id, None, 200]); continue
        x0, y0, x1, y1 = bbx
        x0 -= x0 % 3; y0 -= y0 % 3
        crop = im.crop((x0, y0, x1, y1)).convert('RGBA')
        oid = f'{gid}:demo:{len(seq)}o'
        frame(oid, crop, x0 / DS, y0 / DS, DS)
        seq.append([base_id, oid, 200])
    st, delay = stream_ogg(blocks, a, b, f'{gid}_demo')
    ev, bgm = timeline_events(swf, a, b, sounds)
    gm['demo'] = {'frames': seq, 'stream': st, 'streamDelayMs': delay, 'events': ev,
                  'bgm': bgm[1] if bgm else None, 'bgmDelayMs': bgm[0] if bgm else 0,
                  'bgmPeriodMs': g.get('bgm_period_ms', 4080), 'button': gm['groups']['btn_qstart']['up']}
    gm['title'] = g['title']
    manifest['games'][gid] = gm
    print(gid, 'frames', len(gm['frames']))

# ------------------------------------------------------------------ main
# 폰트 cmap 확인용
from fontTools.ttLib import TTFont
build_backspaces()
fonts_sorted = sorted(os.listdir(CFG['fonts']))
cm = {}
_fobjs = [ImageFont.truetype(os.path.join(CFG['fonts'], f), 26) for f in fonts_sorted]
for f, fo in zip(fonts_sorted, _fobjs):
    cm[id(fo)] = set(chr(c) for c in TTFont(os.path.join(CFG['fonts'], f)).getBestCmap())
CFG['_cmaps'] = cm
def _font_for(ch):
    for fo in _fobjs:
        if ch in cm[id(fo)]: return fo
    return _fobjs[-1]
# build_text_glyphs 내부 font_for 대체
def build_text_glyphs2():
    chars = {}
    TEXT_H, IMG_H = 10, 30
    text = ''.join(chr(c) for c in range(0x21, 0x7F)) + CFG['text_chars']
    for ch in dict.fromkeys(text):
        f = _font_for(ch)
        bbox = f.getbbox(ch)
        w = max(1, bbox[2] + 2)
        img = Image.new('RGBA', (w, IMG_H), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        for ox, oy in ((-1, 0), (1, 0), (0, -1), (0, 1), (1, 1), (2, 2)):
            d.text((1 + ox, -1 + oy), ch, font=f, fill=(30, 20, 10, 255))
        d.text((1, -1), ch, font=f, fill=(255, 255, 255, 255))
        if img.getbbox() is None: continue
        cp = new_char()
        name = f'c{ord(ch):x}'
        img.save(f'{PACK}/assets/{NS}/textures/font/{name}.png')
        providers.append({'type': 'bitmap', 'file': f'{NS}:font/{name}.png', 'ascent': 7, 'height': TEXT_H, 'chars': [cp]})
        chars[ch] = [cp, bitmap_advance(img.width, img.height, TEXT_H, img.split()[3])]
    manifest['text'] = {'h': TEXT_H, 'chars': chars, 'space': 4}
build_text_glyphs2()
build_solids()
for gid, g in CFG['games'].items():
    build_game(gid, g)

json.dump({'providers': providers}, open(f'{PACK}/assets/{NS}/font/ui.json', 'w'), ensure_ascii=False)
shutil.copy(CFG['shader'], f'{PACK}/assets/minecraft/shaders/core/text.vsh')

# 손을 숨기는 투명 아이템, 주방장 가면
json.dump({'model': {'type': 'minecraft:empty'}}, open(f'{PACK}/assets/{NS}/items/invisible.json', 'w'))
m = Image.open(CFG['mask']).convert('RGBA')
m = m.resize((round(m.width * 512 / m.height), 512), Image.LANCZOS)
r, gg, b, a = m.split()
r, gg, b = (ch.point(lambda v: min(255, round(v * 1.3))) for ch in (r, gg, b))
m = Image.merge('RGBA', (r, gg, b, a))
sq = Image.new('RGBA', (512, 512), (0, 0, 0, 0)); sq.paste(m, ((512 - m.width) // 2, 0), m)
sq.save(f'{PACK}/assets/{NS}/textures/item/chef_mask.png')
aspect = m.width / m.height
mw = 18.0; mh = mw / aspect; top = 7.5 + 0.52 * mh; x0 = 8 - mw / 2; u0 = (1 - aspect) / 2 * 16; u1 = 16 - u0
json.dump({
    'textures': {'t': f'{NS}:item/chef_mask', 'particle': f'{NS}:item/chef_mask'},
    'elements': [{'from': [x0, top - mh, 1.0], 'to': [x0 + mw, top, 1.0],
                  'faces': {'north': {'uv': [u0, 0, u1, 16], 'texture': '#t'}, 'south': {'uv': [u1, 0, u0, 16], 'texture': '#t'}}}],
    'display': {
        'gui': {'rotation': [0, 180, 0], 'translation': [0, 1.5, 0], 'scale': [0.55, 0.55, 0.55]},
        'ground': {'rotation': [0, 180, 0], 'translation': [0, 2, 0], 'scale': [0.4, 0.4, 0.4]},
        'fixed': {'rotation': [0, 180, 0], 'translation': [0, 1.5, 0], 'scale': [0.6, 0.6, 0.6]},
        'thirdperson_righthand': {'rotation': [0, 180, 0], 'translation': [0, 2, 1], 'scale': [0.3, 0.3, 0.3]},
        'thirdperson_lefthand': {'rotation': [0, 180, 0], 'translation': [0, 2, 1], 'scale': [0.3, 0.3, 0.3]},
        'firstperson_righthand': {'rotation': [0, 200, 0], 'translation': [2, 4, -2], 'scale': [0.07, 0.07, 0.07]},
        'firstperson_lefthand': {'rotation': [0, 160, 0], 'translation': [-2, 4, -2], 'scale': [0.07, 0.07, 0.07]}}
}, open(f'{PACK}/assets/{NS}/models/item/chef_mask.json', 'w'))
json.dump({'model': {'type': 'minecraft:model', 'model': f'{NS}:item/chef_mask'}}, open(f'{PACK}/assets/{NS}/items/chef_mask.json', 'w'))

json.dump(sounds_json, open(f'{PACK}/assets/{NS}/sounds.json', 'w'), indent=1)
json.dump({'pack': {'description': '띵호와 주방장 1·2 (짜장면 / 짬뽕) - 전체화면 UI', 'min_format': 97, 'max_format': 99}},
          open(f'{PACK}/pack.mcmeta', 'w'), ensure_ascii=False, indent=1)
icon = Image.open(CFG['mask']).convert('RGBA'); icon.thumbnail((128, 128))
ic = Image.new('RGBA', (128, 128), (0, 0, 0, 0)); ic.paste(icon, ((128 - icon.width) // 2, 0), icon); ic.save(f'{PACK}/pack.png')

zpath = os.path.join(OUT, 'ddinghowa_pack.zip')
with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in sorted(os.walk(PACK)):
        for fn in sorted(files):
            full = os.path.join(root, fn); arc = os.path.relpath(full, PACK)
            zi = zipfile.ZipInfo(arc, (2020, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, open(full, 'rb').read())
json.dump(manifest, open(os.path.join(OUT, 'assets.json'), 'w'), ensure_ascii=False, separators=(',', ':'))
shutil.rmtree(TMP)
npx = sum(Image.open(f'{PACK}/assets/{NS}/textures/font/{f}').size[0] * Image.open(f'{PACK}/assets/{NS}/textures/font/{f}').size[1]
          for f in os.listdir(f'{PACK}/assets/{NS}/textures/font'))
print('glyphs', len(providers), 'px', npx, 'zip', os.path.getsize(zpath))
