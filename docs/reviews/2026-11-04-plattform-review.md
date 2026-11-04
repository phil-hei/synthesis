# Review der Plattform nach dem Umbau zur Server-Anwendung

Prüfstand: 4. November 2026, Branch `claude/zen-archimedes-gap5me` (Commit `f2249b3`). Geprüft wurden
Backend (`backend/`), Frontend (`frontend/`), Skripte (`scripts/`), CI, Container-Dateien und die
Dokumentation. Methode: Lesen des Codes, Messwerte (Zeilen, Muster, Abhängigkeiten), die Testsuiten,
die Jar-Abnahme per `curl` und ein Playwright-Durchlauf. Grenze dieser Prüfung: Sie lief in einer
Cloud-Sandbox ohne Docker-Daemon, SUMO, KIT-Adapter, Ollama und .NET; die davon abhängigen
Pfade sind nur statisch geprüft. Die lokale Sitzung schließt diese Lücke (Arbeitsauftrag unten).

## Gesamtbild

Die Plattform funktioniert als ein Prozess: Login, Benutzerverwaltung, geschützte API, ausgelieferter
Angular-Build, persistenter Jobstatus, Container-Image und CI sind vorhanden und getestet
(Backend 495 Tests, Frontend 135 Tests, Lint 0 Befunde, Jar-Abnahme grün). Die fachliche Pipeline
mit ihren 15 Stufen und allen experimentellen Zweigen ist unverändert erhalten. Offen bleiben
vor allem drei Dinge: die Laufzeitabnahme mit den echten Werkzeugen, zwei Härtungslücken der
neuen Anmeldung, und die Größe der beiden Zentralklassen (`GenerationService`, `StudioComponent`),
die jede weitere Änderung teuer macht. Dazu kommen veraltete Laufzeitversionen (Spring Boot 3.5.5,
Node 20).

## Messwerte (Stand 2026-11-04)

| Kennzahl | Wert |
|---|---|
| Backend-Code | 28 181 Zeilen Java; größte Klassen `GenerationService` 2 347, `SumoRunner` 1 068, `SimulationResultIngestor` 762 |
| Frontend-Code | 7 188 Zeilen; `studio.component.ts` 1 724, `studio.component.html` 1 376 |
| Breite `catch (Exception` im Backend | 43 Stellen (1 davon leer) |
| `TODO`/`FIXME`, `System.out` | 0 / 0 |
| `$any()` in Vorlagen | 10 (alle im Studio) |
| `title`-Tooltips vs. `aria-*` im Studio | 114 vs. 21 |
| Tests | Backend 495 (44 übersprungen ohne SUMO/KIT/venv/Ollama), Frontend 135, Python 96 (4 Skips) |
| Python-Skripte | 29, davon 17 mit eigener Testdatei |
| Artefaktgröße je Lauf | ca. 28 MB bei 300 Personen und Fallback-Modus |
| Abhängigkeiten | Spring Boot 3.5.5 (3.5-Linie bei 3.5.16, aktuelle Linie 4.1.2); Angular 21.2.26 (22.2 verfügbar); Node 20 in Dockerfile/CI (Ende der Pflege April 2026); TypeScript 5.9, vitest 4, jsdom 24 |

## Befunde mit hoher Priorität

### H1. Sitzungen überleben Kontoänderungen — ✅ behoben (2026-11-04, siehe docs/06)

**Befund:** `UserDetailsService` wird nur beim Login befragt. Wird ein Konto gelöscht, herabgestuft oder
sein Passwort zurückgesetzt, bleibt eine bestehende Sitzung bis zum Timeout (12 h) voll gültig.
**Evidenz:** `SecurityConfig` ohne `SessionRegistry`; `UserController` ändert nur `users.json`.
**Empfehlung:** Sitzungen des betroffenen Kontos beim Löschen, Rollenwechsel und Passwort-Reset
sofort beenden; zusätzlich pro Anfrage prüfen, ob das Konto noch existiert. Aufwand: klein.

### H2. Kein Schutz gegen Passwort-Raten — ✅ behoben (2026-11-04, siehe docs/06)

**Befund:** `/api/auth/login` hat keine Begrenzung; bcrypt bremst, verhindert aber keine
Wörterbuchangriffe gegen `admin`. **Empfehlung:** Zähler je Benutzername und Herkunft im Speicher,
nach wiederholten Fehlversuchen zeitweise 429 mit Hinweis; Fehlversuche protokollieren.
Aufwand: klein.

### H3. Laufzeitpfade sind nicht abgenommen

