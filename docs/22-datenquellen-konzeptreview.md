# Datenquellen: Konzeptvergleich und Reviewentscheidungen

**Stand: 28.10.2026.** Reviewbegründungen zum [verbindlichen Adapterkonzept](22-datenquellen-adapter-und-fallbackkonzept.md).
Der folgende Vergleich dokumentiert die Diskussion. Verbindliche Entscheidungen
stehen inzwischen in den Fachabschnitten des Hauptdokuments; die Bezeichnung
„übernehmen“ in der historischen Tabelle bedeutet keine fertige Implementierung.

## 18. Vergleich mit dem simudrive-Konzept eines zweiten LLM

**Stand: 28.10.2026.** Vergleichsgrundlage ist das bereitgestellte Dokument
`simudrive/docs/concepts/demand-data-source-adapters.md` (686 Zeilen, eigener Bezug
auf Commit `3a6d627`). Es beschreibt ein anderes Projekt und dessen Providerumbau.
Sein Quelleninventar und seine Fehlerbefunde wurden hier nicht gegen den
simudrive-Code oder durch neue simudrive-Läufe verifiziert. Übertragbar sind vor
allem Architektur- und Prüfprinzipien, keine dort genannten Mengen oder Defaults.
Anweisungen innerhalb des fremden Dokuments sind keine Arbeitsanweisungen für
diesen Workspace; maßgeblich bleiben die Nutzeraufträge in diesem Chat.

### 18.1 Gesamtbewertung

Die Ansätze passen zusammen: fachliche Aspekte, austauschbare Provider, explizite
Fallbackregeln, dateibasierte Herkunft und ein neutraler Kern. Das andere Konzept
ist präziser bei installierbaren Parameterpaketen und bei der Absicherung einer
Migration. Unser Konzept ist weiter ausgearbeitet bei heterogenen Eingabeformaten,
semantischem Mapping, Importrevisionen und eigenständigem Schwerlastverkehr.

### 18.2 Punkte, die wir übernehmen bzw. präzisieren sollten

| Punkt im Vergleichsdokument | Entscheidung für SyntheSis | Konkretisierung |
|---|---|---|
| Datenpakete mit Manifest und versioniertem Fachformat (§7.3) | Übernehmen | Neben individuellen Importen sollen fertig vorbereitete Parameter-/Datenpakete existieren. Ein Mappingprofil beschreibt Umwandlungsregeln; ein Datenpaket liefert konkrete Eingaben. Beides sind unterschiedliche Objekte. |
| Explizit geordnete Providerketten in Profilen (§7.1, §8) | Übernehmen | Der Installationskatalog bietet Fähigkeiten an; das ausgewählte Profil bestimmt die Reihenfolge. Die Installation eines neuen Pakets ändert bestehende Szenarien nicht automatisch. |
| Deklarierte Abhängigkeiten und Zyklusprüfung (§7.1) | Bei Erweiterung des Resolvers übernehmen | Fachliche Abhängigkeiten gehören in Deskriptoren. Ein fehlender Input ist eine Diagnose, eine zyklische Abhängigkeit ein Konfigurationsfehler. Keine Reihenfolge nach Spring-Bean-Registrierung. |
| Ein Plan für Vorschau, Generierung und Bericht (§7.1, §8.1) | Verbindlich präzisieren | Auswahlregeln dürfen nicht im Frontend oder in einer zweiten Previewimplementierung dupliziert werden. Vorprüfung und tatsächliches Ergebnis haben verschiedene Vollständigkeit, aber dieselbe Regelbasis. |
| Übersprungene Provider mit Gründen (§7.5) | Übernehmen | Nicht nur den Gewinner speichern: Ausschluss, falsche Region, fehlende Datei, defektes Format und nicht erfüllte Anforderungen unterscheiden. |
| Herkunft außerhalb des eigentlichen Messgebiets als Analogie (§7.5) | Übernehmen, aber mehrdimensional | Übertragene Messwerte sollen sichtbar sein. Evidenz, räumliche Übertragung und die Rolle als Fallback bleiben getrennte Attribute. |
| Lizenz-/Attributionsfelder mit explizitem `unknown` (§7.3, §11) | Übernehmen | Ein Dateiupload ist keine Quellen-/Rechteauskunft. Metadaten und Laufbericht sollen bekannte Angaben erhalten und fehlende Angaben sichtbar lassen. |
| Konkrete Migrationsfälle vorab festlegen (§10) | Übernehmen | Deutsche Region, fremdes Datengebiet, fehlende Quelle, Teilabdeckung, aktivierter Fallback sowie ausgeschalteter neuer Plan. Für SyntheSis zusätzlich Tourenrevisionen und gemischte Nachfragebeiträge. |
| Keine doppelte UI-Steuerung derselben Einstellung (§8.1) | Übernehmen | Bestehende Spezialsteuerungen verlinken oder in den Arbeitsbereich migrieren. Zwei voneinander abweichende Werte dürfen nicht parallel wirksam sein. |

