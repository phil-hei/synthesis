from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import json,math,html,xml.etree.ElementTree as ET,gzip,sys
out=Path(__file__).resolve().parents[1];base=out;g=json.loads(gzip.decompress((out/'sources/graph-routes.json.gz').read_bytes()));W,H=2400,1350
font='/System/Library/Fonts/Supplemental/Arial.ttf';bold='/System/Library/Fonts/Supplemental/Arial Bold.ttf'
navy='#17334C';blue='#2D6CDF';orange='#D78215';purple='#8051AD';teal='#168B9C';grey='#98ACBA'
def f(size,b=False):return ImageFont.truetype(bold if b else font,size)
def xy(lon,lat):return (60+(lon-7.425)/(7.75-7.425)*1620,205+(51.56-lat)/(51.56-51.45)*940)
class Canvas:
 def __init__(self):
  self.im=Image.new('RGB',(W,H),'white');self.d=ImageDraw.Draw(self.im);self.svg=['<svg xmlns="http://www.w3.org/2000/svg" width="2400" height="1350" viewBox="0 0 2400 1350"><rect width="2400" height="1350" fill="white"/>']
 def line(self,pts,color,width):
  self.d.line(pts,fill=color,width=width,joint='curve');self.svg.append('<polyline points="'+ ' '.join(f'{x:.1f},{y:.1f}' for x,y in pts)+f'" fill="none" stroke="{color}" stroke-width="{width}" stroke-linejoin="round"/>')
 def rect(self,box,color):
  self.d.rectangle(box,fill=color);x,y,x2,y2=box;self.svg.append(f'<rect x="{x}" y="{y}" width="{x2-x}" height="{y2-y}" fill="{color}"/>')
 def text(self,x,y,s,size=30,color=navy,b=False):
  self.d.text((x,y),s,font=f(size,b),fill=color);self.svg.append(f'<text x="{x}" y="{y+size*.92}" font-family="Arial,sans-serif" font-size="{size}" fill="{color}" font-weight="{700 if b else 400}">{html.escape(s)}</text>')
 def point(self,x,y,label,color):
  self.d.ellipse((x-15,y-15,x+15,y+15),fill=color,outline='white',width=4);self.svg.append(f'<circle cx="{x}" cy="{y}" r="15" fill="{color}" stroke="white" stroke-width="4"/>');self.rect((x+22,y-8,x+32+len(label)*15,y+34),'white');self.text(x+28,y-6,label,26,color,True)
 def save(self,name,svg=True):
  self.im.save(out/(name+'.png'));self.svg.append('</svg>');
  if svg:(out/(name+'.svg')).write_text(''.join(self.svg))
def frame(c,num,title,sub,color):
 c.rect((0,0,W,130),color);c.text(60,25,f'{num}  {title}',58,'white',True);c.text(60,145,sub,29)
 c.rect((1740,205,2340,1160),'#F1F5F9');c.text(60,1215,'Gebiet: Dortmund – Brackel – Unna | OSM / GraphHopper',26)
 c.text(60,1260,'© OpenStreetMap-Mitwirkende · Beispielszenario; keine regionale Kalibrierung.',25,grey)
def network(c):
 c.rect((60,205,1680,1160),'#F8FAFC')
 for e in g['edges']:
  p=[xy(*v) for v in e['points']];c.line(p,'#B7C4CF',2)
 # Selected model cells are illustrative, not the application's generated zoning.
 for lat,lon in g['places']:
  x,y=xy(lon,lat);c.line([(x-56,y-56),(x+56,y-56),(x+56,y+56),(x-56,y+56),(x-56,y-56)],'#7297B5',3)
 for lon,lat,label in [(7.45,51.545,'Dortmund'),(7.57,51.545,'Brackel'),(7.68,51.477,'Unna')]:c.text(*xy(lon,lat),label,32,navy,True)
def locations(c):
 for p,label,col in zip(g['places'],['A · Zuhause','B · Arbeit','C · Einkauf','D · Depot','E · Zustellzone'],[blue,blue,purple,orange,orange]):c.point(*xy(p[1],p[0]),label,col)
