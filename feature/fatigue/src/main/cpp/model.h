#pragma once

// ============================================================================
// 主动眼健康助手 —— 用眼风险评估模型（重构版）
// 纯 C++17 标准库，面向 Android JNI。
//
// 分层：
//   特征层：传感器时间序列 → 各因子风险分（R_light...R_feedback，共 6 项）
//           + 4 个交互特征 → FeatureVector（10 维，也是离线训练的特征契约）。
//   组合层：FeatureVector → 综合风险 [0,1]，可插拔：
//           - RuleBasedCombiner（默认）：加权均值 + 非补偿性惩罚 + 交互项
//           - TrainedLogisticCombiner：sigmoid(β₀ + Σβᵢxᵢ)，β 由数据拟合
//   输出层：风险等级（0.30/0.70 阈值）+ 各因子/总体可操作建议。
//
// 注：低头角度（head tilt）已移除（硬件无法可靠采集），眼表/干眼以眨眼频率代理。
//     医学依据见 DESIGN.md；后续升级路线见 ROADMAP.md。
// ============================================================================

#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <numeric>
#include <sstream>
#include <string>
#include <vector>

namespace eyehealth {

// ---- 输入类型 -------------------------------------------------------------

// 单条采样记录（与 MonitoringSample.kt 对应）
struct Sample {
  long long timestampMillis = 0;
  float ambientLux = 300.0f;   // 环境光照（lux）
  int screenBrightness = 50;   // 屏幕亮度（0~100）
  float distanceCm = 40.0f;    // 用眼距离（cm）；0 表示未检测到人脸（非用眼）
  float durationMin = 0.0f;    // 连续用眼时长（分钟）；算法可从时间戳重新推导
  int blinkCount = 7;          // 本采样区间（默认 30 秒）内眨眼次数
};

// 静态用户画像 + 交互反馈（本阶段暂用默认值）
struct Profile {
  double age = 30.0;             // 年龄
  double hasMyopia = 0.0;        // 是否近视（0/1）
  double hasDryEye = 0.0;        // 是否有干眼史（0/1）
  double parentalMyopia = 0.0;   // 父母任一方近视（0/1，遗传易感）
  double readingPosture = 0.0;   // 既往阅读姿势不良程度 0~10（静态习惯，非实时低头角）
  double discomfortScore = 0.0;  // 主观不适评分 0~10（暂默认 0）
  double contentContrast = 0.8;  // 内容对比度 0~1（暂默认 0.8）
};

// ---- 可配置参数（集中管理，便于调参/复现）---------------------------------
struct Config {
  // 风险等级阈值（架构图：低 0~0.3，中 0.3~0.7，高 0.7~1.0）
  double lowMediumThreshold = 0.30;
  double mediumHighThreshold = 0.70;

  // 采样与窗口
  double samplingIntervalSec = 30.0;  // 采样间隔
  double windowHours = 2.0;           // 急性评分窗口（最近 N 小时）
  double dayWindowHours = 24.0;       // 累计剂量窗口（最近 24 小时）
  double breakGapSec = 90.0;          // 相邻样本间隔超过此值 → 视为休息断点

  // 因子权重（和=1）
  double wLight = 0.13;
  double wDistance = 0.24;
  double wTime = 0.22;
  double wBlink = 0.21;
  double wPersonal = 0.05;
  double wFeedback = 0.15;

  // 交互项系数
  double kNearWork = 0.15;   // 距离 × 时间（持续近距离）
  double kDryEye = 0.10;     // 眨眼 × 时间（持续眨眼减少）
  double kGlare = 0.08;      // 暗环境 × 亮屏（眩光 / 瞳孔疲劳）
  double kSuscept = 0.10;    // 个人易感 × max(距离, 时间)

  // 非补偿性惩罚：综合分在加权均值与最大因子风险之间插值，
  // 保证任一严重因子（如眨眼骤降）不会被其它正常因子掩盖
  double nonCompensatoryLambda = 0.25;

  // 光照（lx / %）
  double luxLow = 100.0, luxOptLow = 300.0, luxOptHigh = 500.0, luxHigh = 1500.0;
  double screenOptLow = 40.0, screenOptHigh = 60.0;  // 亮度 %（0~100）

  // 距离（cm）
  double distOptLow = 30.0, distMin = 15.0;

  // 时长（分钟 / 小时）
  double continuousCenterMin = 50.0;   // 连续用眼 sigmoid 中心（45~60 分钟疲劳共识）
  double continuousSlope = 0.09;
  double cumulativeLowHours = 2.0, cumulativeHighHours = 8.0;

