# SPDX-License-Identifier: Apache-2.0
import unittest
import numpy as np
from metering_baselines import app_policy, interval_mean, scores


class PolicyContracts(unittest.TestCase):
    def image(self, evs):
        luma=.18*np.exp2(np.asarray(evs,dtype=float))
        return np.repeat(luma[None,None,None,:],3,axis=1)

    def test_fractional_area_matches_application_fixtures(self):
        self.assertAlmostEqual(float(interval_mean([0,10],.05,.95)),5)
        self.assertAlmostEqual(float(interval_mean([0,10],.98,.995)),10)

    def test_app_highlight_limit_and_sign(self):
        # Translation of the app's 50% EV8 / 50% EV16 fixture, below clipping.
        result=scores(self.image([-8]*500+[0]*500))
        self.assertAlmostEqual(float(result['project_global'][0]),-3.5,places=10)
        shifted=scores(self.image([-9]*500+[-1]*500))
        self.assertAlmostEqual(float(shifted['project_global'][0]-result['project_global'][0]),-1,places=10)

    def test_three_policy_outputs_agree_on_uniform_gray(self):
        result=scores(self.image([0]*100))
        for name in ('project_global','uniform_ev','uniform_linear'):
            self.assertAlmostEqual(float(result[name][0]),0,places=10)

    def test_ev_average_and_linear_average_are_distinct(self):
        result=scores(self.image([-2,0]))
        self.assertAlmostEqual(float(result['uniform_ev'][0]),-1)
        self.assertAlmostEqual(float(result['uniform_linear'][0]),np.log2(.625))

    def test_reliability_and_clipped_highlight_fallback(self):
        rgb=np.full((1,3,1,1000),.18)
        rgb[:,:,:,:20]=1
        result=scores(rgb)
        self.assertTrue(result['base_reliable'][0])
        self.assertFalse(result['highlight_reliable'][0])
        rgb[:,:,:,:200]=1
        self.assertFalse(scores(rgb)['base_reliable'][0])

    def test_source_policy_has_expected_guardrails(self):
        policy=app_policy()
        self.assertEqual(policy['MAX_REDUCTION_EV'],.5)
        self.assertEqual(policy['HIGHLIGHT_HEADROOM_EV'],2.5)

if __name__=='__main__':
    unittest.main(verbosity=2)
