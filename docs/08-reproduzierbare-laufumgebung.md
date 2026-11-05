# Reproduzierbare Laufumgebung

Stand: 2026-11-04

## Ziel

Ein Checkout soll nicht mehr von undokumentierten Inhalten unter `validation/`
abhängen. Versionen, Upstream-Commits, SyntheSis-Patches und Python-Pakete sind im
Repository festgeschrieben. Externe GPL-/MIT-Projekte bleiben aus Lizenz- und
Speichergründen eigene Prozesse.

## Verträge

| Bereich | Versionierte Quelle |
|---|---|
| Java/Node/Python/SUMO/Ollama | `runtime/versions.env` |
| KIT-Clones und Commits | `runtime/kit-sources.env` |
| Transit-Python-Pakete | `runtime/transit-requirements.txt` |
| logiTopp-/Retouren-Patch | `legacy/logitopp-returns-v1/` (Patches + `harness-artifacts/`) |
| actiTopp-CLI-Adapter | `legacy/actitopp-synthesis-v1/` |

## Kommandos

```sh
# Schnelle Prüfung für Backend + Frontend, inkl. Abgleich der Skill-Registries
scripts/preflight.sh core

# Gepinnte KIT-Laufprojekte und Transit-venv rekonstruieren
scripts/bootstrap-runtime.sh all

# Alle nativen Abhängigkeiten und Fixtures prüfen
scripts/preflight.sh full

# Backend, Frontend und bei Bedarf Ollama gemeinsam starten
scripts/dev.sh

# Standard- beziehungsweise vollständige Abnahme
scripts/verify.sh core
scripts/verify.sh full
```

`scripts/preflight.sh core` prüft seit dem 2026-08-20 zusätzlich, ob `.agents/skills/`
und `.claude/skills/` byte-identisch sind. Die Skills liegen doppelt vor: `.agents/`
wird gepflegt, `.claude/` wird geladen. Läuft das auseinander, arbeiten Agenten still
nach veralteten Anweisungen — der Drift ist sonst unsichtbar, weil beide Bäume
git-getrackt sind und nichts sie vergleicht. Die Prüfung ist bewusst ein Fehler und
kein Hinweis: Der Abgleich kostet einen `cp`, ein übersehener Hinweis kostet Tage.
Vor dem Kopieren die Richtung prüfen — welcher Baum der neuere ist, entscheidet der
Inhalt, nicht der Zeitstempel.

`scripts/dev.sh` prüft Backend und Frontend per HTTP und meldet die Anwendung erst nach beiden
erfolgreichen Healthchecks als bereit. Bereits laufende SyntheSis-Prozesse werden wiederverwendet;
eine fremde Belegung von Port 8090 oder 4300 bricht mit einer konkreten Meldung ab. Neu gestartete
Kindprozesse werden beim Abbruch beendet, bereits vorhandene Prozesse bleiben unberührt.
Laufzeitlogs liegen gitignoriert unter `.runtime/logs/`. Ein bereits extern laufender
Ollama-Server wird weiterverwendet.

## CI

Die bisherigen portablen Backend-/Frontend-Jobs bleiben unverändert. Zusätzlich
existiert der manuelle beziehungsweise per `RUN_FULL_INTEGRATION=true` aktivierbare
Job `full-runtime-tests`. Er verlangt einen Shell-Runner mit Tag
`synthesis-runtime`, auf dem `scripts/preflight.sh full` erfolgreich ist.

## Aktuelle KIT-Anbindung ohne Quellcodepatches (02.10.2026)

`bootstrap-runtime.sh kit` baut `adapters/actitopp` und `adapters/logitopp` über
`build-actitopp-adapter.sh` beziehungsweise `build-logitopp-adapter.sh`.
Die Builds lesen Originalcommits mit `git archive` und laufen unter Java 25.
Das Backend startet vorgebaute Launcher. Marker sind
`synthesis-actitopp-households-v2` und `synthesis-logitopp-adapter-v2`.
Der logiTopp-Build verwendet weiterhin die geprüften generierten
Wahlmodellbindungen aus `legacy/logitopp-returns-v1/harness-artifacts`; die
Patches und `apply.sh` werden nicht ausgeführt. Lokale Altänderungen in KIT-
Checkouts werden ignoriert. Details und Abnahme: [Adapterkonzept](17-kit-adapter-und-haushaltskonzept.md).

## Historischer KIT-Patchvertrag (seit 2026-08-13; nicht mehr im Bootstrap)

`legacy/logitopp-returns-v1/apply.sh` setzt auf dem **Upstream-Stand der gepinnten
Commits** auf, nicht auf einem lokal gewachsenen Zwischenstand. Der Vertrag besteht aus
drei Teilen:

