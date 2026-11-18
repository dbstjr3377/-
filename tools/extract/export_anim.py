"""심사받으러 짜장/짬뽕을 가져가는 장면에서 주방장이 고개를 좌우로 흔드는 반복 애니메이션을
PNG 묶음 + 스프라이트 시트 + 딜레이 명시 파일(JSON) + 미리보기 GIF 로 내보낸다.

usage: export_anim.py <capture dir> <out dir>
캡처: c{k}.png / g2c{k}.png (전신), g1h{k}.png / g2h{k}.png (머리만), k=1..8, 2배 해상도
"""
import json, os, sys, hashlib
from PIL import Image
SRC, OUT = sys.argv[1], sys.argv[2]
FPS, SCALE, N = 30, 2, 8
SETS = [
    ('jjajang/full', 'c{k}', 'ddinghowa1.swf', 530, '짜장면 엔딩 시작: 주인공이 짜장을 들고 오는 동안 테이블 뒤 주방장 (전신)'),
    ('jjajang/head', 'g1h{k}', 'ddinghowa1.swf', 530, '짜장면 엔딩 시작: 주인공이 짜장을 들고 오는 동안 테이블 뒤 주방장 (머리만)'),
    ('jjamppong/full', 'g2c{k}', 'ddinghowa2.swf', 531, '짬뽕 엔딩 시작: 주인공이 짬뽕을 들고 오는 동안 테이블 뒤 주방장 (전신)'),
    ('jjamppong/head', 'g2h{k}', 'ddinghowa2.swf', 531, '짬뽕 엔딩 시작: 주인공이 짬뽕을 들고 오는 동안 테이블 뒤 주방장 (머리만)'),
]
SCENE = {'ddinghowa1.swf': [[3304, 3376], [3889, 3961], [4401, 4473]], 'ddinghowa2.swf': [[3351, 3423], [3946, 4018], [4380, 4452]]}
for sub, pat, swf, cid, desc in SETS:
    ims = [Image.open(os.path.join(SRC, pat.format(k=k) + '.png')).convert('RGBA') for k in range(1, N + 1)]
    bbs = [im.getbbox() for im in ims]
    u = (min(b[0] for b in bbs), min(b[1] for b in bbs), max(b[2] for b in bbs), max(b[3] for b in bbs))
    d = os.path.join(OUT, sub); os.makedirs(d, exist_ok=True)
    frames, prev = [], None
    for i, im in enumerate(ims):
        c = im.crop(u)
        h = hashlib.md5(c.tobytes()).hexdigest()
        if h == prev:
            frames[-1]['delay_ms'] += 1000 / FPS
            frames[-1]['sprite_frames'][1] = i + 1
            continue
        prev = h
        name = f'chef_headshake_{len(frames):02d}.png'
        c.save(os.path.join(d, name), optimize=True)
        frames.append({'file': name, 'delay_ms': 1000 / FPS, 'sprite_frames': [i + 1, i + 1]})
    for f in frames:
        f['delay_ms'] = round(f['delay_ms'], 1)
        f['delay_ticks'] = round(f['delay_ms'] / 50, 2)
    w, hh = u[2] - u[0], u[3] - u[1]
    sheet = Image.new('RGBA', (w * len(frames), hh), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        sheet.alpha_composite(Image.open(os.path.join(d, f['file'])), (i * w, 0))
    sheet.save(os.path.join(d, 'chef_headshake_sheet.png'), optimize=True)
    spec = {
        'name': 'chef_headshake',
        'description': desc + ' - 웃으며 고개를 좌우로 흔드는 동작. 무한 반복(loop).',
        'source': {'swf': f'original/{swf}', 'sprite_id': cid, 'sprite_frames': N, 'swf_fps': FPS,
                   'scene_main_timeline_frames': SCENE[swf], 'placement_in_stage_px': [325.15, 33.0]},
        'frame_size': [w, hh], 'render_scale': SCALE,
        'anchor_in_stage_px': [round(u[0] / SCALE, 2), round(u[1] / SCALE, 2)],
        'loop': True,
        'total_ms': round(sum(f['delay_ms'] for f in frames), 1),
        'sheet': {'file': 'chef_headshake_sheet.png', 'layout': 'horizontal', 'frame_count': len(frames)},
        'frames': frames,
    }
    json.dump(spec, open(os.path.join(d, 'chef_headshake.anim.json'), 'w'), ensure_ascii=False, indent=2)
    gif = []
    for f in frames:
        bg = Image.new('RGBA', (w, hh), (255, 248, 200, 255)); bg.alpha_composite(Image.open(os.path.join(d, f['file'])))
        gif.append(bg.convert('RGB').resize((w // 2, hh // 2)))
    # 한 바퀴가 짧아 GIF 는 같은 순서를 3번 이어 붙임
    gif[0].save(os.path.join(d, 'preview.gif'), save_all=True, append_images=(gif * 3)[1:],
                duration=[f['delay_ms'] for f in frames] * 3, loop=0)
    print(sub, len(frames), 'frames', w, 'x', hh, [f['delay_ms'] for f in frames])
