import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('fit',Path(__file__).with_name('calibrate-regional-freight.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class CountFitTest(unittest.TestCase):
 def data(self):
  period=dict(month='2026-09',dayType='Mo-So',vehicleClass='SGV')
  stations=[dict(id='a',observed=25,fixedCompanyCrossings=5,split='calibration',incidence={'a':1},period=period),dict(id='b',observed=10,split='calibration',incidence={'b':1},period=period),dict(id='hold',observed=35,fixedCompanyCrossings=5,split='holdout',incidence={'a':1,'b':1},period=period)]
  return dict(networkSha256='a'*64,period=period,stations=stations,completeBackground=False)
 def test_recovers_known_background_keeps_company_fixed_and_scores_holdout(self):
  rows,report=m.fit([dict(id='a',trips=10),dict(id='b',trips=20)],self.data())
  self.assertEqual([20,10],[r['trips'] for r in rows]);self.assertEqual([0,0,0],[s['afterAbsoluteError'] for s in report['stations']]);self.assertFalse(report['empiricallyValidated'])
 def test_wrong_period_and_missing_holdout_fail(self):
  for kind in ['period','split','company']:
   data=self.data()
   if kind=='period':data['stations'][0]['period']=dict(data['period'],month='2026-07')
   if kind=='split':data['stations'][-1]['split']='calibration'
   if kind=='company':data['stations'][0]['fixedCompanyCrossings']=999
   with self.subTest(kind=kind),self.assertRaises(ValueError):m.fit([dict(id='a',trips=10),dict(id='b',trips=20)],data)
 def test_factor_bounds_and_unobserved_relation_preserved(self):
  data=self.data();data['stations'][0]['observed']=10000
  rows,report=m.fit([dict(id='a',trips=10),dict(id='b',trips=20),dict(id='unseen',trips=13)],data)
  self.assertEqual(4,report['factors']['a']);self.assertEqual(1,report['factors']['unseen']);self.assertEqual(13,rows[-1]['trips'])
if __name__=='__main__':unittest.main()
