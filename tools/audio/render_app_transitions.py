"""Original synthesized opening/return cues, not recordings or vendor sound assets.

Opening: soft ~243/203/365 Hz chord with a lingering bright tail.
Return: lower ~216/180/324 Hz chord, with the high voice fading before the low one.
Rendered offline; Android only preloads and plays the two small mono PCM resources.
"""
import math
from pathlib import Path
import struct
import wave

RATE = 44100
TARGET = Path(__file__).resolve().parents[2] / 'launcher/app/src/main/res/raw'


def render(name, returning):
    duration = .66 if returning else .80
    base = 108 if returning else 121.5
    voices = [(2.0, .72, 0, .135), (5 / 3, .30, .010, .115),
              (3.0, .24, .025 if not returning else 0, .18 if not returning else .085)]
    phases = [0.0, .45, 1.1]
    samples = []
    for i in range(round(duration * RATE)):
        t = i / RATE
        value = 0.0
        for j, (ratio, gain, delay, decay) in enumerate(voices):
            u = t - delay
            if u <= 0:
                continue
            # Tiny opposite pitch settles and staggered voices give the two cues their direction.
            bend = (-.006 if returning else .006) * math.exp(-u / .055)
            phases[j] += 2 * math.pi * base * ratio * (1 + bend) / RATE
            attack = (1 - math.exp(-u / .045)) ** 3
            envelope = attack * math.exp(-u / decay)
            value += gain * envelope * math.sin(phases[j])
        fade = min(1, (duration - t) / .035)
        samples.append(value * fade)
    # Similar peak level to the existing navigation tick; playback uses the same system stream.
    normalization = .20 / max(abs(x) for x in samples)
    path = TARGET / name
    TARGET.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(b''.join(struct.pack('<h', round(x * normalization * 32767)) for x in samples))
    print(f'{name}: {duration:.2f}s, {path.stat().st_size} bytes')


render('app_open.wav', False)
render('app_return.wav', True)
