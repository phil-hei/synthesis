from pathlib import Path
source=Path(__file__).with_name('render.py').read_text()
exec(compile(source.split('for phase in [6,7]:')[0],'helpers','exec'))
W,H=2400,1000
c=Canvas();c.svg[0]=c.svg[0].replace('1350','1000')
colors=['#365D91','#217F97','#D17B12','#478947','#8051AD']
blocks=[(['Szenario-','konfiguration'],'Schritt 1',['Gebiet und Zellgröße','Modellumfang','Szenarioannahmen']),(['Daten-','aufbereitung'],'Schritte 2–3',['Verkehrszellen','OSM und Zensus','Regionale Daten','Standortattraktivitäten']),(['Reisematrizen &','Logistikparameter'],'Schritt 4',['Reisezeiten und Distanzen','Verkehrsmittelkosten','Depots und Fahrzeuge','Paketraten und Anteile']),(['Bevölkerung &','Aktivitäten'],'Schritte 5–6',['Haushalte aus KIT-Pool','Personen und Pkw','Feste Arbeits-/Bildungsorte','Wochenpläne mit actiTopp']),(['Nachfrage-','generierung'],'Schritt 7',['Ziele und Verkehrsmittel','Personenwege: mobiTopp','Pakete und Touren: logiTopp','Verkehrsströme'])]
def arrow(points,col=navy):
 c.line(points,col,4);a,b=points[-2:];dx=b[0]-a[0];dy=b[1]-a[1];length=math.hypot(dx,dy);ux=dx/length;uy=dy/length
 c.line([(b[0]-ux*14-uy*7,b[1]-uy*14+ux*7),b,(b[0]-ux*14+uy*7,b[1]-uy*14-ux*7)],col,4)
for i,(title,step,lines) in enumerate(blocks):
 x=40+i*470;col=colors[i]
 c.rect((x,40,x+430,535),'#F1F5F9');c.rect((x,40,x+430,160),col)
 for j,line in enumerate(title):c.text(x+22,58+j*44,line,34,'white',True)
 c.text(x+22,185,step,28,col,True)
 for j,line in enumerate(lines):c.text(x+22,253+j*59,line,28)
 if i<4:arrow([(x+430,305),(x+468,305)])
# Results branch after demand generation (target architecture).
arrow([(2135,535),(2135,640),(1665,640),(1665,700)])
arrow([(2135,535),(2135,700)])
for x,title,col,lines in [(1450,'Verkehrssimulation','#168B9C',['Schritt 8 · SUMO','Straßenrouten übernehmen','Verkehrsablauf und Fahrzeiten']),(1920,'MobileCity-Export','#365D91',['Modelldaten und Nachfrage','Zielformat aufbereiten','Konfigurationspaket bereitstellen'])]:
 c.rect((x,700,x+430,925),'#F1F5F9');c.rect((x,700,x+430,755),col);c.text(x+20,710,title,30,'white',True)
 for j,line in enumerate(lines):c.text(x+20,775+j*42,line,26)
c.text(40,620,'Neue Reihenfolge:',32,navy,True)
c.text(40,679,'Reisematrizen stehen vor der Bevölkerungssynthese bereit.',30)
c.text(40,732,'Haushalte und Personen entstehen erst in den Schritten 5–6.',30)
c.text(40,785,'Paketparameter werden vor konkreten Aufträgen festgelegt.',30)
c.text(40,907,'Zielarchitektur · SUMO-Ergebnisse können zusätzlich in den Export einfließen.',27,grey)
c.save('pipeline-zielarchitektur-v2')
