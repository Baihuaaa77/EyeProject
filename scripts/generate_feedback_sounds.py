"""生成两端共用的原创轻提示音：22.05 kHz 单声道 PCM，无需额外音频依赖。

音色为柔和的钟片感：基音加两个快速衰减的泛音，余弦起音避免爆音，
相邻音符交叠衰减，听起来连贯而不像蜂鸣。每段时长不超过
ScreeningFeedbackCue.microphonePauseMs 扣除约 60 ms 尾音后的长度。

eye_boop / eye_hop / eye_giggle / eye_wow / eye_spin / eye_heart / eye_dizzy / eye_yawn / eye_pop / eye_sigh / eye_count 为吉祥物「小瞳」的互动音（core:ui XiaoTong 与 iOS MascotStage），
音符带滑音（起始频率按指数滑向目标频率），听起来俏皮，时长同样不超过 0.35 秒。
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
    # 小瞳互动音：(起始频率, 起始秒, 衰减时间常数, 目标频率, 滑音时间常数)
    'eye_boop': (0.150, 0.30, [(520, 0.0, 0.055, 880, 0.030)]),
    'eye_hop': (0.230, 0.28, [(392, 0.0, 0.090, 1175, 0.060)]),
    'eye_giggle': (0.300, 0.26, [(988, 0.0, 0.030, 1100, 0.020), (1175, 0.075, 0.030, 1300, 0.020), (1319, 0.150, 0.040, 1480, 0.020)]),
    'eye_wow': (0.320, 0.28, [(587, 0.0, 0.070, 988, 0.040), (1319, 0.150, 0.080, 988, 0.050)]),
    # 转圈：连续上滑的三连音
    'eye_spin': (0.340, 0.26, [(523, 0.0, 0.050, 784, 0.030), (659, 0.080, 0.050, 988, 0.030), (784, 0.160, 0.070, 1319, 0.040)]),
    # 比心（长按摸头）：两个甜甜的上扬音
    'eye_heart': (0.330, 0.26, [(784, 0.0, 0.080, 880, 0.040), (1047, 0.120, 0.110, 1175, 0.050)]),
    # 晕乎乎（连点）：两段下滑
    'eye_dizzy': (0.340, 0.26, [(1175, 0.0, 0.080, 659, 0.080), (988, 0.140, 0.090, 523, 0.090)]),
    # 打哈欠：低音先升后降
    'eye_yawn': (0.350, 0.28, [(330, 0.0, 0.120, 523, 0.060), (494, 0.150, 0.110, 294, 0.080)]),
    # 气泡弹出：很短的「啵」
    'eye_pop': (0.090, 0.24, [(660, 0.0, 0.022, 1320, 0.010)]),
    # 叹气（累了还被戳）：缓缓下滑
    'eye_sigh': (0.300, 0.26, [(587, 0.0, 0.110, 392, 0.090)]),
    # 护眼操闭眼休息的倒计时：闭着眼也知道还剩几秒；比测试里的 eye_tick 低、更软，像轻敲木鱼
    'eye_count': (0.220, 0.24, [(587, 0.0, 0.040), (880, 0.004, 0.022)]),
}
PARTIALS = [(1, 1.0), (2, 0.18), (3, 0.06)]
ATTACK = 0.0025
RELEASE = 0.012


def note(samples, frequency, onset, tau, target=None, glide_tau=0.03):
    start = round(onset * RATE)
    for i in range(len(samples) - start):
        t = i / RATE
        attack = 0.5 - 0.5 * math.cos(math.pi * min(1.0, t / ATTACK))
        if target is None:
            # 起音瞬间略高约 3%，听感更像轻敲而非电子音
            glide = 1 + 0.03 * math.exp(-t / 0.006)
            cycles = frequency * glide * t
        else:
            # 指数滑音：f(t) = 目标 + (起始 - 目标)·e^(-t/τ)，相位取其积分
            cycles = target * t + (frequency - target) * glide_tau * (1 - math.exp(-t / glide_tau))
        value = 0.0
        for multiple, amplitude in PARTIALS:
            value += amplitude * math.sin(2 * math.pi * multiple * cycles) * math.exp(-t * multiple ** 0.7 / tau)
        samples[start + i] += attack * value


def generate():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, (duration, peak, notes) in SOUNDS.items():
        count = round(duration * RATE)
        samples = [0.0] * count
        for spec in notes:
            note(samples, *spec)
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