| Teil | Inhalt |
|---|---|
| `mobitopp-rastatt.patch`, `logitopp.patch` | Upstream → Vertragszustand, **strikt** (ohne `--ignore-space-change`), inkl. Binär-Diff des Gradle-Wrapper-Jars |
| `harness-artifacts/` | 58 Dateien, die im Harness gitignoriert, zum Kompilieren aber zwingend sind: 42 DCML-generierte Klassen, 6 `.xmi`-Modelle, 8 `.gen`, 2 Config-Varianten — mit `manifest.sha256` |
| Hash-Guards in `apply.sh` | Je Repo Upstream- **oder** bereits installierter Vertragszustand; danach Verifikation von Nach-Zustand und Artefakt-Manifest |

Zwei Punkte, die dabei nicht offensichtlich sind: Der Vertrag enthält ein
**Gradle-Wrapper-Upgrade 6.3 → 9.5.0** — Upstream-Gradle 6.3 kann `SYNTHESIS_KIT_JAVA=25`
nicht bauen. Und der Vertrags-Build entfernt das Codegen-Plugin, weshalb die aus den
`.xmi` erzeugten Klassen als Artefakte mitgeliefert werden müssen statt generiert zu
werden. Beides war vor 2026-08-13 unversioniert und machte den Bootstrap nur scheinbar
reproduzierbar (Befund: `docs/05-validierung.md`, Abschnitt „Bootstrap-Vertrag
rekonstruiert").

**Nicht aufweichen:** Patches für diesen Vertrag niemals mit `--ignore-space-change`
erzeugen oder anwenden — solche Patches sind nicht rückrechenbar, und genau daran ist
die Vorversion gescheitert.

## Plattform-Betrieb im Container (2026-11-04)

Für den Server-Betrieb mit Anmeldung liefert das Backend den Angular-Build selbst aus
(`FrontendConfig`: `synthesis.frontend.dir` oder gebündelt unter `classpath:/static/`;
Deep-Links wie `/vergleich` bekommen `index.html`, `/api/**` nie). Damit besteht die
Plattform aus einem Prozess, einem Port und einer Herkunft; CORS ist nur für den
Dev-Server (`synthesis.auth.allowed-origins`) nötig.

```sh
# Image bauen und starten (Frontend + Backend, Daten im Volume synthesis-data)
docker compose up -d --build

# Startpasswort des Administrators lesen, dann http://localhost:8090 öffnen
docker compose exec synthesis cat /app/data/initial-admin-password.txt

# Lebenszeichen (anmeldefrei, auch für Reverse-Proxy und Container-Healthcheck)
curl -fsS http://localhost:8090/api/health
```

| Baustein | Inhalt |
|---|---|
| `Dockerfile` | Drei Stufen: `node:20` (`npm ci`, `ng build`) → `maven:3.9.11-eclipse-temurin-21` (Build kopiert nach `src/main/resources/static`, `mvn package -DskipTests`) → `eclipse-temurin:21-jre-noble` (unprivilegierter Nutzer, `/app/data` als Volume, `scripts/` unter `/scripts` für `../scripts/*.py`, Healthcheck auf `/api/health`). Die Image-Tags (`node:20.20.2-bookworm-slim`, `maven:3.9.11-eclipse-temurin-21`, `eclipse-temurin:21-jre-noble`) sind auf Docker Hub geprüft (2026-11-04). `--build-arg WITH_SUMO=true` installiert SUMO aus dem Ubuntu-PPA `sumo/stable`; `SUMO_HOME=/usr/share/sumo` ist vorbelegt, ohne Installation bleibt die SUMO-Stufe `SKIPPED`. |
| `docker-compose.yml` | Ein Dienst, Port 8090, Volume `synthesis-data` (Szenarien, `users.json`, Startpasswort), Umgebungsvariablen nach Spring-Konvention (`SYNTHESIS_DATA_DIR`, `SYNTHESIS_AUTH_ENABLED`, `SYNTHESIS_OVERPASS_LIVE_DATA_ENABLED`, …). |
| `.gitlab-ci.yml` | Neuer Job `scripts-tests` (Python-`unittest` der Hilfsskripte) und Stage `build` mit `docker-image` (Docker-in-Docker, Push in die GitLab Container Registry als `:<kurzer SHA>`, auf dem Default-Branch zusätzlich `:latest`, bei Tags `:<tag>`; auf anderen Branches manuell). |

**Nicht im Image:** KIT-Adapter (actiTopp/logiTopp, Java 25), Ollama, .NET-ConfigTool, GTFS-venv.
Die betroffenen Stufen enden als `SKIPPED` beziehungsweise mit dokumentiertem Fallback; für den
vollen Umfang bleibt der Self-hosted-Runner beziehungsweise ein Host mit `scripts/bootstrap-runtime.sh`
der Vertrag. Die KIT-JDK-Suche (`ExternalJavaToolchain`, `kit_java_home` in `runtime-common.sh`)
kennt seit 2026-11-04 neben Homebrew auch die üblichen Linux-Pfade (`/usr/lib/jvm/java-25-openjdk-*`,
`temurin-25-jdk-*`), gesteuert bleibt sie über `SYNTHESIS_KIT_JAVA_HOME`.

**Ehrlicher Befund (2026-11-04):** Der Docker-Build wurde in der Entwicklungsumgebung dieses Standes
nicht ausgeführt (kein Docker-Daemon verfügbar); Dockerfile und Compose folgen den oben genannten
offiziellen Images, der SPA-Auslieferungspfad ist durch `SpaFallbackTest` abgedeckt. Der erste
`docker compose up --build` auf einem Zielsystem ist daher die Abnahme dieses Abschnitts.

Hinter einem TLS-Reverse-Proxy (nginx, Traefik, Caddy) werden `X-Forwarded-*`-Header ausgewertet
(`server.forward-headers-strategy: framework`), damit Session-Cookie und Redirects die externe
Adresse tragen. Das Session-Cookie ist `HttpOnly` und `SameSite=Lax`; `Secure` setzt das Profil
`prod` (`application-prod.yml`, seit 2026-11-05: `server.servlet.session.cookie.secure=true`,
`synthesis.auth.allowed-origins` leer).

### API-Dokumentation (Swagger) hinter der Anmeldung (2026-11-05)

`/swagger-ui.html` und `/v3/api-docs` verlangen eine Sitzung; ohne Anmeldung antworten sie mit
`401`, nicht mit einer Umleitung zur Anmeldeseite. Vorgehen: erst in der App anmelden, dann im
selben Browser `http://<host>:8090/swagger-ui.html` öffnen (geprüft: 87 Operationen sichtbar).
„Try it out“ funktioniert nur für lesende Aufrufe: Swagger UI schickt den CSRF-Header
`X-XSRF-TOKEN` nicht mit, schreibende Aufrufe enden mit `403` (geprüft an
`POST /api/region/estimate`: ohne Header 403, mit Header 200). Für Skripte den Wert des Cookies
`XSRF-TOKEN` als Header `X-XSRF-TOKEN` mitsenden. springdoc kann das mit
`springdoc.swagger-ui.csrf.enabled: true` selbst übernehmen; das ist noch nicht eingestellt.

### Reverse-Proxy-Vorlage (2026-11-05)

Der Container lauscht dann nur lokal; der Proxy terminiert TLS. In `docker-compose.yml`:

```yaml
    ports:
      - "127.0.0.1:8090:8090"            # nur der Proxy auf demselben Host erreicht den Container
    environment:
      SPRING_PROFILES_ACTIVE: prod        # Secure-Cookie, kein CORS
```

Caddy (holt das Zertifikat selbst, setzt `X-Forwarded-*`):

```caddyfile
synthesis.example.org {
	request_body {
		max_size 210MB                    # wie spring.servlet.multipart.max-request-size
	}
	reverse_proxy 127.0.0.1:8090
}
```

nginx:

```nginx
server {
    listen 443 ssl;
    server_name synthesis.example.org;
    ssl_certificate     /etc/ssl/synthesis/fullchain.pem;
    ssl_certificate_key /etc/ssl/synthesis/privkey.pem;

    client_max_body_size 210m;            # Default 1m bricht Uploads (GTFS, Raster, OSM) ab
    proxy_read_timeout   300s;            # große Uploads und Vorschauen; Läufe selbst sind asynchron

    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-Port  $server_port;
    }
}
server {
    listen 80;
    server_name synthesis.example.org;
    return 301 https://$host$request_uri;
}
```

`/api/health` ist anmeldefrei und eignet sich als Proxy-Healthcheck. Ein Unterpfad
(`/synthesis/`) wird nicht unterstützt: der Angular-Build hat `base href="/"`. Nicht
mit `SPRING_PROFILES_ACTIVE=prod` über reines HTTP von einem anderen Rechner zugreifen — der Browser
schickt das Secure-Cookie dann nicht zurück und die Anmeldung scheitert stumm.
Die Vorlagen sind nicht gegen einen laufenden Proxy geprüft (2026-11-05); geprüft sind die
Werte, auf die sie sich beziehen (Port, Multipart-Grenze, Header-Strategie, Profil).

### Sicherung und Aufräumen des Datenvolumes (2026-11-05)

Alles, was die Plattform an Zustand hat, liegt im Volume `synthesis-data` unter `/app/data`
(Compose-Name `synthesis_synthesis-data`): `users.json` (Konten, bcrypt-Hashes),
`initial-admin-password.txt` (nur bis zum ersten Passwortwechsel), `scenarios/<id>/scenario.json`
(Szenario), `scenarios/<id>/uploads/` (hochgeladene Eingaben), `scenarios/<id>/artifacts/`
(Ergebnisse inkl. `job.json`) und `census-cache/` (wieder ladbar). Datenbank gibt es keine.

```sh
# Sicherung als Datenstrom aus dem laufenden Container, kein Zusatz-Image nötig
docker compose exec -T synthesis tar czf - -C /app/data . > synthesis-data-$(date +%F).tgz

# Wiederherstellung: Dienst anhalten, Archiv einspielen, Dienst starten
docker compose stop synthesis
docker compose run --rm --no-deps -T --entrypoint tar synthesis xzf - -C /app/data < synthesis-data-JJJJ-MM-TT.tgz
docker compose start synthesis

# Platzbedarf je Szenario
docker compose exec synthesis sh -c 'du -sh /app/data/scenarios/*'
```

Konsistent ist eine Sicherung zwischen zwei Läufen; während einer Generierung schreibt der Worker
in `artifacts/`. Für eine Sicherung ohne Ergebnisse (sie lassen sich neu erzeugen) genügt
`--exclude='./scenarios/*/artifacts'` am `tar`-Aufruf.

**Aufräumen:** Eine Aufräumfunktion in der Oberfläche gibt es noch nicht (Review-Befund M4).
Ein Szenario samt Artefakten löscht die Schaltfläche „✕“ (Tooltip „Ausgewähltes Szenario löschen“) neben der Szenarioauswahl im Studio (Ersteller oder Admin). Nur die
Ergebnisse eines Szenarios entfernt
`docker compose exec synthesis rm -rf /app/data/scenarios/<id>/artifacts`; Szenario und Uploads
bleiben, „Szenario erzeugen“ legt die Artefakte neu an. Nicht während eines laufenden Jobs.

**Gemessen (2026-11-05, lokale Abnahme):** Ein Lauf auf 0,02° × 0,03° in Rastatt mit
Standardwerten (Zensus 2022, 26 434 Personen, 537 Zonen, reales Straßennetz) belegt 104 MB
Artefakte (`legacy/` 70 MB, `population/` 25 MB, `matrix/` 6 MB) — deutlich mehr als die 28 MB
des Fallback-Laufs mit 300 Personen aus dem Review. Die Sicherung des Volumes dauerte 5 s und
ergab 63 MB.

## Qualitätswerkzeuge (2026-11-04)

| Werkzeug | Aufruf | Bindung |
|---|---|---|
| JaCoCo 0.8.13 | `cd backend && mvn -o test` → `target/site/jacoco/index.html` | an `test` gebunden; keine Mindestabdeckung erzwungen (erst messen, dann Schwelle) |
| Spotless 2.46.1 (nur `removeUnusedImports`, `trimTrailingWhitespace`, `endWithNewline`) | `mvn spotless:check` / `mvn spotless:apply` | bewusst an keine Phase gebunden; die Gates bleiben wie bisher. Erster Lauf entfernte 8 ungenutzte Importe |
| angular-eslint 21 (flat config `frontend/eslint.config.js`) | `cd frontend && npm run lint` | CI-Job `frontend-tests-and-build` und `scripts/verify.sh`; Abweichungen von den Empfehlungen sind in der Konfiguration begründet (`!= null` in Vorlagen, `any` in Specs, `autofocus` auf der Anmeldeseite) |
| `npm audit --omit=dev` | `npm run audit:prod` (CI) | Angular auf 21.2.26 / CLI 21.2.29 angehoben, weil `@angular/router` < 21.2.25 eine hohe Meldung trug und der CI-Job sonst rot wäre |

Die Umstellung der Konstruktor-Injektion auf `inject()` (9 Klassen) wurde von Hand gemacht: das
Angular-Schematic `ng generate @angular/core:inject` formatiert die betroffenen Dateien komplett
um (Anführungszeichen, Importumbrüche) und hätte einen unlesbaren Diff erzeugt.

## Abnahmekriterium

Ein Rechner gilt als vollständig eingerichtet, wenn `scripts/preflight.sh full`
keinen Fehler meldet und `scripts/verify.sh full` Backend, Frontend, actiTopp, SUMO,
GTFS/PT und die laufende Ollama-Instanz ohne umgebungsbedingte Skips prüft. Für
logiTopp prüft die Vorprüfung Commit und Patch-Vertrag; ein vollständiger
logiTopp-Wochenlauf bleibt wegen Laufzeit und Speicherbedarf eine eigene fachliche
Abnahme.

Die erste Härtungsstufe verwendet bewusst gepinnte Quellen und maschinenlesbare
Verträge statt Container-Images. Ein selbst gehosteter Runner kann denselben Vertrag
mit `scripts/verify.sh full` ausführen. Container beziehungsweise vorkonfigurierte
Runner-Images und ein lokaler GraphHopper-PBF bleiben mögliche nächste Ausbaustufen.
