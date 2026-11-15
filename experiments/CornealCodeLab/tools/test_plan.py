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


if __name__=="__main__":unittest.main()