**Befund:** 44 Tests überspringen sich ohne SUMO, actiTopp/logiTopp-Adapter, Transit-venv und Ollama;
der Docker-Build lief nicht. **Empfehlung:** lokal `scripts/verify.sh full` und
`docker compose up -d --build` ausführen, Ergebnis datiert in docs/06 festhalten, rote Punkte beheben.
Aufwand: Laufzeit, keine Entwicklung, solange nichts rot wird.

### H4. Spring Boot 3.5.5 liegt elf Patch-Stände zurück, die 4er-Linie ist aktuell

**Befund:** `pom.xml` pinnt 3.5.5; Maven Central führt 3.5.16 sowie 4.1.2. **Empfehlung:** zuerst
auf 3.5.16 (gleiche Linie, nur Fehler- und Sicherheitskorrekturen, Gates laufen lassen), dann die
Migration auf 4.x planen (Spring Framework 7, Spring Security 7, Jackson 3; betrifft
`JsonMapper`-Aufrufe, Security-DSL, springdoc-Version). Aufwand: Patch klein, Major mittel.

### H5. Node 20 im Dockerfile, in der CI und in `runtime/versions.env`

**Befund:** Node 20 ist seit April 2026 ohne Pflege; `package.json` erlaubt bereits 22 und 24, alle
Gates dieser Prüfung liefen mit Node 22. **Empfehlung:** `node:22`-Images in Dockerfile und CI,
`SYNTHESIS_NODE_VERSION` auf eine 22er-LTS-Version, Preflight-Erwartung anpassen. Aufwand: klein.

## Befunde mit mittlerer Priorität

### M1. `GenerationService` bündelt Orchestrierung, Stufenlogik und Manifest

2 347 Zeilen, 15 Stufen, Manifest-Schreiber und Provenienz in einer Klasse; zwei
Provenienz-Fehler desselben Musters (Quelle vor dem Abruf gesetzt) wurden heute gefunden.
**Empfehlung:** Stufen als eigene Klassen mit gemeinsamer Schnittstelle (`run(ctx)`), `ManifestWriter`
und `RunSummary` herauslösen, Provenienzfelder erst nach erfolgreichem Abruf setzen (Regel im
Architektur-Vertrag festhalten). Der Executor-Pool von 1 und die Stufenreihenfolge bleiben Invarianten.
Aufwand: groß; nur mit kompletter Suite als Beweis der Verhaltensgleichheit.

### M2. Studio-Komponente ist zu groß für sichere Änderungen

1 724 + 1 376 Zeilen, zehn `$any()`-Zugriffe auf `job.summary`, 114 `title`-Tooltips als einzige
Hilfe, wenig `aria-*`. **Empfehlung:** in Teilkomponenten schneiden (Szenarioverwaltung,
Bevölkerung, Modellierung, Pipeline/Ergebnis, Karte), `summary` typisieren, Tooltips durch
beschriftete Hilfetexte ergänzen. Aufwand: groß; Specs schrittweise mitziehen.

### M3. Ein Worker für alle Nutzer ohne sichtbare Warteschlange

Der zweite Lauf einer anderen Person wartet als `QUEUED`, sieht aber weder Position noch den
laufenden Lauf; ein Abbruch existiert nicht (docs/03 nennt ihn). **Empfehlung:** Warteposition und
Name des laufenden Szenarios im Status liefern und anzeigen; Abbruch als eigene Stufe planen
(kooperatives Abbrechen zwischen Stufen). Aufwand: Anzeige klein, Abbruch mittel.

### M4. Keine Betriebssicht: Metriken, Festplattenwachstum, Sicherung

`/api/health` ist minimal, es gibt keine Metriken (kein Actuator), keine Aufräumfunktion und
keine Sicherungsanleitung; ein Lauf belegt rund 28 MB, Uploads bis 200 MB. **Empfehlung:**
Sicherung des Volumes `/app/data` (`users.json`, `scenarios/`) dokumentieren, Aufräumen alter
Artefakte je Szenario anbieten, Actuator `health`/`info`/`metrics` hinter der Anmeldung. Aufwand: klein bis mittel.

### M5. Frontend-Abhängigkeiten eine Hauptversion zurück

Angular 22.2, vitest 5, jsdom 30, TypeScript 7 sind verfügbar; die 21er-Linie wird noch gepflegt.
**Empfehlung:** auf der 21er-Linie bleiben, bis Spring Boot 4 ansteht, dann beides in einem
Wartungsfenster; vorher `jsdom` und `vitest` innerhalb der Nebenversionen heben. Aufwand: mittel.

### M6. Breite Ausnahmebehandlung in Fallback-Pfaden

