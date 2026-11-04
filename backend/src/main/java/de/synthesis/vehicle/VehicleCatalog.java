package de.synthesis.vehicle;

import de.synthesis.vehicle.VehicleType.*;
import de.synthesis.vehicle.VehicleConfiguration.*;
import java.io.IOException;
import java.util.*;

/** Pure validation and resolution; shared by HTTP, persistence and generation. */
public final class VehicleCatalog {
    private static final List<VehicleType> STANDARDS = load();
    public static final Set<String> ROLES = Set.of("car", "bicycle", "deliveryVan", "cargoBike", "heavyTruck", "articulatedTruck");
    private static final Set<String> EMISSIONS = Set.of("Zero", "HBEFA3/PC_G_EU4",
            "HBEFA3/PC_D_EU4", "HBEFA3/LDV_D_EU4", "HBEFA3/LDV_G_EU4", "HBEFA3/HDV_D_EU6");
    public record Issue(String path, String message) { }
    public record Capability(String target, String path, String status, String explanation) { }
    public record Report(boolean valid, VehicleConfiguration resolved, String sha256,
                         List<Issue> errors, List<Capability> capabilities) { }
    private static List<VehicleType> load() {
        try (var stream = VehicleCatalog.class.getResourceAsStream("/vehicle-types.json")) {
            if (stream == null) throw new IllegalStateException("Standardfahrzeugkatalog fehlt");
            return List.copyOf(Arrays.asList(VehicleJson.MAPPER.readValue(stream, VehicleType[].class)));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public static List<VehicleType> standards() { return STANDARDS; }

    public static Report validate(VehicleConfiguration config) {
        Validator v = new Validator();
        if (config == null) {
            v.error("$", "Fahrzeugkonfiguration fehlt");
            return new Report(false, null, null, v.errors, List.of());
        }
        v.check(VehicleConfiguration.CONTRACT.equals(config.schemaVersion()), "schemaVersion", "Unbekannte Vertragsversion");
        v.choice(config.application(), Set.of("catalogOnly", "sumoTraffic"), "application");
        Map<String, VehicleType> types = new TreeMap<>();
        Map<String, VehicleType> standards = new HashMap<>();
        STANDARDS.forEach(t -> standards.put(t.key(), t));
        int i = 0;
        for (TypeRef ref : v.list(config.catalogRefs(), "catalogRefs")) {
            String path = "catalogRefs[" + i++ + "]";
            if (ref == null || !standards.containsKey(ref.key())) v.error(path, "Unbekannte Standardversion");
            else v.check(types.putIfAbsent(ref.key(), standards.get(ref.key())) == null, path, "Doppelte Typreferenz");
        }
        i = 0;
        for (VehicleType type : v.list(config.vehicleTypes(), "vehicleTypes")) {
            String path = "vehicleTypes[" + i++ + "]";
            if (type == null) { v.error(path, "Typ fehlt"); continue; }
            v.type(type, path);
            v.check(type.id() == null || !type.id().startsWith("standard."), path + ".id", "Standard-Namensraum ist reserviert; Kopie benötigt eigene ID");
            v.check(types.putIfAbsent(type.key(), type) == null, path, "Doppelte Typ-ID und Version");
        }
        Map<String, Double> totals = new HashMap<>();
        Set<String> assignments = new HashSet<>();
        List<TrafficAssignment> traffic = v.list(config.trafficAssignments(), "trafficAssignments");
        i = 0;
        for (TrafficAssignment a : traffic) {
            String p = "trafficAssignments[" + i++ + "]";
            if (a == null) { v.error(p, "Zuordnung fehlt"); continue; }
            v.choice(a.role(), ROLES, p + ".role");
            v.positive(a.share(), p + ".share");
            VehicleType type = v.ref(a.typeRef(), types, p + ".typeRef");
            if (a.share() != null && Double.isFinite(a.share())) totals.merge(String.valueOf(a.role()), a.share(), Double::sum);
            v.check(assignments.add(a.role() + ":" + a.typeRef()), p, "Typ mehrfach derselben Verkehrsrolle zugeordnet");
            if (type != null && type.routing() != null) {
                String expected = switch (String.valueOf(a.role())) {
                    case "car" -> "passenger";
                    case "deliveryVan" -> "delivery";
                    case "heavyTruck" -> "truck";
                    case "articulatedTruck" -> "trailer";
                    default -> "bicycle";
                };
                v.check(expected.equals(type.routing().sumoVehicleClass()), p, "Verkehrsrolle benötigt SUMO-Klasse " + expected);
                if (config.appliesToSumo() && type.driving() != null
                        && !"human".equals(type.driving().controlMode())) {
                    v.check(config.allowBehaviorApproximation(), p,
                            "Automatisiert/ferngesteuert wird nur als Fahrverhaltensprofil angenähert; allowBehaviorApproximation explizit setzen");
                }
            }
        }
        totals.forEach((role, total) -> v.check(Math.abs(total - 1) <= 1e-9,
                "trafficAssignments." + role, "Anteile je Verkehrsrolle müssen zusammen 1 ergeben"));
        v.check(!config.appliesToSumo() || !traffic.isEmpty(), "trafficAssignments", "SUMO-Anwendung benötigt mindestens eine Verkehrszuordnung");
        for (String capability : v.list(config.requiredCapabilities(), "requiredCapabilities")) {
            v.check("traffic".equals(capability), "requiredCapabilities",
                    "Nicht unterstützte Simulationsfunktion: " + capability + " (Batterie, Laden und Disposition sind noch nicht ausführbar)");
            v.check(!"traffic".equals(capability) || config.appliesToSumo(), "application", "Angeforderte Verkehrssimulation benötigt sumoTraffic");
        }
        Map<String, FleetAssignment> fleets = new HashMap<>();
        i = 0;
        for (FleetAssignment f : v.list(config.fleetAssignments(), "fleetAssignments")) {
            String p = "fleetAssignments[" + i++ + "]";
            if (f == null) { v.error(p, "Flottenzuordnung fehlt"); continue; }
            v.id(f.id(), p + ".id");
            v.check(fleets.putIfAbsent(f.id(), f) == null, p, "Doppelte Flotten-ID");
            v.ref(f.typeRef(), types, p + ".typeRef");
            v.check(f.count() != null && f.count() > 0, p + ".count", "Positive Fahrzeugzahl erforderlich");
            v.text(f.locationRef(), p + ".locationRef");
        }
        Set<String> vehicleIds = new HashSet<>();
        Map<String, Integer> fleetCounts = new HashMap<>();
        i = 0;
        for (VehicleInstance instance : v.list(config.initialVehicles(), "initialVehicles")) {
            String p = "initialVehicles[" + i++ + "]";
            if (instance == null) { v.error(p, "Fahrzeug fehlt"); continue; }
            v.id(instance.vehicleId(), p + ".vehicleId");
            v.check(vehicleIds.add(instance.vehicleId()), p, "Doppelte Fahrzeug-ID");
            VehicleType type = v.ref(instance.typeRef(), types, p + ".typeRef");
            if (instance.fleetId() != null) {
                FleetAssignment fleet = fleets.get(instance.fleetId());
                v.check(fleet != null, p + ".fleetId", "Unbekannte Flotte");
                if (fleet != null) v.check(Objects.equals(fleet.typeRef(), instance.typeRef()), p, "Fahrzeugtyp passt nicht zur Flotte");
                fleetCounts.merge(instance.fleetId(), 1, Integer::sum);
            }
            v.text(instance.locationRef(), p + ".locationRef");
            if (type != null) v.state(instance.state(), type, p + ".state");
        }
        fleetCounts.forEach((id, count) -> {
            FleetAssignment f = fleets.get(id);
            if (f != null && f.count() != null) v.check(count <= f.count(), "fleetAssignments." + id, "Mehr Instanzen als Flottenfahrzeuge");
        });
        List<Capability> capabilities = capabilities(config, types.values());
        if (!v.errors.isEmpty()) return new Report(false, null, null, List.copyOf(v.errors), capabilities);
        VehicleConfiguration resolved = new VehicleConfiguration(config.schemaVersion(), config.application(),
                config.allowBehaviorApproximation(), List.copyOf(config.requiredCapabilities()),
                List.copyOf(types.values()), List.of(), traffic.stream().sorted(Comparator.comparing(TrafficAssignment::role)
                    .thenComparing(a -> a.typeRef().key())).toList(),
                config.fleetAssignments().stream().sorted(Comparator.comparing(FleetAssignment::id)).toList(),
                config.initialVehicles().stream().sorted(Comparator.comparing(VehicleInstance::vehicleId)).toList());
        return new Report(true, resolved, VehicleJson.hash(resolved), List.of(), capabilities);
    }

    public static VehicleConfiguration requireValid(VehicleConfiguration config) {
        Report report = validate(config);
        if (!report.valid()) throw new IllegalArgumentException(report.errors().getFirst().path() + ": " + report.errors().getFirst().message());
        return report.resolved();
    }
    private static List<Capability> capabilities(VehicleConfiguration c, Collection<VehicleType> types) {
        List<Capability> rows = new ArrayList<>();
        rows.add(new Capability("logitopp", "*", "metadataOnly", "Fahrzeugparameter verändern weder Nachfrage noch Tourplanung; Legacy-Typen bleiben unverändert."));
        rows.add(new Capability("mobilecity", "*", "metadataOnly", "Exportiert weiterhin aggregierten Pkw-Bestand; kein individueller Fahrzeugparameter-Import."));
        rows.add(new Capability("sumo", "fleetAssignments,initialVehicles,powertrain,operation,emissions,noise,capacity",
                "metadataOnly", "Katalog allein simuliert keine Flotte. Explizite Fahrzeugzuordnungen und native Fahrtketten sind separat über vehicleOperations aktivierbar; Batterie, Laden, Ladungsänderungen und Lärm bleiben ungekoppelt."));
        rows.add(new Capability("sumo", "physical.widthM,physical.heightM", "metadataOnly", "Als vType exportiert; im verwendeten Standard-Spurmodell keine zugesicherte Verkehrswirkung."));
        for (VehicleType t : types) {
            boolean used = c.appliesToSumo() && c.trafficAssignments() != null && c.trafficAssignments().stream()
                    .filter(Objects::nonNull).anyMatch(a -> a.typeRef() != null && t.key().equals(a.typeRef().key()));
            rows.add(new Capability("sumo", t.key() + ".physical,driving,routing.sumoVehicleClass,routing.sumoEmissionClass",
                    used ? "configured" : "metadataOnly", used
                    ? "Länge, Höchstgeschwindigkeit, Fahrverhalten, Verkehrsklasse und explizite Emissionsklasse im SUMO-Export; tatsächlicher Lauf separat nachweisen. Leermasse wird exportiert, nicht durch dynamische Beladung ergänzt."
                    : "Keine aktive SUMO-Verkehrszuordnung."));
            if (used && t.driving() != null && !"human".equals(t.driving().controlMode())) rows.add(new Capability(
                    "sumo", t.key() + ".driving.controlMode", "approximated", "Nur Fahrfolgemodell; keine autonome Disposition oder Fernsteuerung."));
        }
        return List.copyOf(rows);
    }

    private static final class Validator {
        final List<Issue> errors = new ArrayList<>();
        void error(String p, String m) { errors.add(new Issue(p, m)); }
        void check(boolean ok, String p, String m) { if (!ok) error(p, m); }
        void text(String value, String p) { check(value != null && !value.isBlank() && value.length() <= 2000, p, "Nichtleerer Text erforderlich (max. 2000 Zeichen)"); }
        void id(String value, String p) { check(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"), p, "ID: 1–96 Buchstaben/Ziffern/Punkt/Bindestrich/Unterstrich"); }
        void choice(String value, Set<String> choices, String p) { check(value != null && choices.contains(value), p, "Erlaubte Werte: " + new TreeSet<>(choices)); }
        void nonnegative(Double value, String p) { check(value != null && Double.isFinite(value) && value >= 0, p, "Endliche Zahl ≥ 0 erforderlich"); }
        void positive(Double value, String p) { check(value != null && Double.isFinite(value) && value > 0, p, "Endliche Zahl > 0 erforderlich"); }
        void optional(Double value, String p) { if (value != null) nonnegative(value, p); }
        void fraction(Double value, String p) { if (value != null) check(Double.isFinite(value) && value >= 0 && value <= 1, p, "Wirkungsgrad muss zwischen 0 und 1 liegen"); }
        <T> List<T> list(List<T> items, String p) {
            if (items == null) { error(p, "Liste fehlt"); return List.of(); }
            check(items.size() <= 1000, p, "Höchstens 1000 Einträge erlaubt");
            return items.size() <= 1000 ? items : items.subList(0, 1000);
        }
        VehicleType ref(TypeRef ref, Map<String, VehicleType> types, String p) {
            VehicleType t = ref == null ? null : types.get(ref.key());
            check(t != null, p, "Typreferenz mit exakter Version nicht auflösbar");
            return t;
        }
        void type(VehicleType t, String p) {
            id(t.id(), p + ".id"); id(t.version(), p + ".version"); text(t.name(), p + ".name");
            choice(t.category(), Set.of("passengerCar", "deliveryVan", "bicycle", "cargoBike", "lightVehicle", "heavyTruck", "articulatedTruck"), p + ".category");
            if (t.provenance() == null) error(p + ".provenance", "Quelle und Qualität fehlen");
            else {
                text(t.provenance().source(), p + ".provenance.source");
                choice(t.provenance().quality(), Set.of("assumed", "sourced", "measured"), p + ".provenance.quality");
                text(t.provenance().notes(), p + ".provenance.notes");
            }
            var a = t.physical();
            if (a == null) error(p + ".physical", "Physische Eigenschaften fehlen");
            else {
                positive(a.curbMassKg(), p + ".physical.curbMassKg"); positive(a.lengthM(), p + ".physical.lengthM");
                positive(a.widthM(), p + ".physical.widthM"); positive(a.heightM(), p + ".physical.heightM");
                positive(a.maxSpeedKmh(), p + ".physical.maxSpeedKmh"); optional(a.maxAccelerationMps2(), p + ".physical.maxAccelerationMps2");
                optional(a.maxGrossMassKg(), p + ".physical.maxGrossMassKg");
                if (a.maxGrossMassKg() != null && a.curbMassKg() != null) check(a.maxGrossMassKg() >= a.curbMassKg(), p + ".physical.maxGrossMassKg", "Gesamtmasse muss mindestens Leermasse sein");
            }
            var c = t.capacity();
            if (c == null) error(p + ".capacity", "Kapazitäten fehlen");
            else {
                check(c.passengerSeats() != null && c.passengerSeats() >= 0, p + ".capacity.passengerSeats", "Nichtnegative Sitzplatzzahl erforderlich");
                check(c.operatorSeats() != null && c.operatorSeats() >= 0, p + ".capacity.operatorSeats", "Nichtnegative Sitzplatzzahl erforderlich");
                nonnegative(c.payloadKg(), p + ".capacity.payloadKg"); nonnegative(c.cargoVolumeM3(), p + ".capacity.cargoVolumeM3");
                if (a != null && a.maxGrossMassKg() != null && a.curbMassKg() != null && c.payloadKg() != null)
                    check(a.curbMassKg() + c.payloadKg() <= a.maxGrossMassKg(), p + ".capacity.payloadKg", "Nutzlast überschreitet maximale Gesamtmasse bereits ohne Insassen");
            }
            var power = t.powertrain();
            if (power == null) error(p + ".powertrain", "Antrieb fehlt");
            if (power instanceof CombustionPowertrain engine) {
                choice(engine.fuel(), Set.of("gasoline", "diesel"), p + ".powertrain.fuel");
                positive(engine.tankCapacityL(), p + ".powertrain.tankCapacityL"); optional(engine.litresPerKm(), p + ".powertrain.litresPerKm");
            } else if (power instanceof ElectricPowertrain electric) {
                positive(electric.usableBatteryCapacityKwh(), p + ".powertrain.usableBatteryCapacityKwh");
                positive(electric.maxChargePowerKw(), p + ".powertrain.maxChargePowerKw");
                fraction(electric.chargingEfficiency(), p + ".powertrain.chargingEfficiency");
                fraction(electric.recuperationEfficiency(), p + ".powertrain.recuperationEfficiency");
                optional(electric.kwhPerKm(), p + ".powertrain.kwhPerKm");
                choice(electric.assistanceMode(), Set.of("full", "pedalAssist"), p + ".powertrain.assistanceMode");
                if ("pedalAssist".equals(electric.assistanceMode())) positive(electric.assistanceCutoffKmh(), p + ".powertrain.assistanceCutoffKmh");
                else check(electric.assistanceCutoffKmh() == null, p + ".powertrain.assistanceCutoffKmh", "Unterstützungsgrenze nur für pedalAssist");
                for (String connector : list(electric.connectors(), p + ".powertrain.connectors")) text(connector, p + ".powertrain.connectors");
            }
            var d = t.driving();
            if (d == null) error(p + ".driving", "Fahrverhaltensprofil fehlt");
            else {
                choice(d.controlMode(), Set.of("human", "automated", "remote"), p + ".driving.controlMode");
                choice(d.carFollowModel(), Set.of("Krauss", "IDM", "ACC", "CACC"), p + ".driving.carFollowModel");
                positive(d.accelerationMps2(), p + ".driving.accelerationMps2"); positive(d.decelerationMps2(), p + ".driving.decelerationMps2");
                positive(d.emergencyDecelerationMps2(), p + ".driving.emergencyDecelerationMps2");
                nonnegative(d.minGapM(), p + ".driving.minGapM"); positive(d.timeHeadwayS(), p + ".driving.timeHeadwayS");
                if (d.decelerationMps2() != null && d.emergencyDecelerationMps2() != null) check(d.emergencyDecelerationMps2() >= d.decelerationMps2(), p + ".driving", "Notbremsung muss mindestens normale Bremsung erreichen");
                if (a != null && a.maxAccelerationMps2() != null && d.accelerationMps2() != null) check(d.accelerationMps2() <= a.maxAccelerationMps2(), p + ".driving.accelerationMps2", "Fahrprofil überschreitet technische Beschleunigung");
            }
            var r = t.routing();
            if (r == null) error(p + ".routing", "Routingprofil fehlt");
            else {
                choice(r.sumoVehicleClass(), Set.of("passenger", "delivery", "bicycle", "truck", "trailer"), p + ".routing.sumoVehicleClass");
                choice(r.sumoEmissionClass(), EMISSIONS, p + ".routing.sumoEmissionClass");
                List<String> networks = list(r.allowedNetworks(), p + ".routing.allowedNetworks");
                check(!networks.isEmpty(), p + ".routing.allowedNetworks", "Mindestens ein Netztyp erforderlich");
                for (String network : networks) choice(network, Set.of("road", "bike"), p + ".routing.allowedNetworks");
                Set<String> expected = "bicycle".equals(r.sumoVehicleClass()) ? Set.of("road", "bike") : Set.of("road");
                check(new HashSet<>(networks).equals(expected) && networks.size() == expected.size(), p + ".routing.allowedNetworks", "Unterstütztes Profil für diese SUMO-Klasse: " + expected + "; individuelle Netzfilter noch nicht implementiert");
                if (power instanceof ElectricPowertrain || power instanceof HumanPowered) check("Zero".equals(r.sumoEmissionClass()), p + ".routing.sumoEmissionClass", "Elektro/Muskelkraft benötigt direkte Emissionsklasse Zero");
                if (power instanceof CombustionPowertrain engine) {
                    String prefix = "passenger".equals(r.sumoVehicleClass()) ? "HBEFA3/PC_" : "HBEFA3/LDV_";
                    boolean heavy = Set.of("truck", "trailer").contains(Objects.toString(r.sumoVehicleClass(), ""));
                    String expectedClass = heavy ? "HBEFA3/HDV_D_EU6" : prefix + ("diesel".equals(engine.fuel()) ? "D" : "G") + "_EU4";
                    if (heavy) check("diesel".equals(engine.fuel()), p + ".powertrain", "Schwerlast-Referenzmodell benötigt Diesel");
                    check(expectedClass.equals(r.sumoEmissionClass()) && !"bicycle".equals(r.sumoVehicleClass()), p + ".routing.sumoEmissionClass", "Für diese Klasse/Kraftstoff unterstütztes Referenzmodell: " + expectedClass);
                }
            }
            if (t.operation() != null) {
                var o = t.operation(); text(o.deliveryMode(), p + ".operation.deliveryMode"); text(o.parkingBehavior(), p + ".operation.parkingBehavior");
                nonnegative(o.loadingTimeS(), p + ".operation.loadingTimeS"); nonnegative(o.unloadingTimeS(), p + ".operation.unloadingTimeS");
                choice(o.serviceTimeBasis(), Set.of("stop", "parcel", "kg"), p + ".operation.serviceTimeBasis");
            }
            if (t.emissions() != null) {
                var e = t.emissions(); nonnegative(e.gramsPerKm(), p + ".emissions.gramsPerKm");
                choice(e.substance(), Set.of("CO2", "CO2e"), p + ".emissions.substance");
                choice(e.boundary(), Set.of("tailpipe", "wellToWheel", "lifecycle"), p + ".emissions.boundary"); text(e.source(), p + ".emissions.source");
                if ((power instanceof ElectricPowertrain || power instanceof HumanPowered) && "tailpipe".equals(e.boundary()) && e.gramsPerKm() != null) check(e.gramsPerKm() == 0, p + ".emissions", "Elektro/Muskelkraft hat keine direkten CO₂-Abgasemissionen");
            }
            if (t.noise() != null) {
                var n = t.noise(); check(n.decibels() != null && Double.isFinite(n.decibels()), p + ".noise.decibels", "Endlicher Pegel erforderlich");
                text(n.weighting(), p + ".noise.weighting"); positive(n.distanceM(), p + ".noise.distanceM");
                nonnegative(n.speedKmh(), p + ".noise.speedKmh"); text(n.conditions(), p + ".noise.conditions");
            }
        }
        void state(VehicleState s, VehicleType t, String p) {
            if (s == null) { error(p, "Anfangszustand fehlt"); return; }
            nonnegative(s.cargoWeightKg(), p + ".cargoWeightKg"); nonnegative(s.cargoVolumeM3(), p + ".cargoVolumeM3");
            check(s.passengers() != null && s.passengers() >= 0, p + ".passengers", "Nichtnegative Personenzahl erforderlich");
            check(s.operators() != null && s.operators() >= 0, p + ".operators", "Nichtnegative Bedienpersonenzahl erforderlich");
            var c = t.capacity();
            if (c != null) {
                bounded(s.cargoWeightKg(), c.payloadKg(), p + ".cargoWeightKg"); bounded(s.cargoVolumeM3(), c.cargoVolumeM3(), p + ".cargoVolumeM3");
                if (s.passengers() != null && c.passengerSeats() != null) check(s.passengers() <= c.passengerSeats(), p + ".passengers", "Sitzplatzkapazität überschritten");
                if (s.operators() != null && c.operatorSeats() != null) check(s.operators() <= c.operatorSeats(), p + ".operators", "Bedienplätze überschritten");
            }
            if (t.powertrain() instanceof ElectricPowertrain e) {
                nonnegative(s.batteryEnergyKwh(), p + ".batteryEnergyKwh"); bounded(s.batteryEnergyKwh(), e.usableBatteryCapacityKwh(), p + ".batteryEnergyKwh");
                check(s.fuelLitres() == null, p + ".fuelLitres", "Elektrofahrzeug hat keinen Kraftstofftank");
            } else if (t.powertrain() instanceof CombustionPowertrain e) {
                nonnegative(s.fuelLitres(), p + ".fuelLitres"); bounded(s.fuelLitres(), e.tankCapacityL(), p + ".fuelLitres");
                check(s.batteryEnergyKwh() == null, p + ".batteryEnergyKwh", "Verbrennervertrag hat keine Traktionsbatterie");
            } else check(s.fuelLitres() == null && s.batteryEnergyKwh() == null, p, "Muskelkraft hat weder Tank noch Traktionsbatterie");
        }
        void bounded(Double value, Double max, String p) { if (value != null && max != null) check(value <= max, p, "Kapazität überschritten"); }
    }
}