  // 眨眼（次/分）
  double blinkNormal = 15.0, blinkMin = 5.0;
};

// ---- 特征向量（组合层输入 / 离线训练特征契约，共 10 维）--------------------

struct FeatureVector {
  // 6 个基础特征（= 各因子风险分，[0,1]）
  double light = 0.0;
  double distance = 0.0;
  double time = 0.0;
  double blink = 0.0;
  double personal = 0.0;
  double feedback = 0.0;
  // 4 个交互特征
  double nearWork = 0.0;  // distance × time
  double dryEye = 0.0;    // blink × time
  double glare = 0.0;     // ambientDark × screenBright
  double suscept = 0.0;   // personal × max(distance, time)

  std::array<double, 10> toArray() const {
    return {light, distance, time,   blink,   personal,
            feedback, nearWork, dryEye, glare, suscept};
  }
};

// 训练版逻辑回归参数：logit = β₀ + Σ βᵢ·xᵢ
struct LogisticParams {
  double bias = 0.0;
  std::array<double, 10> beta{};  // 零初始化

  double logit(const FeatureVector& f) const {
    const auto x = f.toArray();
    double z = bias;
    for (int i = 0; i < 10; ++i) z += beta[i] * x[i];
    return z;
  }
};

// ---- 组合层（可插拔）-------------------------------------------------------

// 组合层接口：特征向量 → 综合风险（[0,1]）
class RiskCombiner {
 public:
  virtual ~RiskCombiner() = default;
  virtual double combine(const FeatureVector& f, const Profile& p,
                         const Config& c) const = 0;
  virtual const char* name() const = 0;
};

// 规则版组合层（当前默认）：加权均值 + 非补偿性惩罚 + 交互项
class RuleBasedCombiner : public RiskCombiner {
 public:
  double combine(const FeatureVector& f, const Profile& p,
                 const Config& c) const override;
  const char* name() const override { return "RuleBased"; }
};

// 训练版组合层：sigmoid(logit)，β 由离线数据拟合（见 tools/train_logistic.py）
class TrainedLogisticCombiner : public RiskCombiner {
 public:
  explicit TrainedLogisticCombiner(const LogisticParams& params = {})
      : params_(params) {}
  double combine(const FeatureVector& f, const Profile& p,
                 const Config& c) const override;
  const char* name() const override { return "TrainedLogistic"; }
  const LogisticParams& params() const { return params_; }

 private:
  LogisticParams params_;
};

// ---- 输出类型 -------------------------------------------------------------

struct FactorScore {
  double value = 0.0;       // [0,1]
  std::string label;        // 中文标签
  std::string advice;       // 可操作建议
};

struct RiskResult {
  double totalRisk = 0.0;   // 综合风险 [0,1]
  double linearRaw = 0.0;   // 组合层输出
  int riskLevel = 0;        // 0=低 1=中 2=高
  std::string riskLevelLabel;
  std::string advice;       // 总体建议
  std::string combinerName; // 实际使用的组合层名（可观测/可追溯）

  FactorScore light;
  FactorScore distance;
  FactorScore time;
  FactorScore blink;
  FactorScore personal;
  FactorScore feedback;

  // 特征向量（10 维，供离线训练采集 / 调试）
  std::array<double, 10> features{};

  // 派生指标（供界面展示）
  double continuousDurationMin = 0.0;
  double cumulativeDurationMin = 0.0;
  double blinkRatePerMin = 0.0;
  double meanDistanceCm = 0.0;
  double meanAmbientLux = 0.0;
  double meanScreenBrightness = 0.0;
  int sampleCount = 0;
  double windowHours = 0.0;
  int episodeCount = 0;
};

// ---- 评分器 ---------------------------------------------------------------

class EyeHealthRiskScorer {
 public:
  explicit EyeHealthRiskScorer(const Config& cfg = Config(),
                               std::shared_ptr<RiskCombiner> combiner = nullptr);

  // 主入口：传入任意一段按时间戳排序的采样序列（可乱序，内部会排序）。
  RiskResult compute(const std::vector<Sample>& samples,
                     const Profile& profile = Profile(),
                     const Config* cfgOverride = nullptr) const;

  // 单条样本便捷入口（实时展示 / 向后兼容）。
  RiskResult computeSingle(const Sample& s,
                           const Profile& profile = Profile()) const;

  void setCombiner(std::shared_ptr<RiskCombiner> combiner) {
    combiner_ = combiner ? combiner : std::make_shared<RuleBasedCombiner>();
  }
  const RiskCombiner& combiner() const { return *combiner_; }
  const Config& config() const { return cfg_; }

 private:
  Config cfg_;
  std::shared_ptr<RiskCombiner> combiner_;
};

}  // namespace eyehealth
