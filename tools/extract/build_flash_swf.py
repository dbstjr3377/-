"""원본 SWF 에 서버 스트리밍용 패치를 넣는다.

- 인트로(SayHi 애니메이션 + 커튼) 생략: 프레임 4 의 stop() 을 play() 로, 프레임 5 끝에서 시연 시작 프레임으로 이동
- 시연 구간 진입 / 게임 시작을 페이지(JS)에 알림: getURL("javascript:ddPhase('demo'|'play')")
- 재료를 집어 드래그를 시작하는 _root.DupDragClip 을 감싸 페이지에 알림 (ddDrag())
  → 서버가 '클릭 한 번 = 누르기 + 떼기' 인지 '집어서 들고 있기' 인지 구분하는 데 사용

사용: python3 build_flash_swf.py <원본 swf> <출력 swf> <게임 1|2>
"""
import struct, sys, zlib
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from swflib import read_tags
from variant import A, tag, s0

GAMES = {
    '1': dict(demo=170, play=3266),
    '2': dict(demo=159, play=3138),
}


def geturl(a, js):
    return a.raw(0x83, s0('javascript:' + js + ';void 0') + s0(''))


def get_var(a, name):
    return a.push(name).raw(0x1C)


def wrap_dupdrag():
    """_root.ddDup = _root.DupDragClip;
       _root.DupDragClip = function(a, b, c) { getURL("javascript:ddDrag()"); _root.ddDup(a, b, c); }"""
    a = A()
    get_var(a, '_root'); a.push('ddDup'); get_var(a, '_root'); a.push('DupDragClip'); a.raw(0x4E); a.raw(0x4F)
    body = A()
    geturl(body, 'ddDrag()')
    for n in ('c', 'b', 'a'):
        get_var(body, n)
    body.push(3); get_var(body, '_root'); body.push('ddDup'); body.raw(0x52); body.raw(0x17)
    get_var(a, '_root'); a.push('DupDragClip')
    hdr = s0('') + struct.pack('<H', 3) + s0('a') + s0('b') + s0('c') + struct.pack('<H', len(body.b))
    a.raw(0x9B, hdr)
    a.b += body.b
    a.raw(0x4F)
    return a


def build(src, out, game):
    g = GAMES[game]
    d = open(src, 'rb').read()
    body = zlib.decompress(d[8:]) if d[:3] == b'CWS' else d[8:]
    nbits = body[0] >> 3
    hdr = (5 + 4 * nbits + 7) // 8 + 4
    ob = bytearray(body[:hdr])
    frame = 1
    for code, tb, raw in read_tags(body, hdr, len(body)):
        if code == 12 and frame == 4:               # stop(); _root.sayhiani.play()  →  play()
            ob += A().play().bytes()
            continue
        if code == 1:                               # ShowFrame: 프레임 끝에 액션 추가
            extra = None
            if frame == 5:
                extra = A().goto(g['demo']).play()
            elif frame == g['demo']:
                extra = geturl(A(), "ddPhase('demo')")
            elif frame == g['play']:
                extra = wrap_dupdrag()
                geturl(extra, "ddPhase('play')")
            if extra is not None:
                ob += extra.bytes()
            frame += 1
        ob += raw
        if code == 0:
            break
    data = bytes(ob)
    comp = zlib.compress(data, 9)
    res = b'CWS' + bytes([d[3]]) + struct.pack('<I', len(data) + 8) + comp
    open(out, 'wb').write(res)
    print(out, len(res), 'bytes')


if __name__ == '__main__':
    build(sys.argv[1], sys.argv[2], sys.argv[3])
