# Mehrere Arbeitsplatzregister regional ergänzen

Stand: 29.10.2026. Experimenteller technischer Schnitt; keine empirische Modellfreigabe.

Im Profileditor des Beitrags „Arbeitsplätze“ bei „Zusammenführung“ die Option
„Register regional ergänzen“ wählen. Importierte Revisionen und Paketquellen in der
gewünschten Priorität hinzufügen. Native Modelle gehören in die getrennte Liste für
Registerlücken. Nach Speichern die konkrete Profilversion auswählen, verbleibende
Lücken behandeln und den Plan prüfen/speichern. Ohne gesetzte Strategie bleibt die
bisherige Auswahl der ersten verwendbaren Quelle aktiv.

Ein regionales Profil verwendet `strategy: fill-gaps`, `fallback: none` und ausschließlich
importierte Arbeitsplatzregister. Pro Modellzone gewinnt das erste Register mit
vollständiger Abdeckung. Weitere Register ergänzen nur die noch offenen Zonen.
Ein belegter Wert null belegt die Zone und wird von nachfolgenden Quellen nicht ersetzt.
Teilzonen werden nicht durch Addieren mehrerer ungeklärter Teilmengen vollständig.
Dieselben Regeln gelten für Punkte, Gemeindegewichte und Polygonverteilung.

Nur wenn danach Zonen offen bleiben, wird die ausdrücklich konfigurierte native
Lückenliste aufgelöst. Ohne erlaubten passenden Ersatz schlägt die gesamte Komposition
fehl, bevor tatsächliche Work-Werte verändert werden. Andere Zielzwecke bleiben erhalten.
Ein Lückenmodell wird bei vollständiger Registerabdeckung nicht aufgerufen.

Alle potenziell benötigten Registerrevisionen und Referenzen werden bereits vor
Queueing geprüft und gebunden. Deshalb kann eine defekte nachrangige Quelldatei
schon die Vorprüfung blockieren, auch wenn eine vorrangige Quelle im Lauf das ganze
Gebiet abdecken würde. Abgewählte Kandidaten werden nicht eingelesen. Im eigentlichen
Lauf werden nach vollständiger Abdeckung keine weiteren Register zugeordnet.

## Ausführbares Beispiel

[regional-inputs.zip](regional-inputs.zip) und die [Profildefinition](source-profile.json)
gehören zusammen. Das Profil enthält den konkreten ZIP-Hash. Im selben Szenario zuerst
installieren, danach die Definition über `POST /api/scenarios/{id}/data/source-profiles`
speichern und den zurückgegebenen Profilhash unter `opportunities.work` auswählen.
[Entpacktes Manifest](regional-inputs/manifest.json).

Für die künstliche Testbox 8,20/48,85–8,26/48,89 mit 1-km-Zonierung gilt:

- Register A liefert 12 und einen bestätigten Nullwert in seiner Teilabdeckung.
- Register B liefert 99 im Überlappungsbereich und 7 im zusätzlichen Bereich.
- Bei A vor B bleiben die 12 und die Null erhalten. Von B werden 7 angewendet und
  99 durch Vorrang verdeckt. Die gemeinsame Abdeckung lässt keine Modelllücke.

Das sind künstliche Vertragszahlen, keine realen regionalen Arbeitsplatzdaten.
Die Wirkung hängt von Szenarioregion, Zonierung und deklarierter Abdeckung ab.
Eine andere Zonierung kann andere vollständige/partielle Zonen erzeugen.

## Bericht und Mengenbilanz

`sources/resolution.json.workplaces` verwendet den Vertrag
`synthesis-regional-workplace-resolution-v1`. `parts` enthält die geprüften Register
mit ihren eigenen Mengen und tatsächlichen Zonen:

| Feld | Bedeutung |
|---|---|
| `inputJobs` | Originalmenge dieser Revision |
| `appliedJobs` | Tatsächlich aus diesem Register verwendete Menge |
| `shadowedJobs` | Vollständig zuordenbare Menge in bereits von früheren Registern belegten Zonen |
| `outsideJobs` | Menge außerhalb der Modellzonen |
| `unappliedPartialJobs` | Menge in nicht vollständig übernommenen Teilzonen |
| `appliedZones`, `shadowedZones` | Externe Zonen-ID und nullbasierter Modellindex getrennt |

Die Mengenbilanz gilt pro Register, bei Flächenzuordnung mit dokumentierter
numerischer Toleranz. Es gibt keine behauptete gemeinsame Arbeitsplatzgesamtzahl:
Definitionen, Bezugsstände und Evidenz verschiedener Register können abweichen.
Modellgewichte des Lückenfüllers bleiben ebenfalls separat.

`allocationBeforePriority` enthält die ursprüngliche Zuordnungsdiagnose vor der
Vorrangmaske. Ihre Zeilenstatus und Mengen sind ausdrücklich nicht die endgültige
Anwendung. Die tatsächlichen Felder stehen eine Ebene darüber. Im Herkunftsgraph
enthalten `register-<Rang>/…` ausschließlich tatsächlich verwendete Zonen; verdeckte
Werte bleiben über Mengenbilanz und Diagnose nachvollziehbar. Ein vollständig
verdecktes Register kann im Bericht stehen, ohne zum tatsächlichen Graphen beizutragen.

Nicht umgesetzt sind dadurch allgemeine NoData-/Geheimhaltungsmasken, amtliche
Schlüsselüberleitungen oder empirische Qualitätsfreigaben. Die regionalen Register
müssen weiterhin einen unterstützten Arbeitsplatzvertrag erfüllen.

Ergänzung 29.10.2026: Für ausdrücklich zugeordnete Zellzustände steht inzwischen
der separate [typisierte Rastervertrag](../masked-cells/README.md) zur Verfügung.
Dies erweitert die alten Punkt-/nativen Rasterverträge nicht stillschweigend.
