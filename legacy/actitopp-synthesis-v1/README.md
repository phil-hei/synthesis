# Versionierter actiTopp-Vertrag

Dieser Vertrag ergänzt den externen MIT-lizenzierten `actitoppNG`-Clone um den
SyntheSis-CLI-Adapter. Der Clone bleibt unter `validation/` und wird nicht in das
Spring-Backend gelinkt.

Vertragskennung: `synthesis-actitopp-v1`

Enthalten sind:

- der Gradle-Task `runSynthesis -Pinput=... -Poutput=...`,
- der Kotlin-Runner für `personId;age;employment;gender`,
- das Austauschformat `personId;activityType;startMinute;durationMinute`,
- Commit- und Hash-Prüfungen vor jeder Installation.

Installation auf einem bereits ausgecheckten, gepinnten Clone:

```sh
sh legacy/actitopp-synthesis-v1/apply.sh
```

Der vollständige Neuaufbau erfolgt über `scripts/bootstrap-runtime.sh kit`.