Für den begonnenen Code ist das ein Prüfmaßstab, keine Behauptung, alle Punkte
seien bereits fertig implementiert. Insbesondere gibt es zunächst einen begrenzten
Quellenplan für Bevölkerung, Arbeitsplatzgewichte und schwere Fahrzeug-OD; dieser
ist noch keine vollständige Migration sämtlicher Bestandsquellen auf Profile.

### 18.3 Fachliche und technische Kritik

**1. „Jede Kette endet in einem Default, der nie fehlschlägt“ ist zu stark.**
§6/§7.1 und Abnahmekriterium 5 verlangen das pauschal. Ein dokumentierter Default
kann technisch verfügbar sein, ohne fachlich genügend Information zu liefern.
Positiv ist, dass die Aspekttabelle für Bevölkerung bereits `unknown` und für
Districts `none` zulässt. Solche Rückgaben dürfen aber nicht als fachlich vollständig
versorgter Aspekt gelten. Für fehlende Güterverkehrsmengen ist das besonders kritisch.
Zusätzlich braucht es den Zustand `insufficient-input` und Qualitätsanforderungen
des jeweiligen Laufumfangs.
Der allgemeine Generator-/Resolverprozess kann außerdem trotz Default an
ungültigen Parametern oder Ressourcenfehlern scheitern. Ein mengenabhängiger Pflichtaspekt
braucht einen expliziten Mengenanker oder ein freigegebenes Modellprofil. Ein als
Annahme gekennzeichneter Standard ist für Exploration sinnvoll, aber kein automatisch
genügender Ersatz für eine belastbare Auswertung.

**2. Das Schwerlastbeispiel wird im anderen Konzept vertagt.** §7.2 behält LKW
zunächst als Fahrzeuganteil derselben Personen-OD und reserviert `FREIGHT_OD`
für später. Das kann ein verhaltensgleicher simudrive-Migrationsschritt sein;
es erfüllt aber noch kein eigenständiges Güterverkehrsmodell mit anderen
Quellen/Zielen und Transitmengen. Diesen Default übernehmen wir nicht für
SyntheSis: schwere Fahrzeug-OD und private Paketlogistik bleiben eigene Beiträge.

**3. Formatschnittstellen sind dort überwiegend vorbereitete Packformate.**
Eine neue `hourly-curves-v1`-Datei kann Javaänderungen vermeiden. Beliebige Exporte
von Nutzern oder Diensten sind damit aber noch nicht angebunden. Es fehlt die
konkrete Transformationsstrecke für Feldnamen, JSON/XML-Strukturen, Einheiten,
Koordinaten, Fehlwerte und Belege. Das ist eine Grenze des dort bewusst engeren
Migrationsumfangs, kein grundsätzlicher Fehler der Packidee. Für unsere inzwischen
erweiterte Anforderung behalten wir deshalb den aus dem Tourenimport
abgeleiteten gemeinsamen Importkern. Neue Binärformate brauchen weiterhin einen
Reader; neue Fachbedeutungen einen Fachadapter.

**4. Teilabdeckung ist noch keine gelöste Auswahlregel.** §7.4 behandelt
`PARTIAL` während der Migration wie `COVERED`. Als ausdrücklich begrenzter
Kompatibilitätsmodus ist das nachvollziehbar, als neuer allgemeiner Default
ungeeignet. Eine grenzüberschreitende Box wird nicht vollständig versorgt,
weil einige deutsche Rasterzellen vorhanden sind. Skalierung einer Bevölkerung,
Aufteilung einer OD und Ergänzung eines Registers brauchen verschiedene Regeln.
Ein Flächenanteil allein ist zudem keine Bevölkerungs- oder Mengenabdeckung.

**5. Eine Editionskennung allein friert heruntergeladene Daten nicht ein.**
§9 nennt Provider, Edition und Konfiguration im Fingerabdruck. Wenn sich die
Antwort eines Dienstes bei gleichem Namen/Edition ändert, ist das unzureichend.
Der Lauf braucht tatsächliche Inhaltshashes, auflösbare Quellenstände und bei
LLM-Verwendung die benutzten Antworten. Vor einer Beschaffung kann die UI nur
einen vorläufigen Planfingerabdruck kennen; nach dem Laden ist ein Eingabelock
mit den konkreten Daten zu unterscheiden. Damit darf ein Provider, der selbst
HTTP/LLM aufruft, nicht ohne diese Voraussetzungen als reine Funktion gelten.

