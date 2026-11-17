"""Generate capture jobs for every game sprite state."""
import sys, json, os
sys.path.insert(0, os.path.dirname(__file__))
from swflib import Swf, display_list_timeline
from mini import Mini, compose, I
from variant import A

SRC = sys.argv[1]; WEB = sys.argv[2]; OUT = sys.argv[3]
S = 1.5
s = Swf(SRC); M = Mini(SRC)
dl = display_list_timeline(s.tags, 3278)
def m(d): return dl[d].get('matrix') or I
os.makedirs(f'{WEB}/a', exist_ok=True)
jobs = []; groups = {}

def flat(cid, frame, parentM, names=None):
    """expand sprite cid at frame into composed placements; names: {child_depth: name}"""
    names = names or {}
    out = []
    for d, r in sorted(display_list_timeline(s.sprites[cid][1], frame).items()):
        if 'clipdepth' in r: continue
        out.append((r['cid'], compose(parentM, r.get('matrix') or I), names.get(d)))
    return out

def job(group, frame_name, places, actions=None, hover=None):
    fn = f'{group}__{frame_name}'
    M.build(places, actions, out=f'{WEB}/a/{fn}.swf')
    j = {'swf': f'a/{fn}.swf', 'out': f'{OUT}/{fn}.png', 'scale': S, 'delay': 250}
    if hover: j['hover'] = hover
    jobs.append(j); groups.setdefault(group, []).append(frame_name)

# background
job('bg', '0', [(490, I, None), (491, I, None), (57, m(4), None), (505, I, None), (293, m(165), None), (294, m(169), None)])
# gauge base (mask at last frame => no red) + overlay frame
job('gauge', '0', [(511, m(144), 'T')], A().tgoto('/T/gaugeMask', 300))
job('gaugefull', '0', [(511, m(144), 'T')], A().tgoto('/T/gaugeMask', 1))
# chef 50 frames
for k in range(1, 51):
    job('chef', str(k), [(504, m(6), 'T')], A().tgoto('/T', k))
# ingredients
ING = {'vegetable': 54, 'meat': 58, 'noodle': 68, 'garlic': 74, 'oil': 84, 'sugar': 91, 'chun': 96, 'kettle': 121}
for nm, dep in ING.items():
    for f in (1, 2):
        job(f'ing_{nm}', str(f), [(dl[dep]['cid'], m(dep), 'T')], A().tgoto('/T', f))
# dishes
for nm, dep in (('dish1', 63), ('dish2', 66)):
    job(nm, '1_0', [(dl[dep]['cid'], m(dep), 'T')], A().tgoto('/T', 1))
    for f in (2, 3):
        for g in (1, 2, 4, 5):
            job(nm, f'{f}_{g}', [(dl[dep]['cid'], m(dep), 'T')], A().tgoto('/T', f).tgoto('/T/mc_dish', g))
# pot: frames without kettle
for f in (1, 3, 4, 5, 6):
    job('pot', str(f), [(104, m(113), 'T')], A().tgoto('/T', f).setprop('/T/pot_kettle', 7, False))
# pot boil (frame7: c91 + spr97 6f)
for k in range(1, 7):
    pl = flat(104, 7, m(113), {2: 'B', 7: 'K'})
    job('pot', f'boil{k}', pl, A().tgoto('/B', k).setprop('/K', 7, False))
# pot kettle pour anim (spr89 child at depth 7)
for k in range(1, 25, 2):
    pl = [p for p in flat(104, 4, m(113), {7: 'K'}) if p[2] == 'K']
    job('potkettle', str(k), pl, A().tgoto('/K', k))
# pan
job('pan', '0', [(246, m(140), 'T')], A().tgoto('/T', 1))
POURS = {'kettle': (2, 24, 2), 'chun': (8, 7, 1), 'sugar': (13, 5, 1), 'oil': (18, 30, 2), 'meat': (24, 13, 1), 'vegetable': (31, 11, 1)}
for nm, (fr, n, step) in POURS.items():
    for k in range(1, n + 1, step):
        pl = flat(246, fr, m(140), {3: 'C'})
        job('pan', f'{nm}{k}', pl, A().tgoto('/C', k))
for i, lab in enumerate(['broil_vegetable1', 'broil_vegetable2', 'broil_vegetable3', 'broil_chun1', 'broil_chun2', 'broil_chun3']):
    for k in range(1, 10):
        pl = flat(246, 36 + i, m(140), {1: 'C'})
        job('pan', f'{lab}_{k}', pl, A().tgoto('/C', k))
# bowl
for f in range(1, 5):
    job('dish3', str(f), [(256, m(125), 'T')], A().tgoto('/T', f))
# buttons (DefineButton): up and over via hover
BTN = {'complete': 161, 'takeOut': 170, 'takeOff': 174}
for nm, dep in BTN.items():
    job(f'btn_{nm}', 'up', [(dl[dep]['cid'], m(dep), None)])
    job(f'btn_{nm}', 'over', [(dl[dep]['cid'], m(dep), None)], hover='CENTER')
# arrow
for k in range(1, 13):
    job('arrow', str(k), [(523, m(178), 'T')], A().tgoto('/T', k))
# help button + help panel + close button
for f in (1, 2):
    job('help', str(f), [(353, m(181), 'T')], A().tgoto('/T', f))
job('tree', '0', [(386, m(187), 'T')])
for f in (1, 2):
    pl = [p for p in flat(386, 1, m(187), {247: 'X'}) if p[2] == 'X']
    job('treeclose', str(f), pl, A().tgoto('/X', f))
# cursor & drag clips at reference origin (100,100)
job('hand', '0', [(419, (1, 0, 0, 1, 100, 100), None)])
job('drag_chun', '0', [(509, (1, 0, 0, 1, 100, 100), None)])
job('drag_sugar', '0', [(510, (0.8, 0, 0, 0.8, 100, 100), None)])
# retry button on endings
job('btn_retry', 'up', [(627, (0.937, 0, 0, 0.937, 452.55, 365.9), None)])
job('btn_retry', 'over', [(627, (0.937, 0, 0, 0.937, 452.55, 365.9), None)], hover='CENTER')

json.dump(jobs, open(f'{OUT}/jobs.json', 'w'))
json.dump(groups, open(f'{OUT}/groups.json', 'w'))
print(len(jobs), 'jobs')
