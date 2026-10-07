"""Title-free slide 12/18 figures; reuses the original GraphHopper geometry."""
from pathlib import Path
source = Path(__file__).with_name('render.py').read_text()
exec(compile(source.split('for phase in [6,7]:')[0], 'render_helpers', 'exec'))
H = 1100
original_init = Canvas.__init__
def canvas_init(self):
    original_init(self)
    self.svg[0] = self.svg[0].replace("1350", "1100")
Canvas.__init__ = canvas_init

def xy(lon, lat):
    return (60+(lon-7.425)/(7.75-7.425)*1620,45+(51.56-lat)/(51.56-51.45)*940)

def map_base(c, count):
    c.rect((60,45,1680,1000),'#F8FAFC')
    for edge in g['edges']:
        c.line([xy(*p) for p in edge['points']], '#B7C4CF', 2)
    for lat,lon in g['places'][:count]:
        x,y=xy(lon,lat)
        c.line([(x-56,y-56),(x+56,y-56),(x+56,y+56),(x-56,y+56),(x-56,y-56)],'#7297B5',3)
    for lon,lat,label in [(7.45,51.545,'Dortmund'),(7.57,51.545,'Brackel'),(7.68,51.477,'Unna')]:
        c.text(*xy(lon,lat),label,32,navy,True)
    c.rect((1740,45,2360,1000),'#F1F5F9')
    c.text(60,1040,'© OpenStreetMap-Mitwirkende · Dortmund/Unna · Standorte und Zellen illustrativ',27,grey)

def locations_v2(c, phase):
    labels=['A · Wohnen','B · Arbeit','C · Einkaufskandidat' if phase==12 else 'C · Einkauf','D · Depot','E · Zustellzone']
    for i,(p,label,col) in enumerate(zip(g['places'],labels,[blue,blue,purple,orange,orange])):
        if phase==12 and i==4: continue
        x,y=xy(p[1],p[0])
        # Slightly lower the shopping label to separate it from the home label.
        c.point(x,y,label,col)

def route(c, r, color, width):
    pts=[xy(*p) for p in r['points']]
    c.line(pts,color,width)
    lengths=[math.hypot(b[0]-a[0],b[1]-a[1]) for a,b in zip(pts,pts[1:])]
    target=sum(lengths)*0.55;walked=0
    for a,b,length in zip(pts,pts[1:],lengths):
        if length>0 and walked+length>=target:
            t=(target-walked)/length;x=a[0]+t*(b[0]-a[0]);y=a[1]+t*(b[1]-a[1]);ux=(b[0]-a[0])/length;uy=(b[1]-a[1])/length
            c.line([(x-ux*22-uy*11,y-uy*22+ux*11),(x,y),(x-ux*22+uy*11,y-uy*22-ux*11)],color,width)
            break
        walked+=length

c=Canvas();map_base(c,4);locations_v2(c,12)
x=1780
c.text(x,85,'Pkw-Basisreisezeiten',36,orange,True)
y=160
for label,r in zip(['A → B  Wohnen – Arbeit','B → C  Arbeit – Einkauf','C → A  Einkauf – Wohnen'],g['routes'][:3]):
    c.text(x,y,label,30,b=True)
    value=f"{r['distanceM']/1000:.1f} km  ·  {r['timeMin']:.1f} min".replace('.',',')
    c.text(x,y+45,value,34);y+=120
c.text(x,555,'Logistikparameter',36,orange,True)
for i,line in enumerate(['D: beispielhafter Depotstandort','Paketraten und Zustellanteile','Paketdienste und Fahrzeugflotte']):c.text(x,620+i*45,line,29)
for i,line in enumerate(['Straßennetz aus GraphHopper.','Ohne simulierte Verkehrsbelastung.','C ist ein möglicher Einkaufsort.','Noch keine Personenwege/Aufträge.']):c.text(x,805+i*42,line,28,b=(i==0))
c.save('folie-12-v2')

c=Canvas();map_base(c,5)
for r in g['routes'][:3]:route(c,r,blue,7)
for r in g['routes'][3:]:route(c,r,orange,6)
locations_v2(c,18)
c.text(x,80,'Alex · Montag · Pkw-Beispiel',34,purple,True)
y=150
for label,time in [('A → B  zur Arbeit','08:30–16:30  Arbeit'),('B → C  zum Einkauf','17:00–17:30  Einkauf'),('C → A  nach Hause','anschließend Aufenthalt zu Hause')]:
    c.text(x,y,label,31,b=True);c.text(x,y+42,time,28);y+=115
c.text(x,520,'Separate Paketzustellung',34,orange,True)
c.text(x,580,'D → A → E → D',32,b=True)
c.text(x,630,'A: Paket an Alex’ Haushalt',29)
c.text(x,675,'E: weitere Pakete in anderer Zone',29)
c.text(x,720,'D: Rückkehr zum Depot',29)
c.line([(x,795),(x+60,795)],blue,7);c.text(x+80,777,'Alex’ Personenwege',29)
c.line([(x,850),(x+60,850)],orange,7);c.text(x+80,832,'Zustelltour',29)
c.text(x,905,'Illustratives Nachfragebeispiel.',28,b=True)
c.text(x,948,'Straßenrouten mit GraphHopper.',28)
c.save('folie-18-v2')
print('Created folie-12-v2 and folie-18-v2 (PNG/SVG)')