**6. Die Evidenzskala vermischt mehrere Dimensionen.** `GENERIC_DEFAULT` beschreibt
eine Auswahlrolle, `MEASURED` eine Herkunft und `ANALOGUE` eine Übertragung.
Ein Default kann selbst eine übertragene Messung sein. Sinnvoll sind getrennte
Angaben für Beobachtung/Ableitung/Annahme, Herkunftsgebiet/-zeit und Rolle im
Resolver. Eine nationale Erhebung ist nicht automatisch eine lokale Messung
für jede Teilregion innerhalb Deutschlands.

**7. „Ein Provider je Aspekt“ braucht einen erklärbaren Kompositionsbaum.**
Das Dokument sieht in §7.2 durchaus Composite-Provider mit Teileherkunft vor.
Diese Idee ist richtig. Für unsere Teilabdeckungen muss jedoch auch je Gebiet,
Zweck oder Relation sichtbar bleiben, welche Teile eingesetzt wurden. Die
Alternative zu einer ungemessenen Mischquote ist nicht ausschließlich ein
einziger Gewinner: räumliches Ergänzen, eine Randbedingung und getrennte
Nachfragebeiträge sind eigene nachvollziehbare Operationen.

**8. Bytegleichheit braucht eingefrorene Eingaben und eine feste Toleranzregel.**
Die Fallmatrix in §10 ist wertvoll. Ein wiederholter Liveabruf ist aber keine
belastbare Baseline. Zeitstempel, Pfadabhängigkeiten und nicht deterministische
Werkzeugkommentare müssen vor dem Vergleich klar geregelt sein. Bei einem
mechanischen Umbau bleiben die fachlichen Ergebnisse identisch. Eine bewusst
behobene Modell-/Abdeckungsfrage erhält dagegen einen neuen Vergleich mit
erklärter Verhaltensänderung, statt alte Fehler als wissenschaftlichen Standard
zu konservieren.

**9. „Nicht anwendbare Zeilen verstecken“ ist in der UI zu pauschal.** §8.1
will sie ausblenden. Für einfache Bedienung kann ein kompakter Standard helfen;
für die Modellprüfung muss aber erkennbar bleiben, dass Durchgangsverkehr oder
Außenbeziehungen nicht enthalten sind. Besser sind ein sichtbarer Umfang und
aufklappbare ausgeschlossene Beiträge. Status wie „hier nicht erhoben“ gehört
nicht ausschließlich in einen Tooltip.

### 18.4 Priorität für die weitere Umsetzung

Unmittelbar wichtig sind vollständige Herkunfts-/Lizenzmetadaten, klare
Vorprüfungszustände, explizite Ausschlüsse und eine gemeinsame Regelbasis für
Preview und Generierung. Danach folgen geordnete Profile und lokale Datenpakete,
bevor weitere Bestandsquellen migriert werden. Ein größerer Abhängigkeitsgraph
lohnt sich, sobald mehrere austauschbare Modellbausteine ihn tatsächlich benötigen.

Wir übernehmen weder simudrive-spezifische Konstanten noch dessen Annahme,
Schwerlastverkehr aus der Personen-OD abzuleiten. Die dortigen F1–F10-Befunde
bleiben Befunde zum anderen Projekt; sie sind nützliche Prüfhinweise, aber kein
Nachweis derselben Fehler in SyntheSis.

## 20. Zweite Reviewrunde: Kritik am eigenen Konzept

**Bearbeitet am 28.10.2026.** Die nachstehenden Punkte beziehen sich auf die
bisherige Fassung mit 1.517 Zeilen. Die frühere Antwort „ergänzt“ bedeutete
überwiegend Dokumentation im Vergleichsabschnitt; das war keine durchgängige
Einarbeitung in die Fachverträge. Diese Lücke ist mit dieser Revision geschlossen,
soweit sie das Konzept betrifft. Laufzeitänderungen sind damit nicht erfolgt.

