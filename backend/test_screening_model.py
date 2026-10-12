"""仅用人工合成特征验证训练程序；这些样本没有医学含义。"""

import csv
import json
import tempfile
import unittest
from pathlib import Path

from screening_model import FEATURES, score, session_features, train


class ScreeningModelTest(unittest.TestCase):
    def test_group_holdout_and_prediction(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            labels = []
            for index in range(20):
                label = int(index >= 10)
                subject = f"synthetic_{index}"
                for repeat in range(2):
                    name = f"{subject}_{repeat}.json"
                    features = {key: None for key in FEATURES["PRESBYOPIA"]}
                    features["near_point_cm"] = 25 + label * 20 + repeat
                    features["near_both_decimal"] = 0.8 - label * 0.4
                    data = {"kind": "PRESBYOPIA", "assessment": {
                        "quality": "可用于探索性评估", "modelVersion": "research-rules-2",
                        "features": features}}
                    (folder / name).write_text(json.dumps(data), encoding="utf-8")
                    labels.append({"session": name, "subject_id": subject, "label": label})
            path = folder / "labels.csv"
            with path.open("w", newline="", encoding="utf-8") as file:
                writer = csv.DictWriter(file, fieldnames=["session", "subject_id", "label"])
                writer.writeheader()
                writer.writerows(labels)
            model = train(folder, path, "PRESBYOPIA")
            self.assertEqual(16, model["trainingSubjects"])
            self.assertEqual(4, model["holdoutSubjects"])
            self.assertGreater(score(model, session_features(folder / "synthetic_19_0.json", "PRESBYOPIA")),
                               score(model, session_features(folder / "synthetic_0_0.json", "PRESBYOPIA")))

    def test_rejects_bad_quality(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad.json"
            path.write_text(json.dumps({"kind": "STRABISMUS", "assessment": {
                "quality": "不足", "modelVersion": "research-rules-2"}}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "质量不足"):
                session_features(path, "STRABISMUS")


if __name__ == "__main__":
    unittest.main()
