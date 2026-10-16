"""仅用人工合成特征验证训练程序；这些样本没有医学含义。记录结构对齐 shared/screening 当前写出的格式。"""

import csv
import json
import tempfile
import unittest
from pathlib import Path

from screening_model import FEATURES, INPUT_VERSIONS, predict, score, session_features, train


def record(kind: str, features: dict, sufficient: bool = True, version: str | None = None) -> dict:
    """按病种拼出 App 当前的记录形状：干眼特征在顶层，老视/斜视在 assessment 下。"""
    version = version or INPUT_VERSIONS[kind]
    risk = "MEDIUM" if sufficient else "UNKNOWN"
    if kind == "DRY_EYE":
        return {"protocolVersion": 1, "kind": kind, "assessmentVersion": version,
                "riskLevel": risk, "valid": sufficient, "features": features}
    return {"protocolVersion": 5 if kind == "STRABISMUS" else 4, "kind": kind, "assessment": {
        "modelVersion": version, "quality": "可用于探索性评估" if sufficient else "不足",
        "riskLevel": risk, "features": features}}


# 每个病种挑一个合成信号特征：阳性受试者在该特征上整体偏移
SIGNALS = {
    "DRY_EYE": ("vmr", 0.95, -0.3),
    "PRESBYOPIA": ("near_point_cm", 25.0, 20.0),
    "STRABISMUS": ("fixation_hde_prism", 2.0, 10.0),
}


class ScreeningModelTest(unittest.TestCase):
    def write(self, folder: Path, name: str, data: dict) -> Path:
        path = folder / name
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        return path

    def test_group_holdout_and_prediction_for_every_kind(self):
        for kind, (key, base, shift) in SIGNALS.items():
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                folder = Path(temp)
                labels = []
                for index in range(20):
                    label = int(index >= 10)
                    subject = f"synthetic_{index}"
                    for repeat in range(2):
                        features = {name: None for name in FEATURES[kind]}
                        features[key] = base + label * shift + repeat * 0.01
                        name = f"{subject}_{repeat}.json"
                        self.write(folder, name, record(kind, features))
                        labels.append({"session": name, "subject_id": subject, "label": label})
                path = folder / "labels.csv"
                with path.open("w", newline="", encoding="utf-8") as file:
                    writer = csv.DictWriter(file, fieldnames=["session", "subject_id", "label"])
                    writer.writeheader()
                    writer.writerows(labels)
                model = train(folder, path, kind)
                self.assertEqual(16, model["trainingSubjects"])
                self.assertEqual(4, model["holdoutSubjects"])
                self.assertEqual(INPUT_VERSIONS[kind], model["inputVersion"])
                self.assertIn("未校准", model["scoreMeaning"])
                self.assertGreater(score(model, session_features(folder / "synthetic_19_0.json", kind)),
                                   score(model, session_features(folder / "synthetic_0_0.json", kind)))
                self.assertEqual(kind, predict(model, folder / "synthetic_19_0.json")["kind"])

    def test_rejects_insufficient_quality(self):
        with tempfile.TemporaryDirectory() as temp:
            for kind in FEATURES:
                with self.subTest(kind=kind):
                    features = {name: 1.0 for name in FEATURES[kind]}
                    path = self.write(Path(temp), f"{kind}.json", record(kind, features, sufficient=False))
                    with self.assertRaisesRegex(ValueError, "质量不足"):
                        session_features(path, kind)

    def test_rejects_stale_version(self):
        with tempfile.TemporaryDirectory() as temp:
            for kind in FEATURES:
                with self.subTest(kind=kind):
                    features = {name: 1.0 for name in FEATURES[kind]}
                    path = self.write(Path(temp), f"{kind}.json", record(kind, features, version="research-rules-2"))
                    with self.assertRaisesRegex(ValueError, "版本不兼容"):
                        session_features(path, kind)

    def test_rejects_missing_feature_keys(self):
        with tempfile.TemporaryDirectory() as temp:
            features = {name: 1.0 for name in FEATURES["STRABISMUS"] if name != "bsi"}
            path = self.write(Path(temp), "s.json", record("STRABISMUS", features))
            with self.assertRaisesRegex(ValueError, "bsi"):
                session_features(path, "STRABISMUS")

    def test_null_and_boolean_become_missing(self):
        with tempfile.TemporaryDirectory() as temp:
            features = {name: 1.0 for name in FEATURES["DRY_EYE"]}
            features["vmr"] = None
            features["decline_sec"] = True
            path = self.write(Path(temp), "d.json", record("DRY_EYE", features))
            parsed = session_features(path, "DRY_EYE")
            self.assertIsNone(parsed["vmr"])
            self.assertIsNone(parsed["decline_sec"])
            self.assertEqual(1.0, parsed["blink_rate_per_min"])

    def test_predict_rejects_model_of_other_version(self):
        with tempfile.TemporaryDirectory() as temp:
            features = {name: 1.0 for name in FEATURES["PRESBYOPIA"]}
            path = self.write(Path(temp), "p.json", record("PRESBYOPIA", features))
            model = {"format": "eyeproject-screening-logistic-v2", "kind": "PRESBYOPIA",
                     "inputVersion": "research-rules-2", "featureNames": FEATURES["PRESBYOPIA"]}
            with self.assertRaisesRegex(ValueError, "重新训练"):
                predict(model, path)


if __name__ == "__main__":
    unittest.main()