43 `catch (Exception`-Stellen, meist bewusst für Fallbacks (Overpass, Routing, externe Tools).
**Empfehlung:** jede Stelle muss Ursache loggen und, wo ein Fallback greift, die Stufe markieren
(`job.fallback`); eine Stelle fängt still. Aufwand: mittel, mechanisch.

### M7. Python-Skripte ohne Paketstruktur

29 Skripte mit Bindestrich-Namen, 17 mit Tests; Importe zwischen Skripten brauchen Umwege
(`freight_restrictions`). **Empfehlung:** Paket `scripts/synthesis_tools/` mit Modulnamen,
Einstiegsskripte dünn, Tests im Paket; CI-Job bleibt. Aufwand: mittel.

### M8. Dokumentation: vier Dokumente mit Nummer 22, Chronik ohne Inhaltsverzeichnis

`docs/22-*.md` existiert viermal; docs/06 hat 3 896 Zeilen ohne Index. **Empfehlung:**
Datenquellen-Dokumente in `docs/datenquellen/` bündeln oder 22a–22d nummerieren; docs/06 ein
Inhaltsverzeichnis der Abschnitte A–K voranstellen. Aufwand: klein.

### M9. CI-Jobs mit externen Voraussetzungen sind ungeprüft

`docker-image` braucht die GitLab Container Registry, `full-runtime-tests` einen Runner mit Tag
`synthesis-runtime`. **Empfehlung:** beides einmal auf GitLab auslösen und die Pipeline-Ausgabe in
docs/08 festhalten. Aufwand: Betrieb.

## Befunde mit niedriger Priorität

- **N1. Startpasswort im Log:** erscheint einmalig als WARN; in Container-Logs bleibt es stehen.
  Nach dem ersten Login Passwort ändern und `initial-admin-password.txt` löschen; optional nur die
  Datei schreiben und im Log auf sie verweisen.
- **N2. Produktionsprofil fehlt:** `application.yml` erlaubt `http://localhost:4300` mit
  Credentials; `docker-compose.yml` überschreibt das. Ein `application-prod.yml` mit leeren Origins
  und `cookie.secure=true` wäre expliziter.
- **N3. Reverse-Proxy-Beispiel:** docs/08 nennt die Header-Strategie, aber keine nginx-/Caddy-Vorlage.
- **N4. `job.json` erscheint in der Artefaktliste** (83 Einträge); harmlos, könnte in der
  Studio-Liste ausgeblendet werden.
- **N5. Swagger-UI hinter der Anmeldung:** ohne Sitzung 401 statt Umleitung; erst in der App
  anmelden, dann `/swagger-ui.html` öffnen. In docs/08 vermerken.
- **N6. Kartenkacheln vom öffentlichen OSM-Tile-Server:** für eine Mehrbenutzer-Plattform die
  Nutzungsbedingungen prüfen, eigenen Kachel-Anbieter oder Schlüssel konfigurierbar machen.
- **N7. Sitzungen im Speicher:** ein Backend-Neustart meldet alle ab; für einen Server-Prozess
  akzeptabel, bei mehreren Instanzen Spring Session nötig.
- **N8. Dateirechte `rw-------`** für `users.json` und das Startpasswort gelten nur auf POSIX-Dateisystemen.

## Bewusst nicht geändert

Kein Spring Modulith, keine Datenbank, kein OIDC/SSO, keine Komplettformatierung des Codes, keine
Mindestabdeckung. Alle experimentellen Flags bleiben mit Default `false` erhalten.

## Arbeitsauftrag für die lokale Sitzung (in dieser Reihenfolge)

1. Branch holen, Maven-Plugins und npm-Pakete einmal online nachladen (`mvn -B dependency:resolve-plugins dependency:go-offline`, `npm ci`).
2. **H3:** `scripts/verify.sh full` und `docker compose up -d --build`; Ergebnis datiert in docs/06 Abschnitt K; rote Punkte beheben.
3. **H4/H5:** Spring Boot 3.5.16, Node 22 in Dockerfile, CI und `runtime/versions.env`; beide Gates.
4. **M3, M4, N1–N5:** kleine Betriebs- und Doku-Punkte in je einem Commit.
5. **M8, M7:** Doku-Struktur und Skriptpaket.
6. **M1, M2:** Refactorings einzeln, jeweils mit kompletter Suite als Nachweis.
7. Später: Spring Boot 4 und Angular 22 in einem Wartungsfenster (M5).

Jeder Schritt folgt `synthesis-change-control`: Gates grün, datierter Eintrag in docs/06, deutsche
Commit-Nachricht mit „Verifiziert“.
