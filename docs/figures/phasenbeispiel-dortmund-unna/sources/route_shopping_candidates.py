import sys,json,gzip
from pathlib import Path
sys.path.insert(0,'/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO/share/sumo/tools')
sys.path.insert(0,"/private/tmp/synthesis-phase-example/python-deps")
import sumolib
base=Path(__file__).resolve().parents[1]
net=sumolib.net.readNet(str(base/'sources/network.net.xml.gz'))

import math
# WGS84 UTM zone 32, matching the saved SUMO network projection.
radius=6378137.;ecc=0.00669437999014;k=.9996
es=ecc/(1-ecc);lon0=math.radians(9)
def project(lon,lat):
 phi=math.radians(lat);lam=math.radians(lon);sn=math.sin(phi);co=math.cos(phi);tn=math.tan(phi)
 n=radius/math.sqrt(1-ecc*sn*sn);t=tn*tn;c=es*co*co;A=co*(lam-lon0)
 M=radius*((1-ecc/4-3*ecc**2/64-5*ecc**3/256)*phi-(3*ecc/8+3*ecc**2/32+45*ecc**3/1024)*math.sin(2*phi)+(15*ecc**2/256+45*ecc**3/1024)*math.sin(4*phi)-35*ecc**3/3072*math.sin(6*phi))
 return (k*n*(A+(1-t+c)*A**3/6+(5-18*t+t*t+72*c-58*es)*A**5/120)+500000-390825.62,k*(M+n*tn*(A*A/2+(5-t+9*c+4*c*c)*A**4/24+(61-58*t+t*t+600*c-330*es)*A**6/720))-5700971.43)
def unproject(x,y):
 x=x+390825.62-500000;y=y+5700971.43;mu=(y/k)/(radius*(1-ecc/4-3*ecc**2/64-5*ecc**3/256));e1=(1-math.sqrt(1-ecc))/(1+math.sqrt(1-ecc))
 ph=mu+(3*e1/2-27*e1**3/32)*math.sin(2*mu)+(21*e1**2/16-55*e1**4/32)*math.sin(4*mu)+151*e1**3/96*math.sin(6*mu)+1097*e1**4/512*math.sin(8*mu)
 sn=math.sin(ph);co=math.cos(ph);tn=math.tan(ph);n=radius/math.sqrt(1-ecc*sn*sn);r=radius*(1-ecc)/(1-ecc*sn*sn)**1.5;t=tn*tn;c=es*co*co;D=x/(n*k)
 lat=ph-n*tn/r*(D*D/2-(5+3*t+10*c-4*c*c-9*es)*D**4/24+(61+90*t+298*c+45*t*t-252*es-3*c*c)*D**6/720)
 lon=lon0+(D-(1+2*t+c)*D**3/6+(5-2*c+28*t-3*c*c+8*es+24*t*t)*D**5/120)/co
 return (math.degrees(lon),math.degrees(lat))
assert max(abs(v-w) for v,w in zip(unproject(*project(7.565,51.515)),(7.565,51.515)))<1e-7
net.convertLonLat2XY=project;net.convertXY2LonLat=unproject

edges=[e for e in net.getEdges() if e.allows('passenger')]
places={'A':(51.515,7.565),'B':(51.515,7.475),'C2':(51.535,7.50),'C3':(51.485,7.63)}
near={}
for key,(lat,lon) in places.items():
 xy=net.convertLonLat2XY(lon,lat)
 near[key]=sorted(edges,key=lambda e:sumolib.geomhelper.distancePointToPolygon(xy,e.getShape()))[:12]
routes=[]
for a,b in [('B','C2'),('C2','A'),('B','C3'),('C3','A')]:
 result=None
 for start in near[a]:
  for end in near[b]:
   path,cost=net.getFastestPath(start,end,vClass='passenger')
   if path:
    result=(path,cost);break
  if result:break
 if result is None:raise RuntimeError('Unreachable '+a+' '+b)
 path,cost=result
 points=[list(net.convertXY2LonLat(*xy)) for edge in path for xy in edge.getShape()]
 routes.append(dict(source='stored-sumo-network-fastest-passenger-path',fromZone=a,toZone=b,points=points,timeSeconds=cost,edgeIds=[e.getID() for e in path]))
(base/'sources/shopping-candidate-routes-v4.json').write_text(json.dumps(routes))
print([(r['fromZone'],r['toZone'],len(r['edgeIds'])) for r in routes])
