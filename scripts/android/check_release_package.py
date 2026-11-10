"""检查正式 APK 未包含模型实验资产，结果写到构建报告。"""
import json
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("app/build/outputs/apk/release/app-release-unsigned.apk")
with zipfile.ZipFile(apk) as package:
    names = package.namelist()
    forbidden = [n for n in names if any(token in n.lower() for token in ("l2cs_net", "blaze_face_short_range", "gaze_native", "liblitert"))]
    assert not forbidden, f"Experimental payload in release: {forbidden}"
    assert names.count("assets/face_landmarker.task") == 1, "Expected one shared face landmarker asset"
report = {"apk": str(apk), "bytes": apk.stat().st_size, "shared_face_models": 1, "experimental_payloads": forbidden}
out = Path("app/build/reports/release-package.json")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report))