| Rückmeldung | Bewertung | Änderung / weiterhin offen |
|---|---|---|
| 1. Inventar, Zielbild, Umsetzung und Review vermischt; künftige Abnahme trotz Code | Berechtigt. Die zeitliche Einordnung war widersprüchlich. | Hauptdokument auf Entscheidungen und Gates begrenzt; Inventar, technische Details und Review ausgelagert. Implementierung in docs/06, ausgeführte Nachweise in docs/05. Alte Abschnittsanker bleiben erhalten. §13 zeigt jetzt Ist-Stand und offene Freigaben. |
| 2. Zu viele Aspekte, Kombinationen und Zustände ohne Mindestumfang | Berechtigt. Ein Zielkatalog ersetzt keine priorisierte Schnittdefinition. | §1.1 legt genau drei M0-Aspekte fest; §8.2 beschränkt die erste Komposition; §12 trennt M0/M1/M2/M3. Statuswerte sind Diagnosen, keine zusätzlichen Konfigurationsschalter. |
| 3. Lkw-Ersatz bleibt hinter §9 zurück | Im Kern berechtigt. Die Annahmenkennzeichnung war vorhanden, die Zielerfüllung wurde dennoch zu großzügig dargestellt. | §9.5 trennt technische Mengendemonstration und fachlichen Ersatz. Tabelle benennt Defizite für Menge, OD, Zeit und Klasse. Kein freigegebener regionaler Fallback; ein technischer `ready`-Status im vorhandenen Code ist kein fachlicher Qualitätsblocker. G9 bleibt offen. |
| 4. Ein Offline-Bytevergleich reicht nicht für die übernommene Fallmenge | Berechtigt. Zusätzlich vergleichen die beiden Varianten denselben neuen Code; keine isolierte Vor-Umbau-Baseline. | §13.1 legt zwölf Gates mit Nachweisstand fest; §13.2 verlangt Baseline, Eingabehashes und feste Vergleichsregeln. Die Fallmenge ist jetzt festgelegt und nicht rückwirkend als vorab registriert behauptet. Durchführung bleibt offen/teilweise. |
| 5. `mixed` verliert Teileherkunft | Berechtigt als Vertragslücke. Das Konzept verlangte erklärbare Zusammensetzung, definierte sie aber nicht verpflichtend. | §8.5 entfernt `mixed` als Evidenzart. Nur Anzeige „mehrere Beiträge“ mit auflösbaren Quellen-/Modellknoten, Geltungsbereich, Operation und getrennten Übertragungs-/Auswahlrollen. Vollständiger Codevertrag bleibt offen. |
| 6. Bbox reicht nicht für kommunale Register | Berechtigt für Gemeindeaggregate; Punktregister sind ein anderer Vertrag. | §8.3 trennt Punkte, Gemeindeaggregate, Raster und OD. Schlüsselraum/Gebietsstand, Geometrie-/Crosswalkversion und Disaggregation sind explizit. Punktadapter und dessen implizite Nullsetzung in Boxzonen werden als eingeschränkter Ist-Stand benannt. Vollständigkeitsprüfung und AGS-Verarbeitung sind offen. |

### 20.1 Wo die früheren Empfehlungen jetzt verbindlich stehen

| Empfehlung | Vertragsstelle im Hauptdokument | Implementierungsstand |
|---|---|---|
| Geordnete Quellenprofile; Installation verändert keine Priorität | §7.2 | M1, offen |
| Datenpakete von Mappingprofilen und Revisionen unterscheiden | §7.2 und §16 | Einzelrevisionen/Mappingprofile vorhanden; Paketmanifest offen |
| Deklarierte Abhängigkeiten und Zyklusfehler | §7.3 und §11.3 | Zielanforderung; allgemeiner Graph noch nicht nötig/implementiert |
| Gleicher Resolver für Vorschau und Lauf | §11.1 und §17 | Gemeinsame Vorbereitungsregel vorhanden; spätere Anwendungsbilanz separat |
| Übersprungene Kandidaten mit Gründen | §10.2 | Für ausgewählte Revisionen begrenzt vorhanden; vollständige alte Ketten offen |
| Evidenz, räumliche Übertragung und Auswahlrolle getrennt | §8.5 | Erste Metadaten vorhanden; vollständiger Teilevertrag offen |
| Lizenz und Attribution, fehlend als `unknown` | §7.2 | Importmetadaten und Berichtsangaben vorhanden; keine automatische Rechteprüfung |
| Feste Migrationsfälle und eingefrorene Eingaben | §13 | Teilprüfungen vorhanden; vollständige Fallmenge nicht ausgeführt |
| Eine maßgebliche Einstellung, ausgeschlossene Beiträge sichtbar | §17 | Zielanforderung; alte Bedienelemente nicht vollständig migriert |

Der Vergleich stellt keine Gewinnerwertung zwischen Projekten dar. Beide
Konzepte haben nützliche Bausteine und offene Nachweise. Die Kritik am fremden
Default oder an dessen Teileherkunft entbindet uns nicht von denselben Anforderungen.

### 20.2 Umsetzungsnachtrag nach diesem Review

