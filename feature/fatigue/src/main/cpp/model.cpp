#include "model.h"

#include <cmath>
#include <iomanip>

namespace eyehealth {

namespace {

constexpr double kRecencyHalfLifeMs = 10.0 * 60.0 * 1000.0;  // 近期条件半衰期 10 分钟

double clamp01(double x) { return std::clamp(x, 0.0, 1.0); }

double sigmoid(double x) { return 1.0 / (1.0 + std::exp(-x)); }

double logisticStep(double x, double center, double slope) {
  return sigmoid((x - center) * slope);
}

// x 在 [lo, hi] 内线性从 0 升到 1（hi<lo 时表示反向）
double ramp(double x, double lo, double hi) {
  if (hi <= lo) return x >= lo ? 1.0 : 0.0;
  return clamp01((x - lo) / (hi - lo));
}

// x 低于 lo 时风险线性上升，到 hi 达到 1（hi < lo）
double rampBelow(double x, double lo, double hi) {
  if (x >= lo) return 0.0;
  if (x <= hi) return 1.0;
  return (lo - x) / (lo - hi);
}

std::string fmtNum(double v, int prec = 1) {
  std::ostringstream os;
  os << std::fixed << std::setprecision(prec) << v;
  return os.str();
}

// 汇总指标（从采样序列推导）
struct Metrics {
  long long latestTs = 0;
  int sampleCount = 0;
  double windowHours = 0.0;
  double continuousMin = 0.0;   // 当前连续用眼段时长（分钟）
  double cumulativeMin = 0.0;   // 窗口内累计用眼时长（分钟）
  int episodeCount = 0; // 连续用眼段数
  double meanDistance = 40.0;   // 近期距离加权均值
  double meanLux = 300.0; // 近期环境光加权值
  double meanBrightness = 50.0; // 近期手机屏幕亮度加权值
  double blinkRate = 15.0;      // 眨眼频率，次/分
  double nearFraction = 0.0;    // 近期 distance < 阈值 的时间占比
};

//---------------------------------------------------------------------------//
// 从按时间戳排序的采样序列推导指标（内部负责排序 / 窗口 / 片段切分）
// 计算各个指标在近期的均值
//---------------------------------------------------------------------------//
Metrics buildMetrics(std::vector<Sample> samples, const Config& c) {
  Metrics m;
  if (samples.empty()) return m;

  std::sort(samples.begin(), samples.end(),
            [](const Sample& a, const Sample& b) {
              return a.timestampMillis < b.timestampMillis;
            });

  m.latestTs = samples.back().timestampMillis;
  const long long acuteCut =
      m.latestTs - static_cast<long long>(c.windowHours * 3600.0 * 1000.0);
  const long long dayCut =
      m.latestTs - static_cast<long long>(c.dayWindowHours * 3600.0 * 1000.0);

  auto isActive = [](const Sample& s) { return s.distanceCm > 0.0f; };

  // 采样间隔（中位数），用于推导断点阈值
  std::vector<double> deltas;
  for (size_t i = 1; i < samples.size(); ++i) {
    double d = static_cast<double>(samples[i].timestampMillis -
                                   samples[i - 1].timestampMillis);
    if (d > 0) deltas.push_back(d);
  }
  double cadenceMs = c.samplingIntervalSec * 1000.0;
  if (!deltas.empty()) {
    std::sort(deltas.begin(), deltas.end());
    cadenceMs = deltas[deltas.size() / 2];
  }
  const double breakGapMs = std::max(c.breakGapSec * 1000.0, 3.0 * cadenceMs);

  // 用眼片段切分（在 day 窗口内）→ 连续 / 累计时长
  long long lastActiveTs = -1;
  // 当前这一段的起点（本段第一条在用样本的时间）。
  long long currentEpisodeStart = -1;
  // 今天所有段加起来的总用眼时长
  double cumulativeMs = 0.0;
  // 今天有几段"连续用眼"（段与段之间隔着休息）
  int episodes = 0;
  for (const auto& s : samples) {
    // 只处理当天时间
    if (s.timestampMillis < dayCut) continue;
    // 当前采样未用眼跳过
    if (!isActive(s)) continue;
    // 如果与上一段采样数据间隔breakGapMs则判定为休息
    if (lastActiveTs < 0 || (s.timestampMillis - lastActiveTs) > breakGapMs) {
      // 结算上一段时间
      if (lastActiveTs >= 0) {
        cumulativeMs += (lastActiveTs - currentEpisodeStart) + cadenceMs;
      }
      currentEpisodeStart = s.timestampMillis;
      ++episodes;
    }
    lastActiveTs = s.timestampMillis;
  }
  // 结算最后一段时间
  if (lastActiveTs >= 0) {
    cumulativeMs += (lastActiveTs - currentEpisodeStart) + cadenceMs;
    m.continuousMin = static_cast<double>(lastActiveTs - currentEpisodeStart) /
                      60000.0;
  }
  m.cumulativeMin = cumulativeMs / 60000.0;
  m.episodeCount = episodes;

  // 近期加权统计（急性窗口内、active 样本；半衰期 10 分钟）
  double wDist = 0.0, wLux = 0.0, wBri = 0.0, wSum = 0.0;
  double nearW = 0.0;
  std::vector<double> rates;
  for (const auto& s : samples) {
    if (s.timestampMillis < acuteCut) continue;
    if (!isActive(s)) continue;

    // 计算采样与当前时间的间隔，计算衰减权重
    const double age = static_cast<double>(m.latestTs - s.timestampMillis);
    const double w = std::pow(0.5, age / kRecencyHalfLifeMs);

    wDist += w * s.distanceCm;
    wLux += w * s.ambientLux;
    wBri += w * s.screenBrightness;
    // 分母：所有权重累加
    wSum += w;
    // 累计近距离用眼占比
    if (s.distanceCm < c.distOptLow) nearW += w;
    // 保存各个sample的眨眼次数 分钟算
    rates.push_back(s.blinkCount * (60.0 / c.samplingIntervalSec));
  }

  // 加权平均
  if (wSum > 0) {
    m.meanDistance = wDist / wSum;
    m.meanLux = wLux / wSum;
    m.meanBrightness = wBri / wSum;
    m.nearFraction = nearW / wSum;
  }

  // 区间眨眼频率取中位数
  if (!rates.empty()) {
    std::sort(rates.begin(), rates.end());
    m.blinkRate = rates[rates.size() / 2];  // 中位数，抗噪声
  }

  m.sampleCount = static_cast<int>(samples.size());
  m.windowHours = c.windowHours;
  return m;
}

}  // namespace


//---------------------------------------------------------------------------//
// 组合层，计算总风险分
//---------------------------------------------------------------------------//
double RuleBasedCombiner::combine(const FeatureVector& f, const Profile&,
                                  const Config& c) const {
  const double wSum =
      c.wLight + c.wDistance + c.wTime + c.wBlink + c.wPersonal + c.wFeedback;

  const double weightedMean =
      (c.wLight * f.light + c.wDistance * f.distance + c.wTime * f.time +
       c.wBlink * f.blink + c.wPersonal * f.personal +
       c.wFeedback * f.feedback) /
      std::max(wSum, 1e-9);

  const double maxFactor =
      std::max({f.light, f.distance, f.time, f.blink, f.personal, f.feedback});

  // 非补偿性惩罚：加权均值与最大因子风险插值，单一严重因子也能拉升综合风险
  const double base =
      (1.0 - c.nonCompensatoryLambda) * weightedMean +
      c.nonCompensatoryLambda * maxFactor;

  const double interaction =
      c.kNearWork * f.nearWork + c.kDryEye * f.dryEye + c.kGlare * f.glare +
      c.kSuscept * f.suscept;

  return base + interaction;
}

double TrainedLogisticCombiner::combine(const FeatureVector& f, const Profile&,
                                        const Config&) const {
  return sigmoid(params_.logit(f));
}


//---------------------------------------------------------------------------//
// 计算各层风险分，得出特征向量
//---------------------------------------------------------------------------//
EyeHealthRiskScorer::EyeHealthRiskScorer(const Config& cfg,
                                         std::shared_ptr<RiskCombiner> combiner)
    : cfg_(cfg),
      combiner_(combiner ? combiner : std::make_shared<RuleBasedCombiner>()) {}

// 从已汇总指标 + 画像 → 完整评分结果
static RiskResult scoreFromMetrics(const Metrics& m, const Profile& p,
                                   const Config& c,
                                   const RiskCombiner& combiner) {
  RiskResult r;
  r.sampleCount = m.sampleCount;
  r.windowHours = m.windowHours;
  r.continuousDurationMin = m.continuousMin;
  r.cumulativeDurationMin = m.cumulativeMin;
  r.blinkRatePerMin = m.blinkRate;
  r.meanDistanceCm = m.meanDistance;
  r.meanAmbientLux = m.meanLux;
  r.meanScreenBrightness = m.meanBrightness;
  r.episodeCount = m.episodeCount;
  r.combinerName = combiner.name();

  // ---- 特征层：各因子风险分 ----
  const double lux = m.meanLux;
  const double bri = m.meanBrightness;
  const double ambientDark = rampBelow(lux, c.luxOptLow, c.luxLow);
  const double ambientBright = ramp(lux, c.luxOptHigh, c.luxHigh);
  const double ambientRisk = std::max(ambientDark, ambientBright);
  const double screenDim = rampBelow(bri, c.screenOptLow, 0.0);
  const double screenBright = ramp(bri, c.screenOptHigh, 100.0);
  const double screenRisk = std::max(screenDim, screenBright);
  const double contentRisk = clamp01((0.8 - p.contentContrast) / 0.8);
  const double lightRisk =
      0.40 * ambientRisk + 0.40 * screenRisk + 0.20 * contentRisk;

  const double dist = m.meanDistance;
  const double distRisk = rampBelow(dist, c.distOptLow, c.distMin);
  const double nearFracRisk = ramp(m.nearFraction, 0.0, 0.6);
  const double distanceRisk = 0.65 * distRisk + 0.35 * nearFracRisk;

  const double continuousRisk =
      logisticStep(m.continuousMin, c.continuousCenterMin, c.continuousSlope);
  const double cumulativeRisk =
      ramp(m.cumulativeMin / 60.0, c.cumulativeLowHours, c.cumulativeHighHours);
  const double timeRisk = 0.70 * continuousRisk + 0.30 * cumulativeRisk;

  const double blinkRisk = rampBelow(m.blinkRate, c.blinkNormal, c.blinkMin);

  double personalRisk = 0.0;
  if (p.age < 18.0) personalRisk += 0.25;
  else if (p.age > 50.0) personalRisk += 0.15;
  if (p.hasMyopia > 0.5) personalRisk += 0.25;
  if (p.hasDryEye > 0.5) personalRisk += 0.30;
  if (p.parentalMyopia > 0.5) personalRisk += 0.10;
  if (p.readingPosture > 5.0) personalRisk += 0.10;
  personalRisk = clamp01(personalRisk);

  const double feedbackRisk = clamp01(p.discomfortScore / 10.0);

  // ---- 特征向量（组合层输入 / 离线训练契约）----
  FeatureVector f;
  f.light = lightRisk;
  f.distance = distanceRisk;
  f.time = timeRisk;
  f.blink = blinkRisk;
  f.personal = personalRisk;
  f.feedback = feedbackRisk;
  f.nearWork = distanceRisk * timeRisk;
  f.dryEye = blinkRisk * timeRisk;
  f.glare = ambientDark * screenBright;
  f.suscept = personalRisk * std::max(distanceRisk, timeRisk);
  r.features = f.toArray();

  // ---- 组合层 ----
  r.linearRaw = combiner.combine(f, p, c);
  r.totalRisk = clamp01(r.linearRaw);

  // ---- 等级 ----
  if (r.totalRisk < c.lowMediumThreshold)
    r.riskLevel = 0;
  else if (r.totalRisk < c.mediumHighThreshold)
    r.riskLevel = 1;
  else
    r.riskLevel = 2;
  r.riskLevelLabel =
      r.riskLevel == 0 ? "低风险" : (r.riskLevel == 1 ? "中风险" : "高风险");

  // 各因子填充
  r.light.value = lightRisk;
  r.light.label = "光照/亮度";
  r.distance.value = distanceRisk;
  r.distance.label = "用眼距离";
  r.time.value = timeRisk;
  r.time.label = "用眼时长";
  r.blink.value = blinkRisk;
  r.blink.label = "眨眼/干眼";
  r.personal.value = personalRisk;
  r.personal.label = "个人易感";
  r.feedback.value = feedbackRisk;
  r.feedback.label = "主观不适";

  // ---- 各因子建议 ----
  {  // 光照
    std::string a = "光照与亮度适宜";
    if (ambientDark > 0.5)
      a = "环境光偏暗（约 " + fmtNum(lux, 0) + " lux），建议开灯至 300–500 lux";
    else if (ambientDark > 0.15)
      a = "环境光略暗（约 " + fmtNum(lux, 0) + " lux），可适当提高照明";
    else if (ambientBright > 0.3)
      a = "环境光过亮（约 " + fmtNum(lux, 0) + " lux），避免直射/眩光";
    if (screenBright > 0.5)
      a += "；屏幕过亮（" + fmtNum(bri, 0) + "%），建议调低至 40–60%";
    else if (screenDim > 0.5)
      a += "；屏幕过暗（" + fmtNum(bri, 0) + "%），建议调高以看得清";
    if (ambientDark > 0.5 && screenBright > 0.3)
      a += "；暗环境看亮屏易致眩光，务必开灯或降亮度";
    if (contentRisk > 0.3) a += "；内容对比度偏低，建议提高对比度";
    r.light.advice = a;
  }
  {  // 距离
    if (distRisk > 0.5)
      r.distance.advice = "用眼距离过近（约 " + fmtNum(dist, 0) +
                          " cm），建议拉远至 40 cm 左右（一臂远）";
    else if (nearFracRisk > 0.3)
      r.distance.advice = "长时间近距离用眼，建议保持 30–50 cm 并定时远眺";
    else
      r.distance.advice = "用眼距离适宜";
  }
  {  // 时长
    std::string a = "用眼时长适度";
    if (m.continuousMin >= 60.0)
      a = "已连续用眼约 " + fmtNum(m.continuousMin, 0) +
          " 分钟，请立即休息 5 分钟并远眺 6 m（20-20-20）";
    else if (m.continuousMin >= 20.0)
      a = "已连续用眼约 " + fmtNum(m.continuousMin, 0) +
          " 分钟，建议稍后休息远眺（20-20-20 法则）";
    if (cumulativeRisk > 0.4)
      a += "；今日累计用眼约 " + fmtNum(m.cumulativeMin / 60.0, 1) +
           " 小时，注意控制总时长";
    r.time.advice = a;
  }
  {  // 眨眼
    if (blinkRisk > 0.6)
      r.blink.advice = "眨眼频率过低（约 " + fmtNum(m.blinkRate, 0) +
                       " 次/分），有干眼风险，建议刻意眨眼或使用人工泪液";
    else if (blinkRisk > 0.25)
      r.blink.advice = "眨眼频率偏低（约 " + fmtNum(m.blinkRate, 0) +
                       " 次/分），注意多眨眼、定时闭目";
    else
      r.blink.advice = "眨眼频率正常";
  }
  {  // 个人
    if (personalRisk > 0.3)
      r.personal.advice = "存在近视/干眼/年龄等易感因素，更需控制距离与时长";
    else
      r.personal.advice = "无显著个人易感因素（默认）";
  }
  {  // 反馈
    if (feedbackRisk > 0.4)
      r.feedback.advice = "你报告了明显不适（" + fmtNum(p.discomfortScore, 0) +
                          "/10），建议优先休息";
    else
      r.feedback.advice = "无主观不适报告（默认）";
  }

  // ---- 总体建议（按贡献度取主导因子）----
  struct Named {
    double contrib;
    std::string advice;
  };
  std::vector<Named> ranked = {
      {c.wLight * lightRisk, r.light.advice},
      {c.wDistance * distanceRisk, r.distance.advice},
      {c.wTime * timeRisk, r.time.advice},
      {c.wBlink * blinkRisk, r.blink.advice},
      {c.wPersonal * personalRisk, r.personal.advice},
      {c.wFeedback * feedbackRisk, r.feedback.advice},
  };
  std::sort(ranked.begin(), ranked.end(),
            [](const Named& a, const Named& b) { return a.contrib > b.contrib; });

  if (r.riskLevel == 0) {
    std::string a = "当前用眼状态良好，保持即可";
    if (ranked[0].contrib > 0.05) a += "；可微调：" + ranked[0].advice;
    r.advice = a;
  } else if (r.riskLevel == 1) {
    r.advice = "存在中度用眼风险，建议优先改善：" + ranked[0].advice;
    if (ranked[1].contrib > 0.05) r.advice += "；其次：" + ranked[1].advice;
  } else {
    r.advice = "用眼风险高，请立即休息、暂停使用。主要问题：" + ranked[0].advice;
    if (ranked[1].contrib > 0.08) r.advice += "；同时：" + ranked[1].advice;
  }

  return r;
}

RiskResult EyeHealthRiskScorer::compute(const std::vector<Sample>& samples,
                                        const Profile& profile,
                                        const Config* cfgOverride) const {
  const Config& c = cfgOverride ? *cfgOverride : cfg_;
  if (samples.empty()) {
    RiskResult r;
    r.riskLevelLabel = "低风险";
    r.advice = "暂无监测数据";
    r.combinerName = combiner_->name();
    r.light.label = "光照/亮度";
    r.distance.label = "用眼距离";
    r.time.label = "用眼时长";
    r.blink.label = "眨眼/干眼";
    r.personal.label = "个人易感";
    r.feedback.label = "主观不适";
    return r;
  }
  Metrics m = buildMetrics(samples, c);
  return scoreFromMetrics(m, profile, c, *combiner_);
}

RiskResult EyeHealthRiskScorer::computeSingle(const Sample& s,
                                              const Profile& profile) const {
  Metrics m;
  m.latestTs = s.timestampMillis;
  m.sampleCount = 1;
  m.windowHours = cfg_.windowHours;
  m.continuousMin = std::max(0.0f, s.durationMin);
  m.cumulativeMin = std::max(0.0f, s.durationMin);
  m.episodeCount = (s.distanceCm > 0.0f) ? 1 : 0;
  m.meanDistance = (s.distanceCm > 0.0f) ? s.distanceCm : 40.0f;
  m.meanLux = s.ambientLux;
  m.meanBrightness = s.screenBrightness;
  m.blinkRate = s.blinkCount * (60.0 / cfg_.samplingIntervalSec);
  m.nearFraction = (m.meanDistance < cfg_.distOptLow) ? 1.0 : 0.0;
  return scoreFromMetrics(m, profile, cfg_, *combiner_);
}

}  // namespace eyehealth
