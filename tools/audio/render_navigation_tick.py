"""Render the original soft navigation tick; stdlib only, no recorded/vendor audio.

Low, rounded 405 Hz pulse, a tiny downward pitch settle and a subdued transient.
The resource is preloaded by SoundPool, never generated on the TV.
"""
import math
from pathlib import Path
import random
import struct
import wave

rate = 44100
duration = 0.080
rng = random.Random(405)
phase = 0.0
low_noise = 0.0
samples = []
for i in range(round(rate * duration)):
    t = i / rate
    frequency = 403 + 12 * math.exp(-t / .008)
    phase += 2 * math.pi * frequency / rate
    attack = 1 - math.exp(-t / .0025)
    # A rounded ~40 ms body, rather than a sharp attack followed by a long ring.
    body = math.exp(-.5 * ((t - .022) / .011) ** 2)
    tail = min(1.0, (duration - t) / .010)
    low_noise += .09 * (rng.uniform(-1, 1) - low_noise)
    tone = math.sin(phase) + .035 * math.sin(phase * 2)
    transient = .09 * low_noise * math.exp(-t / .004)
    samples.append(.20 * (attack * body * tone + transient) * tail)

target = Path(__file__).resolve().parents[2] / 'launcher/app/src/main/res/raw/navigation_tick.wav'
target.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(target), 'wb') as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(rate)
    output.writeframes(b''.join(struct.pack('<h', round(x * 32767)) for x in samples))
print(f'{target.name}: {len(samples)} samples, {target.stat().st_size} bytes')
