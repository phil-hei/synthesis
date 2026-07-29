# Versionierter logiTopp-/mobiTopp-Vertrag für Retouren

Dieser Patchvertrag hält die GPL-Projekte weiterhin als externe Prozesse und
versioniert nur die für SyntheSis notwendigen Integrationsänderungen.
Die Patchdateien ändern GPL-3.0-lizenzierte Upstream-Projekte und werden unter
deren Lizenz angewendet; sie werden nicht in das Spring-Boot-Backend gelinkt.

Vertragskennung: `synthesis-logitopp-returns-v1`

Enthalten sind:

- `parcel-demand-config.yaml` wird mit dem Vertrag
  `synthesis-parcel-demand-v1` gelesen und validiert.
- Zustellmenge und Zielanteile kommen aus dieser Datei statt aus Hardcodings.
- Optional aktivierte Retouren werden deterministisch zusätzlich zur
  Zustellnachfrage erzeugt.
- Pickups werden nach tatsächlichem Standort gruppiert.
- Pickup-Aufträge werden über den separaten Depot-Pickup-Bestand registriert.
- logiTopp wird unter der eindeutigen Maven-Koordinate
  `edu.kit.ifv.mobitopp:logitopp:0.0-synthesis-returns-v1` publiziert.

## Installation

Voraussetzung sind die bereits für SyntheSis modernisierten lokalen Projekte
unter `validation/mobitopp-rastatt` und `validation/logitopp`. Der Installer
prüft die SHA-256-Hashes der Patchdateien sowie der vier berührten Dateien vor
und nach dem Patch. Bei Drift bricht er ab, bevor er publiziert.

```sh
sh legacy/logitopp-returns-v1/apply.sh
```

Alternative Pfade können explizit übergeben werden:

```sh
sh legacy/logitopp-returns-v1/apply.sh \
  /pfad/zu/mobitopp-rastatt /pfad/zu/logitopp
```

Danach verlangt `SimulationRunner` den Marker
`config/synthesis/harness-contract.properties`. Ein alter Harness kann die neue
Paketkonfiguration damit nicht mehr still ignorieren.

`validation/` bleibt gitignoriert. Die Upstream-Commits und erwarteten
Vorher-/Nachher-Hashes stehen in `manifest.properties`; die Änderungen selbst
in den beiden Patchdateien.
