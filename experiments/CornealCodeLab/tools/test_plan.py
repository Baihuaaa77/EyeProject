import unittest
from plan_experiment import plan


class PlanTests(unittest.TestCase):
    def test_blocks_are_balanced_and_reproducible(self):
        a=plan("screen",3,17)
        self.assertEqual(a,plan("screen",3,17))
        self.assertEqual(len(a["rounds"]),18)
        groups=[{str(sorted(r["configuration"].items())) for r in a["rounds"] if r["block"]==b} for b in [1,2,3]]
        self.assertEqual(len(groups[0]),6)
        self.assertEqual(groups[0],groups[1]);self.assertEqual(groups[1],groups[2])

    def test_invalid_block_count_rejected(self):
        with self.assertRaises(ValueError):plan("screen",0,17)

    def test_point_protocol_has_three_codes_and_matched_controls(self):
        rows = plan("coded",1,17)["rounds"]
        self.assertEqual(len(rows),9)
        for scheme in ["mSequence","manchester","sparsePulse"]:
            group = [r["configuration"] for r in rows if r["configuration"]["codingScheme"]==scheme]
            self.assertEqual({c["mode"] for c in group},{"steady","coded","dark"})
            for c in group:
                self.assertEqual(c["dotPlacement"],"nearCamera")
                self.assertEqual(c["dotDiameter"],96)
                if c["mode"]=="coded":
                    self.assertEqual(c["mean"]-c["modulation"],0)
                    self.assertEqual(c["mean"]+c["modulation"],1)
                if c["mode"]=="steady":
                    self.assertEqual(c["mean"],0.175 if scheme=="sparsePulse" else 0.5)


if __name__=="__main__":unittest.main()
