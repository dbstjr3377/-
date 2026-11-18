"""완벽 엔딩 심사 장면의 '주방장 고개 좌우로 흔들기' 루프를 PNG 묶음 + 애니메이션 명시 파일로 내보낸다."""
import json, os, sys, hashlib
from PIL import Image
SRC, OUT = sys.argv[1], sys.argv[2]
FIRST, LAST, FPS, SCALE = 3770, 3794, 30, 2
for kind, prefix, desc in (('full', 'c', '주방장 전신 (머리 + 몸 + 팔)'), ('head', 'hd', '주방장 머리만')):
    ims = [Image.open(os.path.join(SRC, f'{prefix}{f}.png')).convert('RGBA') for f in range(FIRST, LAST + 1)]
    bbs = [im.getbbox() for im in ims]
    u = (min(b[0] for b in bbs), min(b[1] for b in bbs), max(b[2] for b in bbs), max(b[3] for b in bbs))
    d = os.path.join(OUT, kind); os.makedirs(d, exist_ok=True)
    frames, prev = [], None
    for i, im in enumerate(ims):
        c = im.crop(u)
        h = hashlib.md5(c.tobytes()).hexdigest()
        if h == prev:
            frames[-1]['delay_ms'] += round(1000 / FPS, 3)
            frames[-1]['source_frames'][1] = FIRST + i
            continue
        prev = h
        name = f'chef_headshake_{len(frames):02d}.png'
        c.save(os.path.join(d, name), optimize=True)
        frames.append({'file': name, 'delay_ms': round(1000 / FPS, 3), 'source_frames': [FIRST + i, FIRST + i]})
    for f in frames:
        f['delay_ms'] = round(f['delay_ms'], 1)
        f['delay_ticks'] = round(f['delay_ms'] / 50, 2)
    w, h = u[2] - u[0], u[3] - u[1]
    # 가로 스프라이트 시트
    sheet = Image.new('RGBA', (w * len(frames), h), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        sheet.alpha_composite(Image.open(os.path.join(d, f['file'])), (i * w, 0))
    sheet.save(os.path.join(d, 'chef_headshake_sheet.png'), optimize=True)
    spec = {
        'name': 'chef_headshake',
        'description': f'띵호와 주방장1 완벽 엔딩 심사 장면 - 주방장이 웃으며 고개를 좌우로 흔드는 동작 ({desc}). 무한 반복(loop) 애니메이션.',
        'source': {'swf': 'original/ddinghowa1.swf', 'timeline': 'main', 'frames': [FIRST, LAST], 'swf_fps': FPS,
                   'layers': 'depth 3(팔), 5(몸), 17(머리)' if kind == 'full' else 'depth 17(머리)'},
        'frame_size': [w, h], 'render_scale': SCALE,
        'anchor_in_stage_px': [round(u[0] / SCALE, 2), round(u[1] / SCALE, 2)],
        'loop': True,
        'total_ms': round(sum(f['delay_ms'] for f in frames), 1),
        'sheet': {'file': 'chef_headshake_sheet.png', 'layout': 'horizontal', 'frame_count': len(frames)},
        'frames': frames,
    }
    json.dump(spec, open(os.path.join(d, 'chef_headshake.anim.json'), 'w'), ensure_ascii=False, indent=2)
    # 미리보기 GIF (투명 → 연한 배경)
    gif = []
    for f in frames:
        bg = Image.new('RGBA', (w, h), (255, 248, 200, 255)); bg.alpha_composite(Image.open(os.path.join(d, f['file'])))
        gif.append(bg.convert('RGB').resize((w // 2, h // 2)))
    gif[0].save(os.path.join(d, 'preview.gif'), save_all=True, append_images=gif[1:],
                duration=[f['delay_ms'] for f in frames], loop=0)
    print(kind, len(frames), 'frames', w, 'x', h)
