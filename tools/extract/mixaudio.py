"""서버 로그의 [snd]/[sndstop] 기록으로 녹화 영상의 게임 소리를 합성한다.
usage: mixaudio.py server.log rec_start_ms rec_end_ms sounds_dir out.wav"""
import sys, re, subprocess, numpy as np
log, t0, t1, sdir, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
SR = 44100
ev, stops = [], []
for line in open(log, encoding='utf-8', errors='replace'):
    m = re.search(r'\[snd\] (\d+) (\S+)', line)
    if m: ev.append((int(m.group(1)), m.group(2)))
    m = re.search(r'\[sndstop\] (\d+)', line)
    if m: stops.append(int(m.group(1)))
cache = {}
def load(name):
    if name not in cache:
        raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', f'{sdir}/{name}.ogg', '-f', 's16le', '-ac', '1', '-ar', str(SR), '-'],
                             capture_output=True, check=True).stdout
        cache[name] = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768
    return cache[name]
n = int((t1 - t0) / 1000 * SR) + SR
mix = np.zeros(n, dtype=np.float32)
used = 0
for t, name in ev:
    if t < t0 - 30000 or t > t1: continue
    a = load(name)
    end = min([s for s in stops if s > t] + [t1 + 60000])
    a = a[:max(0, int((end - t) / 1000 * SR))]
    start = int((t - t0) / 1000 * SR)
    if start < 0:
        a = a[-start:]; start = 0
    a = a[:max(0, n - start)]
    mix[start:start + len(a)] += a * 0.8
    used += 1
peak = np.max(np.abs(mix)) or 1
if peak > 0.98: mix *= 0.98 / peak
import wave
w = wave.open(out, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
w.writeframes((mix * 32767).astype(np.int16).tobytes()); w.close()
print('events', used, 'stops', len(stops))
