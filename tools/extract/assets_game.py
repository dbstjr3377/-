"""게임별 설정으로 스프라이트 상태 캡처 작업(jobs.json)을 만든다.
usage: assets_game.py <g1|g2> <web dir> <out dir>"""
import sys, json, os
sys.path.insert(0, os.path.dirname(__file__))
from swflib import Swf, display_list_timeline
from mini import Mini, compose, I
from variant import A

SPECS = {
    'g1': dict(swf='dl/ddinghowa1.swf', frame=3278,
               bg=[(490, None), (491, None), (57, 4), (505, None), (293, 165), (294, 169)],
               chef=6, ing={'vegetable': 54, 'meat': 58, 'noodle': 68, 'garlic': 74, 'oil': 84, 'sugar': 91, 'chun': 96, 'kettle': 121},
               dishes={'dish1': 63, 'dish2': 66}, pot=113, pan=140, dish3=125, gauge=144,
               btn={'complete': 161, 'takeOut': 170, 'takeOff': 174}, arrow=178, help=181, tree=187, tree_close_depth=247,
               hand=419, drag_chun=509, drag_sugar=510, retry=627, qstart=272,
               pours={'kettle': (2, 24, 2), 'chun': (8, 7, 1), 'sugar': (13, 5, 1), 'oil': (18, 30, 2), 'meat': (24, 13, 1), 'vegetable': (31, 11, 1)},
               broil_first=36, gauge_frames=300, pan_default=1),
    'g2': dict(swf='dl/ddinghowa2.swf', frame=3150,
               bg=[(470, None), (53, 3), (55, 4), (55, 11), (57, 18), (55, 20), (55, 27), (484, None), (300, 192), (301, 196)],
               chef=34, ing={'vegetable': 82, 'seaFood': 125, 'noodle': 88, 'oil': 94, 'sugar': 101, 'chun': 106, 'eggM': 116, 'kettle': 180},
               dishes={'dish1': 144, 'dish2': 86}, pot=184, pan=176, dish3=147, gauge=197,
               btn={'complete': 214, 'takeOut': 218, 'takeOff': 222}, arrow=237, help=242, tree=248, tree_close_depth=None,
               hand=392, drag_chun=417, drag_sugar=420, retry=625, qstart=293,
               pours={'kettle': (3, 24, 2), 'chun': (9, 7, 1), 'sugar': (14, 5, 1), 'oil': (19, 30, 2), 'meat': (25, 13, 1),
                      'vegetable': (32, 11, 1), 'seaFood': (37, 11, 1), 'boilingSeaFood': (44, 10, 1)},
               broil_first=53, gauge_frames=139, pan_default=2),
}

gid, WEB, OUT = sys.argv[1:4]
G = SPECS[gid]
SRC = G['swf']
S = 1.5
s = Swf(SRC); M = Mini(SRC)
dl = display_list_timeline(s.tags, G['frame'])
def m(d): return dl[d].get('matrix') or I
def cid(d): return dl[d]['cid']
os.makedirs(f'{WEB}/a{gid}', exist_ok=True); os.makedirs(OUT, exist_ok=True)
jobs = []; groups = {}

def flat(c, frame, parentM, names=None):
    names = names or {}
    out = []
    for d, r in sorted(display_list_timeline(s.sprites[c][1], frame).items()):
        if 'clipdepth' in r: continue
        out.append((r['cid'], compose(parentM, r.get('matrix') or I), names.get(d)))
    return out

def child_depths(c, frame):
    return {d: r['cid'] for d, r in display_list_timeline(s.sprites[c][1], frame).items()}

def job(group, fname, places, actions=None, hover=None):
    fn = f'{group}__{fname}'
    M.build(places, actions, out=f'{WEB}/a{gid}/{fn}.swf')
    j = {'swf': f'a{gid}/{fn}.swf', 'out': f'{OUT}/{fn}.png', 'scale': S, 'delay': 250}
    if hover: j['hover'] = hover
    jobs.append(j); groups.setdefault(group, []).append(fname)

job('bg', '0', [(c, m(d) if d else I, None) for c, d in G['bg']])
job('gauge', '0', [(cid(G['gauge']), m(G['gauge']), 'T')], A().tgoto('/T/gaugeMask', G['gauge_frames']))
job('gaugefull', '0', [(cid(G['gauge']), m(G['gauge']), 'T')], A().tgoto('/T/gaugeMask', 1))
for k in range(1, 51):
    job('chef', str(k), [(cid(G['chef']), m(G['chef']), 'T')], A().tgoto('/T', k))
