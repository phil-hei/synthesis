"""Shopping alternatives and matrix entries only; no demand route overlay."""
from pathlib import Path
source=Path(__file__).with_name('render_candidates_v3.py').read_text()
exec(compile(source.split('x=1780')[0],'helpers','exec'))
x=1780
c.text(x,85,'Pkw-Matrix: Beispieleinträge',32,orange,True)
c.text(x,145,'Relation',27,b=True)
c.text(x+190,145,'Distanz (km)',27,b=True)
c.text(x+395,145,'Zeit (min)',27,b=True)
rows=list(zip(['A → B','B → C1','C1 → A'],g['routes'][:3]))
extra=json.loads((out/'sources/shopping-candidate-matrix-v5.json').read_text())
rows.extend((r['fromZone']+' → '+r['toZone'],r) for r in extra)
for i,(label,r) in enumerate(rows):
 y=205+i*49
 c.text(x,y,label,29,b=True)
 c.text(x+235,y,f"{r['distanceM']/1000:.1f}".replace('.',','),30)
 c.text(x+430,y,f"{r['timeMin']:.1f}".replace('.',','),30)
c.text(x,610,'Mögliche Einkaufszonen',34,purple,True)
for i,line in enumerate(['C1, C2 und C3 stehen zur Auswahl.','Das Ziel wird später mit mobiTopp','anhand von Attraktivitäten und','Reisematrizen gewählt.']):c.text(x,662+i*38,line,28)
c.text(x,835,'Logistikparameter',34,orange,True)
c.text(x,890,'D: Depot · Paketraten und Flotte',28)
c.text(x,945,'Basiswerte ohne Verkehrsbelastung.',26)
c.save('folie-12-v5')
print('Created folie-12-v5 PNG/SVG')
