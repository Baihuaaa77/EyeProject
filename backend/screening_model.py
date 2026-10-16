"""用已标注会话分别训练干眼、老视、斜视的探索性筛查模型；仅依赖 Python 标准库。"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import sys
from pathlib import Path


# 每个病种的评估规则版本；与 shared/screening 中写入记录的版本常量逐一对应，规则或特征口径升版本时这里同步改。
INPUT_VERSIONS = {
    "DRY_EYE": "dryeye-9.18-rules-2",          # DryEyeAssessment.VERSION，写在记录顶层 assessmentVersion
    "PRESBYOPIA": "protocol-9.18-rules-2",     # ScreeningAssessmentModel.VERSION，写在 assessment.modelVersion
    "STRABISMUS": "protocol-9.18-rules-2",
}

# 特征键与顺序照搬 DryEyeAssessment.evaluate 与 ScreeningAssessmentModel 的 features 映射。
FEATURES = {
    "DRY_EYE": [
        "natural_seconds", "baseline_decimal", "blink_rate_per_min", "open_longest_sec",
        "fva_mean_logmar", "vmr", "accuracy_natural", "accuracy_open", "mean_rt_ms", "decline_sec",
        "valid_frame_ratio", "distance_in_range_ratio", "head_turn_ratio",
    ],
    "PRESBYOPIA": [
        "age_years", "near_right_decimal", "near_left_decimal", "near_both_decimal",
        "near_point_cm", "near_point_mad_cm", "amplitude_d", "push_speed_cm_s",
        "hofstetter_min_d", "hofstetter_mean_d", "amplitude_age_years",
        "flipper_cycles_per_min", "flipper_errors",
        "curve_60_decimal", "curve_33_decimal", "curve_25_decimal",
        "daily_correction_mode", "near_correction_in_use", "invalid_trials",
    ],
    "STRABISMUS": [
        "age_years", "quality_rate",
        "fixation_hde_prism", "fixation_vde_prism", "fixation_hde_sd_prism", "fixation_vde_sd_prism",
        "fixation_frames", "fixation_continuous_ms",
        "cover_refix_median_prism", "cover_refix_events",
        "bsi", "pursuit_r_h", "pursuit_r_v", "pursuit_d_norm",
        "pursuit_max_x_deg", "pursuit_max_y_deg",
    ],
}

MODEL_FORMAT = "eyeproject-screening-logistic-v2"


def record_parts(session: dict, kind: str) -> tuple[str | None, bool, object]:
    """按 RecordSummary 的口径取（评估版本、质量是否合格、特征对象）：干眼在顶层，老视/斜视在 assessment 下。"""
    if kind == "DRY_EYE":
        valid = session.get("valid")
        return session.get("assessmentVersion"), valid is True, session.get("features")
    assessment = session.get("assessment")
    if not isinstance(assessment, dict):
        return None, False, None
    quality = assessment.get("quality")
    return assessment.get("modelVersion"), isinstance(quality, str) and quality != "不足", assessment.get("features")


def session_features(path: Path, kind: str) -> dict[str, float | None]:
    session = json.loads(path.read_text(encoding="utf-8"))
    if session.get("kind") != kind:
        raise ValueError(f"病种不匹配：{path}")
    version, sufficient, raw = record_parts(session, kind)
    if version != INPUT_VERSIONS[kind]:
        raise ValueError(f"特征版本不兼容（{version}）：{path}")
    if not sufficient:
        raise ValueError(f"质量不足，拒绝训练或推理：{path}")
    if not isinstance(raw, dict):
        raise ValueError(f"记录缺少 features 对象：{path}")
    missing = [key for key in FEATURES[kind] if key not in raw]
    if missing:
        raise ValueError(f"特征键缺失，记录与训练程序版本不一致：{path} 缺 {', '.join(missing)}")
    result = {}
    for key in FEATURES[kind]:
        value = raw[key]
        # JSON 的 true/false 在 Python 中是 int 子类，不能当作数值特征
        numeric = isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
        result[key] = float(value) if numeric else None
    return result


def median(values: list[float]) -> float:
    seq = sorted(values)
    if not seq:
        return 0.0
    middle = len(seq) // 2
    return seq[middle] if len(seq) % 2 else (seq[middle - 1] + seq[middle]) / 2


def fit_transform(rows: list[dict], names: list[str]):
    centers = [median([row[name] for row in rows if row[name] is not None]) for name in names]
    scales = []
    for name, center in zip(names, centers):
        present = [row[name] for row in rows if row[name] is not None]
        scale = math.sqrt(sum((value - center) ** 2 for value in present) / max(1, len(present)))
        scales.append(max(scale, 1e-6))
    return centers, scales


def transform(row: dict, names: list[str], centers: list[float], scales: list[float]) -> list[float]:
    values = []
    missing = []
    for name, center, scale in zip(names, centers, scales):
        value = row[name]
        values.append(max(-5.0, min(5.0, ((value if value is not None else center) - center) / scale)))
        missing.append(float(value is None))
    return values + missing


def sigmoid(value: float) -> float:
    value = max(-30.0, min(30.0, value))
    return 1.0 / (1.0 + math.exp(-value))


def fit_logistic(x: list[list[float]], labels: list[int]) -> tuple[list[float], float]:
    weights = [0.0] * len(x[0])
    bias = 0.0
    positive = sum(labels)
    negative = len(labels) - positive
    if positive == 0 or negative == 0:
        raise ValueError("训练集必须同时含阳性和阴性")
    for epoch in range(1200):
        dw = [0.0] * len(weights)
        db = 0.0
        for row, label in zip(x, labels):
            predicted = sigmoid(bias + sum(w * v for w, v in zip(weights, row)))
            class_weight = len(labels) / (2 * (positive if label else negative))
            error = (predicted - label) * class_weight
            db += error
            for index, value in enumerate(row):
                dw[index] += error * value
        rate = 0.08 / (1.0 + epoch / 350.0)
        bias -= rate * db / len(labels)
        for index in range(len(weights)):
            weights[index] -= rate * (dw[index] / len(labels) + 0.02 * weights[index])
    return weights, bias


def score(model: dict, features: dict) -> float:
    row = transform(features, model["featureNames"], model["centers"], model["scales"])
    return sigmoid(model["bias"] + sum(a * b for a, b in zip(model["weights"], row)))


def metrics(labels: list[int], probabilities: list[float]) -> dict:
    predicted = [int(probability >= 0.5) for probability in probabilities]
    tp = sum(a == 1 and b == 1 for a, b in zip(labels, predicted))
    tn = sum(a == 0 and b == 0 for a, b in zip(labels, predicted))
    fp = sum(a == 0 and b == 1 for a, b in zip(labels, predicted))
    fn = sum(a == 1 and b == 0 for a, b in zip(labels, predicted))
    return {"subjects": len(labels), "tp": tp, "tn": tn, "fp": fp, "fn": fn,
            "sensitivity": tp / (tp + fn) if tp + fn else None,
            "specificity": tn / (tn + fp) if tn + fp else None}


def train(sessions: Path, labels_path: Path, kind: str) -> dict:
    with labels_path.open(encoding="utf-8-sig", newline="") as file:
        label_rows = list(csv.DictReader(file))
    if not label_rows or not {"session", "subject_id", "label"}.issubset(label_rows[0]):
        raise ValueError("标签 CSV 必须包含 session、subject_id、label 列")
    records = []
    for row in label_rows:
        label = row["label"].strip()
        if label not in ("0", "1") or not row["subject_id"].strip():
            raise ValueError("label 必须为 0/1，subject_id 不可为空")
        path = (sessions / row["session"]).resolve()
        if not path.is_relative_to(sessions.resolve()):
            raise ValueError("会话文件必须位于 sessions 目录")
        records.append((row["subject_id"], int(label), session_features(path, kind)))
    subject_labels = {}
    for subject, label, _ in records:
        if subject in subject_labels and subject_labels[subject] != label:
            raise ValueError(f"同一受试者标签冲突：{subject}")
        subject_labels[subject] = label
    groups = {label: sorted(s for s, value in subject_labels.items() if value == label) for label in (0, 1)}
    if min(map(len, groups.values())) < 5:
        raise ValueError("每类至少需要 5 名不同受试者，且必须是临床确认标签")
    rng = random.Random(20260925)
    holdout = set()
    for group in groups.values():
        rng.shuffle(group)
        holdout.update(group[: max(1, round(len(group) * 0.2))])
    train_rows = [(label, features) for subject, label, features in records if subject not in holdout]
    test_rows = [(label, features) for subject, label, features in records if subject in holdout]
    names = FEATURES[kind]
    centers, scales = fit_transform([features for _, features in train_rows], names)
    x = [transform(features, names, centers, scales) for _, features in train_rows]
    weights, bias = fit_logistic(x, [label for label, _ in train_rows])
    model = {"format": MODEL_FORMAT, "kind": kind,
             "inputVersion": INPUT_VERSIONS[kind], "featureNames": names,
             "centers": centers, "scales": scales, "weights": weights, "bias": bias,
             "trainingSubjects": len(subject_labels) - len(holdout),
             "holdoutSubjects": len(holdout),
             "scoreMeaning": "未校准的研究分数；0.5 仅为软件演示切点，不是诊断阈值"}
    model["holdoutMetrics"] = metrics([label for label, _ in test_rows],
                                      [score(model, features) for _, features in test_rows])
    return model


def predict(model: dict, session: Path) -> dict:
    if model.get("format") != MODEL_FORMAT:
        raise ValueError("模型格式不兼容，请用当前版本重新训练")
    kind = model.get("kind")
    if kind not in FEATURES or model.get("inputVersion") != INPUT_VERSIONS[kind] \
            or model.get("featureNames") != FEATURES[kind]:
        raise ValueError("模型的输入版本或特征列表与当前记录格式不一致，请重新训练")
    features = session_features(session, kind)
    return {"kind": kind, "researchScore": score(model, features), "scoreMeaning": model["scoreMeaning"]}


def emit(payload: dict) -> None:
    # Windows 控制台为 GBK，直接 print 中文/符号可能抛 UnicodeEncodeError；统一按 UTF-8 写字节
    sys.stdout.buffer.write((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
    sys.stdout.flush()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    fit = sub.add_parser("train")
    fit.add_argument("--sessions", type=Path, required=True)
    fit.add_argument("--labels", type=Path, required=True)
    fit.add_argument("--kind", choices=FEATURES, required=True)
    fit.add_argument("--out", type=Path, required=True)
    run = sub.add_parser("predict")
    run.add_argument("--model", type=Path, required=True)
    run.add_argument("--session", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "train":
        model = train(args.sessions, args.labels, args.kind)
        args.out.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
        emit({"model": str(args.out), "holdoutMetrics": model["holdoutMetrics"]})
    else:
        emit(predict(json.loads(args.model.read_text(encoding="utf-8")), args.session))


if __name__ == "__main__":
    main()
