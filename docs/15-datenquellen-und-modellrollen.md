# Datenquellen und Modellrollen verständlich erklärt

## Zensus und synthetischer Haushaltspool

Der Zensus liefert für ein Gebiet statistische Häufigkeiten, etwa Einwohner nach Alter und Haushalte nach Größe. Die öffentlichen Tabellen sind keine Liste individueller Personen mit ihren Familienbeziehungen. Quelle: [Zensus-Haushaltsergebnisse](https://ergebnisse.zensus2022.de/datenbank/online/statistic/5000H).

Der synthetische Haushaltspool liefert dagegen einzelne künstliche Haushaltskonstellationen: etwa zwei Erwachsene mit einem Kind und konkreten modellierten Alterswerten. Unsere Synthese kann ganze Haushalte ziehen und sie optional so nachgewichten, dass regionale Alters-/Haushaltsgrößen besser getroffen werden. Nicht vorhandene Haushaltstypen kann eine reine Nachgewichtung nicht neu erfinden.

Beispiel: Die Zielstatistik fordert viele Einpersonenhaushalte. Die Synthese zieht entsprechend häufiger solche Haushaltsmuster aus dem Pool. Die Personen sind künstlich; die regionale Häufigkeit ist die statistische Zielgröße. Einkommens-, Pkw-, Orts- und Aktivitätsmerkmale werden zusätzlich modelliert.

„Marne-Beispiel“ bezeichnet das Beispielprojekt der KIT-Bevölkerungssynthese, aus dem der gebündelte Pool vorbereitet wurde. Marne ist eine Stadt in Dithmarschen, Schleswig-Holstein; kein Fachbegriff und keine deutschlandweite Datensammlung. Für den lokalen Vorlauf wurde der nicht mitgelieferte Personen-Ausgangsbestand synthetisch rekonstruiert. Der Pool ist daher eine Prototypgrundlage, keine regional repräsentative Haushaltserhebung. Siehe Projektbefund in [Fragen und Zielbild](11-modellierungsfragen-und-zielbild.md) und [Gemeindeinformation](https://www.amt-marne-nordsee.de/unsere-gemeinden/stadt-marne/gemeindeinformationen).

Folienformulierung: **„Regionale Zensus-Strukturen + synthetische Haushaltsmuster“**. Die Herkunft und Grenzen des heutigen Pools gehören in die Sprechernotizen.

## MiD und Pendlerverflechtungen

**MiD = Mobilität in Deutschland.** Die bundesweite Haushaltsbefragung untersucht das alltägliche Verkehrsverhalten. Vergleichsgrößen sind etwa Verkehrsmittelanteile, Wege je Person und Tag, Wegelängen und -dauern, Wegezwecke und der Anteil mobiler Personen. Nicht alle verfügbaren MiD-Größen werden von unserer Software bereits als Kalibrierziele genutzt. Die vorhandenen experimentellen Kalibrierungen beziehen sich insbesondere auf Modus- und Distanzverteilungen; bisherige Referenzen beruhen teils auf MiD 2017, nicht automatisch auf der neueren MiD 2023. Quelle: [MiD](https://www.mobilitaet-in-deutschland.de/).

MiD ist auch für Arbeitswege nützlich: Verkehrsmittel, Entfernungen, Homeoffice und Mobilitätsmuster. Daraus folgt aber keine vollständig beobachtete kleinräumige Start-Ziel-Matrix für jedes Quartier.

Für räumliche Wohnort-Arbeitsort-Verflechtungen sind die Pendlerrechnung der Länder bzw. passende BA-Statistiken geeignete Grundlagen. Die Pendlerrechnung umfasst auch Gruppen über die sozialversicherungspflichtig Beschäftigten hinaus. Sie beschreibt potenzielle Pendelbeziehungen, keine Zahl tatsächlich täglich angetretener Fahrten. Quelle: [amtliche Pendlerrechnung](https://www.statistikportal.de/de/veroeffentlichungen/pendlerrechnung).

Fachlicher Ansatz: **Pendlerverflechtungen bestimmen mögliche Arbeitsbeziehungen; MiD hilft beim Mobilitätsverhalten; das Aktivitäten-/Nachfragemodell erzeugt Tageswege.** Lokale Erreichbarkeiten, Arbeitsplatzgewichte und Gebietszuordnung werden zusätzlich benötigt. Das ist kein bereits fertig angebundener automatischer MiD-Pendlerimport.

## KBA, BKG und BNetzA

| Kürzel | Ausgeschrieben | Nutzung im aktuellen Code |
|---|---|---|
| KBA | Kraftfahrt-Bundesamt | Optionaler regionaler BEV-Anteil für MobileCity aus gespeichertem Referenzbestand. BEV bezeichnet batterieelektrische Pkw. |
| BKG | Bundesamt für Kartographie und Geodäsie | Gemeindegeometrien zur Zuordnung der Fahrzeugdaten zu Modellzonen. |
| BNetzA | Bundesnetzagentur | Optional öffentlich registrierte Ladepunkte für MobileCity und regionaler Stationsbestand mit Ladepunkt-/Anschlussdetails aus demselben gespeicherten Registerstand. |

Diese Anbindungen existieren bereits. Aktivierung, passende Datenabdeckung und Quellenstände sind erforderlich. Sie bedeuten weder automatisch aktuelle Live-Fahrzeugdaten noch eine vollständige Nutzung im Personen-/Logistikmodell. Der gemeinsame Pkw-Bestand wird weiterhin durch das Bevölkerungsmodell erzeugt. Quellen: [BKG-Verwaltungsgebiete](https://gdz.bkg.bund.de/index.php/default/wms-verwaltungsgebiete-1-5-000-000-stand-31-12-wms-vg5000-1231.html), [BNetzA-Ladesäulenregister](https://bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html).

Für öffentliche Lade- und Tankmöglichkeiten existiert zusätzlich ein optionaler OSM-Import
für Strom, CNG, LNG, LPG und H₂ einschließlich Flächen/Relationen. H₂-Register-API,
Straßenzufahrt und operative Lade-/Tankplanung bleiben offen:
[Bedienung, Quellen und Integrationsplan](19-fahrzeugimport-und-modellbegriffe.md#öffentliche-lade--und-tankinfrastruktur-bestandsprüfung-und-integrationsplan-2026-10-20).

## Wer wählt das Einkaufsziel?

Die bisherige Formulierung „logiTopp wählt das Einkaufsziel“ war zu grob. **mobiTopp modelliert Personenmobilität; logiTopp erweitert diese um Paketlogistik.** Unser angebundener logiTopp-Lauf enthält die Personenmobilität. Seine Konfiguration bindet ausdrücklich Parameter für `destinationChoice.shopping` ein.

1. Die Bevölkerungssynthese ordnet Wohnort und feste Arbeits-/Bildungsorte zu.
2. Die Aktivitätenplanung erzeugt den Anlass „Einkaufen“ und seine zeitliche Einordnung.
3. Die Personenmobilität im mobiTopp/logiTopp-Lauf wählt variable Zielzone und Verkehrsmittel anhand der Attraktivitäten, Erreichbarkeiten und Verhaltensparameter.
4. Die Paketlogistik erzeugt separat Bestellungen, Zustellziele und Lieferfahrten.

Es wird nicht zwingend ein konkreter realer Supermarkt gewählt. Der Einkaufsanlass ist nicht die Paketbestellung.

**Korrigierte Beschriftung für das Personenbeispiel:** „Einkaufsziel: im Personenverkehrsmodell später gewählte Zielzone.“ Es ist keine feste Eigenschaft des Haushalts.