for nm, d in G['ing'].items():
    for f in (1, 2):
        job(f'ing_{nm}', str(f), [(cid(d), m(d), 'T')], A().tgoto('/T', f))
for nm, d in G['dishes'].items():
    job(nm, '1_0', [(cid(d), m(d), 'T')], A().tgoto('/T', 1))
    for f in (2, 3):
        for g in (1, 2, 4, 5):
            job(nm, f'{f}_{g}', [(cid(d), m(d), 'T')], A().tgoto('/T', f).tgoto('/T/mc_dish', g))
pot = cid(G['pot'])
for f in (1, 3, 4, 5, 6):
    job('pot', str(f), [(pot, m(G['pot']), 'T')], A().tgoto('/T', f).setprop('/T/pot_kettle', 7, False))
for k in range(1, 7):
    job('pot', f'boil{k}', flat(pot, 7, m(G['pot']), {2: 'B', 7: 'K'}), A().tgoto('/B', k).setprop('/K', 7, False))
for k in range(1, 25, 2):
    pl = [p for p in flat(pot, 4, m(G['pot']), {7: 'K'}) if p[2] == 'K']
    job('potkettle', str(k), pl, A().tgoto('/K', k))
pan = cid(G['pan'])
job('pan', '0', [(pan, m(G['pan']), 'T')], A().tgoto('/T', G['pan_default']))
for nm, (fr, n, step) in G['pours'].items():
    for k in range(1, n + 1, step):
        job('pan', f'{nm}{k}', flat(pan, fr, m(G['pan']), {3: 'C'}), A().tgoto('/C', k))
for i, lab in enumerate(['broil_vegetable1', 'broil_vegetable2', 'broil_vegetable3', 'broil_chun1', 'broil_chun2', 'broil_chun3']):
    for k in range(1, 10):
        job('pan', f'{lab}_{k}', flat(pan, G['broil_first'] + i, m(G['pan']), {1: 'C'}), A().tgoto('/C', k))
for f in range(1, 5):
    job('dish3', str(f), [(cid(G['dish3']), m(G['dish3']), 'T')], A().tgoto('/T', f))
for nm, d in G['btn'].items():
    job(f'btn_{nm}', 'up', [(cid(d), m(d), None)])
    job(f'btn_{nm}', 'over', [(cid(d), m(d), None)], hover='CENTER')
for k in range(1, 13):
    job('arrow', str(k), [(cid(G['arrow']), m(G['arrow']), 'T')], A().tgoto('/T', k))
for f in (1, 2):
    job('help', str(f), [(cid(G['help']), m(G['help']), 'T')], A().tgoto('/T', f))
job('tree', '0', [(cid(G['tree']), m(G['tree']), 'T')])
tc = G['tree_close_depth']
if tc is None:
    for d, r in display_list_timeline(s.sprites[cid(G['tree'])][1], 1).items():
        if r.get('name') == 'btn_skillClose': tc = d
for f in (1, 2):
    pl = [p for p in flat(cid(G['tree']), 1, m(G['tree']), {tc: 'X'}) if p[2] == 'X']
    job('treeclose', str(f), pl, A().tgoto('/X', f))
job('hand', '0', [(G['hand'], (1, 0, 0, 1, 100, 100), None)])
job('drag_chun', '0', [(G['drag_chun'], (1, 0, 0, 1, 100, 100), None)])
job('drag_sugar', '0', [(G['drag_sugar'], (0.8, 0, 0, 0.8, 100, 100), None)])
job('btn_retry', 'up', [(G['retry'], (0.937, 0, 0, 0.937, 452.55, 365.9), None)])
job('btn_retry', 'over', [(G['retry'], (0.937, 0, 0, 0.937, 452.55, 365.9), None)], hover='CENTER')
job('btn_qstart', 'up', [(G['qstart'], (1, 0, 0, 1, 478.9, 369.3), None)])

json.dump([j for j in jobs if 'hover' not in j], open(f'{OUT}/jobs1.json', 'w'))
json.dump([j for j in jobs if 'hover' in j], open(f'{OUT}/jobs2.json', 'w'))
json.dump(groups, open(f'{OUT}/groups.json', 'w'))
print(gid, len(jobs), 'jobs')
