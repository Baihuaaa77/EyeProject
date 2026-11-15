"""离线复核的真实文件回归，不用 mock。"""
import array
import json
import math
from pathlib import Path
import tempfile
import unittest

import review


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.iris = {"center":{"x":0.5,"y":0.5},"radiusX":0.2,"radiusY":0.4,
                     "eyelid":[{"x":0.2,"y":0.1},{"x":0.8,"y":0.1},{"x":0.8,"y":0.9},{"x":0.2,"y":0.9}]}

    def tearDown(self):
        self.temp.cleanup()

    def fixture(self):
        events = [{"hostTime":i/2,"code":1 if i%2 else -1,"chip":i} for i in range(64)]
        binary = bytearray(); rows=[]
        for i in range(32,64):
            p=array.array("f",[90]*8192)
            p[32*128+64] = 160+20*events[i]["code"]
            iris=self.iris
            if i%7==0:
                # 瞳孔配准异常帧的候选已在搜索范围外，不应污染复算相关。
                iris={**self.iris,"center":{"x":0.1,"y":0.1}}
                p[32*128+64]=30
            rows.append({"hostTime":i/2+0.1,"eye":"image_left","status":"valid","saturation":0,
                         "patchOffset":len(binary),"iris":iris})
            if review.sys.byteorder!="little":p.byteswap()
            binary.extend(p.tobytes())
        (self.root/"patches.f32").write_bytes(binary)
        (self.root/"frames.jsonl").write_text("\n".join(json.dumps(r) for r in rows),encoding="utf8")
        (self.root/"display_events.json").write_text(json.dumps(list(reversed(events))),encoding="utf8")
        summary={"configuration":{"mode":"coded","chipSeconds":0.5},"startHostTime":0,"algorithmVersion":"corneal-feasibility-4-shape-screen"}
        eye={"eye":"image_left","point":{"x":64.5/128,"y":32.5/64},"lagSeconds":0,"patchWidth":128,"heldOutCorrelation":1}
        return summary,eye,rows

    def test_replay_matches_iris_gate_and_sorted_display_events(self):
        summary,eye,rows=self.fixture()
        audit=review.replay(self.root,summary,eye)
        self.assertEqual(audit["frames"],sum(i%7!=0 for i in range(32,64)))
        self.assertLess(audit["absolute_difference"],1e-8)

    def test_invalid_binary_offset_is_reported(self):
        summary,eye,rows=self.fixture()
        rows[0]["patchOffset"]=-1
        (self.root/"frames.jsonl").write_text(json.dumps(rows[0]),encoding="utf8")
        with self.assertRaises(ValueError):review.replay(self.root,summary,eye)

    def test_screen_mode_does_not_claim_independent_python_recomputation(self):
        audit=review.replay(self.root,{"configuration":{"mode":"screenTarget"}},{"screenResult":{"structuralEvidence":True}})
        self.assertIn("不独立复算",audit["scope"])

    def test_connected_component_center_is_subpixel(self):
        p=[40+130*math.exp(-((i%128+0.5-64)**2+(i//128+0.5-32)**2)/8) for i in range(8192)]
        centers=review.component_centers(p,self.iris,128,64)
        self.assertEqual(len(centers),1)
        self.assertAlmostEqual(centers[0][0],64,places=6)
        self.assertAlmostEqual(centers[0][1],32,places=6)

    def test_nonfinite_and_constant_correlation_rejected(self):
        self.assertIsNone(review.correlation([float("nan")]*10,list(range(10))))
        self.assertIsNone(review.correlation([1]*10,list(range(10))))


if __name__=="__main__":unittest.main()
