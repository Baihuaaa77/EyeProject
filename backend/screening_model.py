"""用已标注会话训练两个独立的探索性筛查模型；仅依赖 Python 标准库。"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
from pathlib import Path


FEATURES = {
    "PRESBYOPIA": [
        "age_years", "near_right_decimal", "near_left_decimal", "near_both_decimal",
        "near_point_cm", "near_point_demand_d", "near_point_mad_cm",
        "flipper_near_accuracy", "flipper_far_accuracy", "flipper_correct_cycles",
        "daily_correction_mode", "near_add_present", "prior_eye_surgery",
        "resolution_limited", "invalid_trials",
    ],
    "STRABISMUS": [
        "age_years", "quality_rate", "fixed_optical_h_iris", "fixed_optical_v_iris",
        "fixed_optical_h_mad_iris", "fixed_optical_v_mad_iris",
        "cover_refix_median_iris", "cover_refix_events",
        "pursuit_velocity_correlation", "fixed_optical_frames", "pursuit_optical_pairs",
    ],
}


def session_features(path: Path, kind: str) -> dict[str, float | None]:
    session = json.loads(path.read_text(encoding="utf-8"))
    if session.get("kind") != kind:
        raise ValueError(f"病种不匹配：{path}")
    assessment = session.get("assessment", {})
    if assessment.get("quality") != "可用于探索性评估":
        raise ValueError(f"质量不足，拒绝训练或推理：{path}")
    if assessment.get("modelVersion") != "research-rules-2":
        raise ValueError(f"特征版本不兼容：{path}")
    raw = assessment.get("features", {})
    result = {}
    for key in FEATURES[kind]:
        value = raw.get(key)
        result[key] = float(value) if isinstance(value, (int, float)) and math.isfinite(value) else None
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
    model = {"format": "eyeproject-screening-logistic-v1", "kind": kind,
             "inputVersion": "research-rules-2", "featureNames": names,
             "centers": centers, "scales": scales, "weights": weights, "bias": bias,
             "trainingSubjects": len(subject_labels) - len(holdout),
             "holdoutSubjects": len(holdout),
             "scoreMeaning": "未校准的研究分数；0.5 仅为软件演示切点，不是诊断阈值"}
    model["holdoutMetrics"] = metrics([label for label, _ in test_rows],
                                      [score(model, features) for _, features in test_rows])
    return model


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    fit = sub.add_parser("train")
    fit.add_argument("--sessions", type=Path, required=True)
    fit.add_argument("--labels", type=Path, required=True)
    fit.add_argument("--kind", choices=FEATURES, required=True)
    fit.add_argument("--out", type=Path, required=True)
    predict = sub.add_parser("predict")
    predict.add_argument("--model", type=Path, required=True)
    predict.add_argument("--session", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "train":
        model = train(args.sessions, args.labels, args.kind)
        args.out.write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"model": str(args.out), "holdoutMetrics": model["holdoutMetrics"]}, ensure_ascii=False))
    else:
        model = json.loads(args.model.read_text(encoding="utf-8"))
        if model.get("format") != "eyeproject-screening-logistic-v1":
            raise ValueError("模型格式不兼容")
        features = session_features(args.session, model["kind"])
        print(json.dumps({"kind": model["kind"], "researchScore": score(model, features),
                          "scoreMeaning": model["scoreMeaning"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
