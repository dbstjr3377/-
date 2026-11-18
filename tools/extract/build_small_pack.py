"""스트리밍(플래시 연결) 버전용 작은 리소스팩.

들어가는 것
- ddinghowa:invisible      손을 숨기는 투명 아이템 (게임 화면을 보는 동안 손에 쥐여 줌)
- ddinghowa:chef_mask      띵호와 주방장 가면 (얼굴 앞 평면)
- ddinghowa:chef_headshake 고개를 좌우로 흔드는 주방장 얼굴 (가면처럼 착용, 애니메이션 텍스처)
- ddinghowa:jjamppong      짬뽕 게임의 완성된 짬뽕 한 그릇 스프라이트

사용: python3 build_small_pack.py <저장소 루트> <짬뽕 스프라이트 png> <출력 zip>
"""
import json, os, sys, zipfile, io
from PIL import Image

ROOT, JJAMPPONG, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
NS = 'ddinghowa'
files = {}


def put_json(path, obj):
    files[path] = json.dumps(obj, ensure_ascii=False, indent=1).encode('utf-8')


def put_png(path, im):
    b = io.BytesIO(); im.save(b, 'PNG', optimize=True); files[path] = b.getvalue()


def brighten(im, k=1.3):
    r, g, b, a = im.split()
    r, g, b = (ch.point(lambda v: min(255, round(v * k))) for ch in (r, g, b))
    return Image.merge('RGBA', (r, g, b, a))


def face_square(im, size):
    """세로를 size 에 맞추고 가운데 정렬한 정사각형"""
    im = im.resize((round(im.width * size / im.height), size), Image.LANCZOS)
    sq = Image.new('RGBA', (size, size), (0, 0, 0, 0)); sq.paste(im, ((size - im.width) // 2, 0), im)
    return sq


def face_model(tex, aspect):
    """얼굴 앞에 붙는 평면 (머리 네모보다 커도 됨) – 기존 가면과 같은 배치"""
    mw = 18.0; mh = mw / aspect; top = 7.5 + 0.52 * mh; x0 = 8 - mw / 2
    u0 = (1 - aspect) / 2 * 16; u1 = 16 - u0
    return {
        'textures': {'t': tex, 'particle': tex},
        'elements': [{'from': [x0, top - mh, 1.0], 'to': [x0 + mw, top, 1.0],
                      'faces': {'north': {'uv': [u0, 0, u1, 16], 'texture': '#t'},
                                'south': {'uv': [u1, 0, u0, 16], 'texture': '#t'}}}],
        'display': {
            'gui': {'rotation': [0, 180, 0], 'translation': [0, 1.5, 0], 'scale': [0.55, 0.55, 0.55]},
            'ground': {'rotation': [0, 180, 0], 'translation': [0, 2, 0], 'scale': [0.4, 0.4, 0.4]},
            'fixed': {'rotation': [0, 180, 0], 'translation': [0, 1.5, 0], 'scale': [0.6, 0.6, 0.6]},
            'thirdperson_righthand': {'rotation': [0, 180, 0], 'translation': [0, 2, 1], 'scale': [0.3, 0.3, 0.3]},
            'thirdperson_lefthand': {'rotation': [0, 180, 0], 'translation': [0, 2, 1], 'scale': [0.3, 0.3, 0.3]},
            'firstperson_righthand': {'rotation': [0, 200, 0], 'translation': [2, 4, -2], 'scale': [0.07, 0.07, 0.07]},
            'firstperson_lefthand': {'rotation': [0, 160, 0], 'translation': [-2, 4, -2], 'scale': [0.07, 0.07, 0.07]}}}


def item_def(name):
    put_json(f'assets/{NS}/items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{name}'}})


# 1) 손 숨기기
put_json(f'assets/{NS}/items/invisible.json', {'model': {'type': 'minecraft:empty'}})

# 2) 주방장 가면
mask = brighten(Image.open(f'{ROOT}/tools/extract/mask_head.png').convert('RGBA'))
put_png(f'assets/{NS}/textures/item/chef_mask.png', face_square(mask, 512))
put_json(f'assets/{NS}/models/item/chef_mask.json', face_model(f'{NS}:item/chef_mask', mask.width / mask.height))
item_def('chef_mask')

# 3) 고개 흔드는 주방장 얼굴 (짜장면 심사 장면, 머리만) – 66.7 / 66.7 / 133.3 ms 반복
hs_dir = f'{ROOT}/docs/animations/chef_headshake/jjajang/head'
frames = [brighten(Image.open(f'{hs_dir}/chef_headshake_{i:02d}.png').convert('RGBA')) for i in range(3)]
S = 512
strip = Image.new('RGBA', (S, S * 3), (0, 0, 0, 0))
for i, f in enumerate(frames):
    strip.paste(face_square(f, S), (0, S * i))
put_png(f'assets/{NS}/textures/item/chef_headshake.png', strip)
# 마인크래프트 애니메이션은 틱(50ms) 단위: 3번 반복(800ms = 16틱)에 맞춰 반올림 → 평균 속도가 원본과 같음
put_json(f'assets/{NS}/textures/item/chef_headshake.png.mcmeta', {'animation': {'frames': [
    {'index': 0, 'time': 1}, {'index': 1, 'time': 2}, {'index': 2, 'time': 2},
    {'index': 0, 'time': 2}, {'index': 1, 'time': 1}, {'index': 2, 'time': 3},
    {'index': 0, 'time': 1}, {'index': 1, 'time': 1}, {'index': 2, 'time': 3}]}})
put_json(f'assets/{NS}/models/item/chef_headshake.json', face_model(f'{NS}:item/chef_headshake', frames[0].width / frames[0].height))
item_def('chef_headshake')

# 4) 짬뽕 한 그릇
bowl = Image.open(JJAMPPONG).convert('RGBA')
bowl = bowl.crop(bowl.getbbox())
side = max(bowl.size)
sq = Image.new('RGBA', (side, side), (0, 0, 0, 0)); sq.paste(bowl, ((side - bowl.width) // 2, (side - bowl.height) // 2), bowl)
sq = sq.resize((256, 256), Image.LANCZOS)
put_png(f'assets/{NS}/textures/item/jjamppong.png', sq)
put_json(f'assets/{NS}/models/item/jjamppong.json', {
    'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/jjamppong'},
    'display': {'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1.4, 1.4, 1.4]}}})
item_def('jjamppong')

put_json('pack.mcmeta', {'pack': {'description': '띵호와 주방장 - 가면 / 고개 흔드는 주방장 / 짬뽕', 'min_format': 97, 'max_format': 99}})
icon = mask.copy(); icon.thumbnail((128, 128))
ic = Image.new('RGBA', (128, 128), (0, 0, 0, 0)); ic.paste(icon, ((128 - icon.width) // 2, 0), icon)
put_png('pack.png', ic)

with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in sorted(files):
        zi = zipfile.ZipInfo(path, (2020, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, files[path])
print(OUT, os.path.getsize(OUT), 'bytes')
for p in sorted(files): print(' ', p, len(files[p]))