for phase in [6,7]:
 c=Canvas();color=orange if phase==6 else purple
 frame(c,phase,'Netz & Logistik' if phase==6 else 'Nachfragegenerierung','Ein durchgängiges Beispiel: Alex wohnt in Brackel und arbeitet in Dortmund.',color);network(c)
 if phase==7:
  for r in g['routes'][:3]:c.line([xy(*p) for p in r['points']],blue,7)
  for r in g['routes'][3:]:
   pts=[xy(*p) for p in r['points']];c.line(pts,orange,6)
   lengths=[math.hypot(b[0]-a[0],b[1]-a[1]) for a,b in zip(pts,pts[1:])];target=sum(lengths)*0.55;walked=0
   for a,b,length in zip(pts,pts[1:],lengths):
    if length>0 and walked+length>=target:
     t=(target-walked)/length;x0=a[0]+t*(b[0]-a[0]);y0=a[1]+t*(b[1]-a[1]);ux=(b[0]-a[0])/length;uy=(b[1]-a[1])/length
     c.line([(x0-ux*20-uy*10,y0-uy*20+ux*10),(x0,y0),(x0-ux*20+uy*10,y0-uy*20-ux*10)],orange,6);break
    walked+=length
 locations(c)
 x=1780;y=245
 c.text(x,y,'Reisematrizen' if phase==6 else 'Alex · Montag',38,color,True);y+=72
 if phase==6:
  for label,r in zip(['A → B  Zuhause – Arbeit','B → C  Arbeit – Einkauf','C → A  Einkauf – Zuhause'],g['routes'][:3]):
   c.text(x,y,label,26,b=True);c.text(x,y+42,f"{r['distanceM']/1000:.1f} km  ·  {r['timeMin']:.1f} min",32);y+=118
  c.text(x,y+20,'Logistikgrundlagen',34,color,True);c.text(x,y+82,'D: modellierter Depotstandort',26);c.text(x,y+125,'Zustellziele, Flotte, Paketraten',26)
  c.text(x,1010,'Echte GraphHopper-Kanten.',26,b=True);c.text(x,1055,'Standorte und Zellen: Beispiel.',25);c.text(x,1100,'Noch keine erzeugten Aufträge.',25)
 else:
  for label,time in [('A → B  zur Arbeit','08:30–16:30  Arbeit'),('B → C  zum Einkauf','17:00–17:30  Einkauf'),('C → A  nach Hause','anschließend Zuhause')]:c.text(x,y,label,28,b=True);c.text(x,y+42,time,26);y+=110
  c.text(x,y+25,'Separate Paketnachfrage',32,orange,True);c.text(x,y+85,'D → A → E → D  Zustelltour',25)
  c.text(x,825,'A: Paket für Alex abliefern',25);c.text(x,868,'E: weitere Pakete abliefern',25)
  c.line([(x,945),(x+60,945)],blue,7);c.text(x+80,927,'Personenwege',27)
  c.line([(x,1000),(x+60,1000)],orange,7);c.text(x+80,982,'Zustellbeispiel',27)
  c.text(x,1050,'Illustrative Nachfrage; Routen',25,b=True);c.text(x,1092,'zur Darstellung mit GraphHopper.',25)
 c.save(f'phase-{phase}')
c=Canvas();frame(c,8,'Verkehrsablauf & Reisezeiten','Echter SUMO-Lauf auf demselben OSM-Netz · Nahaufnahme um Alex’ Arbeitsweg.',teal)
screen=Image.open(base/'sumo-screenshot.png').convert('RGB');screen=screen.resize((1620,955));c.im.paste(screen,(60,205))
c.text(1780,245,'Verkehr auf dem Netz',36,teal,True)
for i,s in enumerate(['Alex’ Arbeitsweg und eine','Lieferfahrt laufen zusammen','mit weiterer Beispielnachfrage.']):c.text(1780,330+i*43,s,27)
c.text(1780,515,'Momentaufnahme',33,teal,True);c.text(1780,580,'180 Sekunden nach Start',28)
c.text(1780,685,'Auswertbare Ergebnisse',33,teal,True)
for i,s in enumerate(['Fahrzeiten und Verzögerungen','Geschwindigkeiten auf Straßen','Wartezeiten an Knoten']):c.text(1780,755+i*55,s,27)
c.text(1780,990,'Echter SUMO-GUI-Screenshot.',25,b=True);c.text(1780,1040,'Nachfrage: illustratives Beispiel.',25);c.text(1780,1090,'Keine logiTopp-Vollsimulation.',25)
c.save('phase-8',False)
print('Rendered three figures')
