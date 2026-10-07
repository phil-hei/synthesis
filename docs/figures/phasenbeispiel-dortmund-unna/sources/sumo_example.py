import sys,pathlib,random,xml.etree.ElementTree as E,json,gzip
base=pathlib.Path(__file__).resolve().parent
sys.path[:0]=[str(base/'python-deps'),'/Library/Frameworks/EclipseSUMO.framework/EclipseSUMO/share/sumo/tools']
import sumolib
net=sumolib.net.readNet(str(base/'network.net.xml.gz'))
g=json.loads(gzip.decompress((base/'graph-routes.json.gz').read_bytes()))
edges=[e for e in net.getEdges() if e.allows('passenger') and e.getLength()>70]
def closest(lat,lon):
 x,y=net.convertLonLat2XY(lon,lat)
 return min(edges,key=lambda e:sumolib.geomhelper.distancePointToPolygon((x,y),e.getShape())).getID()
ids=[closest(*p) for p in g['places']]
x,y=net.convertLonLat2XY(g['places'][3][1],g['places'][3][0])
near=sorted(edges,key=lambda e:sumolib.geomhelper.distancePointToPolygon((x,y),e.getShape()))
ids[3]=next(e.getID() for e in near if e.allows('delivery') and net.getShortestPath(e,net.getEdge(ids[0]),vClass='delivery')[0])
print('Depot snapped to reachable edge:',ids[3])
root=E.Element('routes');E.SubElement(root,'vType',id='alexType',color='0.15,0.45,0.95');E.SubElement(root,'vType',id='van',vClass='delivery',color='0.95,0.5,0.05',length='6')
trips=[('Alex-Arbeit',10,ids[0],ids[1],'alexType'),('Lieferung-Alex',15,ids[3],ids[0],'van')]
rng=random.Random(42)
for i in range(700):
 a,b=rng.sample(edges,2);trips.append((f'background-{i}',rng.uniform(0,450),a.getID(),b.getID(),None))
for name,t,a,b,typ in sorted(trips,key=lambda v:v[1]):
 attrs=dict(id=name,depart=f'{t:.1f}',**{'from':a,'to':b},departLane='best',departSpeed='max')
 if typ:attrs['type']=typ
 E.SubElement(root,'trip',**attrs)
E.ElementTree(root).write(base/'trips.xml',encoding='utf-8',xml_declaration=True)
print(ids)
