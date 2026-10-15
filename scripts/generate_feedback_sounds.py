"""生成两端共用的原创轻提示音：22.05 kHz 单声道 PCM，无需额外音频依赖。

音色为柔和的钟片感：基音加两个快速衰减的泛音，余弦起音避免爆音，
相邻音符交叠衰减，听起来连贯而不像蜂鸣。每段时长不超过
ScreeningFeedbackCue.microphonePauseMs 扣除约 60 ms 尾音后的长度。
"""
from pathlib import Path
import math
import struct
import wave

RATE = 22050
ROOT = Path(__file__).resolve().parents[1] / 'shared/feedback/res/raw'
# 名称: (总时长秒, 峰值, [(频率 Hz, 起始秒, 衰减时间常数秒)])
SOUNDS = {
    'eye_answer': (0.050, 0.28, [(1175, 0.0, 0.016)]),
    'eye_tick': (0.050, 0.24, [(880, 0.0, 0.014)]),
    'eye_target': (0.075, 0.26, [(1319, 0.0, 0.026)]),
    'eye_phase': (0.170, 0.28, [(659, 0.0, 0.055), (880, 0.070, 0.060)]),
    'eye_warning': (0.190, 0.30, [(523, 0.0, 0.060), (415, 0.085, 0.065)]),
    'eye_start': (0.245, 0.28, [(659, 0.0, 0.060), (831, 0.060, 0.060), (988, 0.120, 0.080)]),
    'eye_complete': (0.255, 0.28, [(784, 0.0, 0.050), (988, 0.055, 0.050), (1175, 0.110, 0.055), (1568, 0.165, 0.060)]),
}
PARTIALS = [(1, 1.0), (2, 0.18), (3, 0.06)]
ATTACK = 0.0025
RELEASE = 0.012


def note(samples, frequency, onset, tau):
    start = round(onset * RATE)
    for i in range(len(samples) - start):
        t = i / RATE
        attack = 0.5 - 0.5 * math.cos(math.pi * min(1.0, t / ATTACK))
        # 起音瞬间略高约 3%，听感更像轻敲而非电子音
        glide = 1 + 0.03 * math.exp(-t / 0.006)
        value = 0.0
        for multiple, amplitude in PARTIALS:
            value += amplitude * math.sin(2 * math.pi * frequency * multiple * glide * t) * math.exp(-t * multiple ** 0.7 / tau)
        samples[start + i] += attack * value


def generate():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, (duration, peak, notes) in SOUNDS.items():
        count = round(duration * RATE)
        samples = [0.0] * count
        for frequency, onset, tau in notes:
            note(samples, frequency, onset, tau)
        for i in range(count):
            remaining = (count - 1 - i) / RATE
            samples[i] *= 0.5 - 0.5 * math.cos(math.pi * min(1.0, remaining / RELEASE))
        loudest = max(abs(value) for value in samples) or 1.0
        frames = [round(32767 * peak * value / loudest) for value in samples]
        with wave.open(str(ROOT / (name + '.wav')), 'wb') as output:
            output.setparams((1, 2, RATE, 0, 'NONE', 'not compressed'))
            output.writeframes(struct.pack('<' + 'h' * len(frames), *frames))


if __name__ == '__main__':
    generate()
