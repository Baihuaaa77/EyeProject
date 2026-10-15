"""生成两端共用的原创轻提示音：22.05 kHz 单声道 PCM，无需额外音频依赖。"""
from pathlib import Path
import math
import struct
import wave

ROOT = Path(__file__).resolve().parents[1] / 'shared/feedback/res/raw'
NOTES = {
    'eye_start': [(659, 0.065), (831, 0.065), (988, 0.08)],
    'eye_answer': [(1047, 0.035)],
    'eye_phase': [(659, 0.06), (880, 0.085)],
    'eye_target': [(740, 0.06)],
    'eye_warning': [(440, 0.075), (349, 0.09)],
    'eye_tick': [(659, 0.035)],
    'eye_complete': [(784, 0.065), (1047, 0.065), (1319, 0.09)],
}

def generate():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, notes in NOTES.items():
        samples = []
        for frequency, duration in notes:
            count = round(22050 * duration)
            for i in range(count):
                t = i / 22050
                envelope = min(1, t / 0.004) * min(1, (duration - t) / 0.008) * math.exp(-2.6 * t / duration)
                tone = math.sin(2 * math.pi * frequency * t) + 0.15 * math.sin(4 * math.pi * frequency * t)
                samples.append(round(32767 * 0.22 * envelope * tone / 1.15))
            samples.extend([0] * round(22050 * 0.012))
        with wave.open(str(ROOT / (name + '.wav')), 'wb') as output:
            output.setparams((1, 2, 22050, 0, 'NONE', 'not compressed'))
            output.writeframes(struct.pack('<' + 'h' * len(samples), *samples))

if __name__ == '__main__':
    generate()
