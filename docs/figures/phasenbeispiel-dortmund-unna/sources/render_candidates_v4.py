"""Several illustrative shopping alternatives; no selected destination or trip chain."""
from pathlib import Path
source=Path(__file__).with_name('render_slides_v2.py').read_text()
exec(compile(source.split('c=Canvas();map_base(c,4);locations_v2(c,12)')[0],'helpers','exec'))
c=Canvas();map_base(c,4)
extra=json.loads((out/'sources/shopping-candidate-routes-v4.json').read_text())
route(c,g['routes'][0],blue,7)
for r in g['routes'][1:3]:route(c,r,purple,6)
for r in extra:route(c,r,'#168B9C' if 'C2' in (r['fromZone'],r['toZone']) else '#B84985',6)

for i,(p,label,col) in enumerate(zip(g['places'][:4],['A · Wohnen','B · Arbeit','C1 · Einkauf','D · Depot'],[blue,blue,purple,orange])):
    c.point(*xy(p[1],p[0]),label,col)
for lon,lat,label in [(7.50,51.535,'C2 · Einkauf'),(7.63,51.485,'C3 · Einkauf')]:
    x,y=xy(lon,lat)
    c.line([(x-56,y-56),(x+56,y-56),(x+56,y+56),(x-56,y+56),(x-56,y-56)],'#7297B5',3)
    c.point(x,y,label,'#168B9C' if label.startswith('C2') else '#B84985')
x=1780
c.text(x,85,'Pkw-Matrix: Beispieleinträge',32,orange,True)
c.text(x,140,'Routenalternativen vor der Zielwahl',28)
y=215
for label,r in zip(['A → B','B → C1','C1 → A'],g['routes'][:3]):
    c.text(x,y,label,30,b=True)
    c.text(x+175,y,f"{r['distanceM']/1000:.1f} km  ·  {r['timeMin']:.1f} min".replace('.',','),32)
    y+=85
c.text(x,510,'Mögliche Einkaufszonen',34,purple,True)
c.text(x,565,'C1, C2 und C3 stehen zur Auswahl.',28)
c.text(x,607,'Das Ziel wird später gewählt:',28)
c.text(x,647,'mit mobiTopp, anhand von',28)
c.text(x,687,'Attraktivitäten und Reisematrizen.',28)
c.text(x,770,'Logistikparameter',34,orange,True)
for i,line in enumerate(['D: beispielhafter Depotstandort','Paketraten, Zustellanteile und Flotte']):c.text(x,825+i*43,line,28)
c.text(x,925,'C1: Route aus GraphHopper.',27,b=True)
c.text(x,963,'C2/C3: Routing im SUMO-Netz.',26)
c.save('folie-12-v4')
print('Created folie-12-v4 PNG/SVG')