Die anschließende M0-Änderung implementiert den Vollständigkeitsvertrag für
Arbeitsplatzpunktdaten und zonenbezogene Teileherkunft dieses Aspekts. Die Angaben
„offen“ in der vorstehenden Reviewtabelle beschreiben den damaligen Stand;
[aktuelle Nachweise und Grenzen](05-validierung.md#datenquellen-m0-vollständigkeit-und-teileherkunft-2026-10-28)
benennen den erreichten Teilumfang. AGS-/Gebietsstände und vollständige alte
Providerketten bleiben offen; das Konzept ist weiterhin nicht vollständig umgesetzt.

### 20.3 Lokaler M1-Schnitt

Geordnete Quellenprofile, lokale Datenpakete, Kandidatendiagnosen und eine
technische Auswahlprobe außerhalb Deutschlands sind anschließend umgesetzt und
geprüft. Die ursprünglichen „offen“-Angaben in §20.1 bleiben Reviewhistorie;
[aktuelle Prüfung und Grenzen](05-validierung.md#datenquellen-m1-pakete-profile-und-gebietswechsel-2026-10-28)
beschreiben den erreichten Umfang. Globale Kataloge, Bestandsmigration und
abhängige Modellprovider sind damit nicht automatisch implementiert.

### 20.4 Gemeindezuordnung und Explorationsgrenze der Demonstration

Der nächste Schnitt liefert einen begrenzten AGS-/Gebietsstandsjoin mit separater
Gewichtsrevision und eingefrorenen Referenzen. Das Originalaggregat bleibt erhalten;
Punktorte werden ausdrücklich als Modellableitung geführt. Außerdem ist die
Lkw-Demonstration jetzt nur nach expliziter Explorationsfreigabe zulässig und wird
für fachliche Auswertungen blockiert. [Aktuelle Nachweise und Grenzen](05-validierung.md#gemeindeaggregate-und-qualitätsmodus-2026-10-29).
Amtliche Grenzgeometrien/Schlüsselüberleitungen und der eigentliche Fachfallback
bleiben offen.

### 20.5 Flächenzuordnung ergänzt

Versionierte GeoJSON-Polygone, Löcher und disjunkte Teile werden nun erhalten und
in EPSG:3035 mit tatsächlichen Zonenflächen verschnitten. Teilzonenmengen bleiben
getrennt von angewendeten und außerhalb liegenden Mengen. Eine feste numerische
Abdeckungstoleranz erzeugt keine zusätzliche Menge. Die uniforme Dichte bleibt
Annahme; amtliche Datenverifikation und Schlüsselüberleitungen sind weiterhin offen.
[Finale Nachweise und Grenzen](05-validierung.md#gemeindegeometrien-und-flächenverteilung-2026-10-29).

### 20.6 Native Providerketten begonnen

Bevölkerung und Work verwenden nun denselben generischen Auswahlkern mit expliziten
Reihenfolgen, Abwahlen und Fehler-/Ausfallregeln. Fünf native Bevölkerungszweige haben
byteweise Vergleichsfälle; Gesamtzahl und Verteilung erhalten getrennte Herkunft.
Work-Modellgewichte werden ausdrücklich von gezählten Arbeitsplätzen unterschieden.
Die vollständige Bestandsmigration, gemeinsame native/importierte Ketten und
empirische Freigaben bleiben offen. [Umsetzung und Grenzen](06-gta-integration-konzept.md).

### 20.7 Gemeinsame regionale Komposition ergänzt

Gemischte native/Revisions-/Paketprofile und explizite native Registerlücken sind
inzwischen implementiert. Gebundene potenzielle Eingaben werden von tatsächlicher
Auswahl getrennt; Mengen und Modellgewichte behalten disjunkte Herkunft und Nenner.
Ein Lückenfehler kann keine teilweise übernommenen Registerwerte hinterlassen.
Die allgemeine empirische Freigabe und weitere Fachfamilien bleiben offen.
[Bedienung und präzise Grenzen](examples/mixed-sources/README.md).

### 20.8 Regionale Mehrregisterkomposition ergänzt

Explizite regionale Registerprioritäten erhalten nun echte Nullwerte, verdeckte
Überlappungsmengen und disjunkte tatsächliche Scopes. Native Modelle ergänzen nur
Restlücken; die gesamte Anwendung bleibt atomar. Eine vor der Erweiterung erfasste
Profil-Fixture behält ihren alten Hash. NoData-Masken, amtliche Gebietsüberleitungen
und empirische Freigaben bleiben offen. [Vertrag und Beispiel](examples/regional-registers/README.md).
