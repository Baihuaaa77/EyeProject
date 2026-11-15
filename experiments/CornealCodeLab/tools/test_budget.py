import unittest
from optical_budget import budget
from evaluate_roc import roc


class BudgetTests(unittest.TestCase):
    def test_measured_scale_changes_predictions_linearly(self):
        a=budget(36,330);b=budget(72,330)
        self.assertAlmostEqual(a['screen_native_pixels'][0],2.34,delta=.03)
        self.assertAlmostEqual(b['dot_native_pixels'],a['dot_native_pixels']*2)

    def test_roc_handles_ties_and_requires_both_classes(self):
        self.assertEqual(roc([dict(score=.5,label=0),dict(score=.5,label=1)])['auc'],.5)
        self.assertEqual(roc([dict(score=.1,label=0),dict(score=.9,label=1)])['auc'],1)
        with self.assertRaises(ValueError):roc([dict(score=.1,label=0)])


if __name__=='__main__':unittest.main()
