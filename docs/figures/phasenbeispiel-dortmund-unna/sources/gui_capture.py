import pathlib,sys,os
base=pathlib.Path(__file__).resolve().parent
sys.path[:0]=[str(base/'python-deps'),'/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO/share/sumo/tools']
import traci
traci.start(['sumo-gui','-n',str(base/'network.net.xml.gz'),'-r',str(base/'routes.rou.xml.gz'),'--gui-settings-file',str(base/'view.xml'),'--seed','42','--start','--quit-on-end','--no-step-log','true','--no-warnings','true','--window-size','1400,950'])
for i in range(180):traci.simulationStep()
x,y=traci.vehicle.getPosition('Alex-Arbeit')
traci.gui.setBoundary('View #0',x-1700,y-1000,x+1700,y+1000)
print('Vehicles at t=180:',len(traci.vehicle.getIDList()), 'Alex position',x,y)
traci.gui.screenshot('View #0',str(base/'sumo-native.png'),1600,1000)
traci.simulationStep()
traci.close()
print('Screenshot saved')
