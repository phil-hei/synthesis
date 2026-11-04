package de.synthesis.companytour;

import java.text.Normalizer;
import java.util.*;

/** Local address gazetteer; missing house numbers use nearest known number on the same street/postcode. */
public final class CompanyTourLocations {
    public record Match(String stopId, List<CompanyTourExecution.Point> candidates) {}
    public record Lookup(String sourceHash, int matched, int ambiguous, int missing, List<Match> matches) {}
    public static Lookup match(byte[] bytes, String filename, CompanyTourMapper.Draft draft) {
        var tables = new TourTableReader().read(bytes, filename);
        if (tables.size() != 1) throw new IllegalArgumentException("Ortstabelle muss genau ein Blatt enthalten");
        var rows = tables.getFirst().rows(); var header = rows.getFirst().cells();
        var columns = new HashMap<String, Integer>();
        for (int i = 0; i < header.size(); i++) {
            String key = switch (normal(header.get(i))) {
                case "street", "strasse", "adresse" -> "street";
                case "postalcode", "plz" -> "postalCode";
                case "city", "ort" -> "city";
                case "lon", "longitude" -> "lon";
                case "lat", "latitude" -> "lat";
                default -> "";
            };
            if (!key.isEmpty() && columns.putIfAbsent(key, i) != null) throw new IllegalArgumentException("Doppelte Spalte in Ortstabelle: " + key);
        }
        if (!columns.keySet().containsAll(Set.of("street", "postalCode", "city", "lon", "lat")))
            throw new IllegalArgumentException("Ortstabelle benötigt street;postalCode;city;lon;lat (alternativ Strasse;PLZ;Ort)");
        String hash = CompanyTourMapper.hash(bytes); Map<String, List<CompanyTourExecution.Point>> addresses = new HashMap<>();
        record Address(CompanyTourMapper.Location location,CompanyTourExecution.Point point) {}
        List<Address> known=new ArrayList<>();
        for (var row : rows.subList(1, rows.size())) {
            if (row.cells().stream().allMatch(String::isBlank)) continue;
            if (row.cells().size() != header.size()) throw new IllegalArgumentException("Ortstabelle: abweichende Spaltenzahl in Zeile " + row.sourceRow());
            try {
                var location = new CompanyTourMapper.Location(cell(row, columns, "street"), cell(row, columns, "postalCode"), cell(row, columns, "city"));
                if (location.street().isBlank() || location.postalCode().isBlank() || location.city().isBlank()) throw new IllegalArgumentException();
                double lon = Double.parseDouble(cell(row, columns, "lon").replace(',', '.'));
                double lat = Double.parseDouble(cell(row, columns, "lat").replace(',', '.'));
                if (!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90) throw new IllegalArgumentException();
                var list = addresses.computeIfAbsent(key(location), ignored -> new ArrayList<>());
                if (list.stream().noneMatch(p -> p.lon() == lon && p.lat() == lat))
                    list.add(new CompanyTourExecution.Point(lon, lat, "local-address-file:" + hash + ":" + row.sourceRow()));
                known.add(new Address(location,new CompanyTourExecution.Point(lon,lat,"local-address-file:"+hash+":"+row.sourceRow())));
            } catch (RuntimeException e) { throw new IllegalArgumentException("Ortstabelle: ungültige Adresse/Koordinate in Zeile " + row.sourceRow()); }
        }
        List<Match> matches = new ArrayList<>(); int found = 0, ambiguous = 0, missing = 0;
        for (var tour : draft.tours()) for (var stop : tour.stops()) {
            var candidates = addresses.getOrDefault(key(stop.location()), List.of());
            if(candidates.isEmpty()) {
                var wanted=house(stop.location().street());
                if(wanted!=null) {
                    var nearest=known.stream().filter(a->address(a.location().postalCode()).equals(address(stop.location().postalCode()))
                            && address(a.location().city()).equals(address(stop.location().city())))
                        .filter(a->{var h=house(a.location().street());return h!=null&&h.street().equals(wanted.street());})
                        .min(Comparator.comparingLong((Address a)->Math.abs((long)house(a.location().street()).number()-wanted.number()))
                            .thenComparing(a->key(a.location())).thenComparingDouble(a->a.point().lon()).thenComparingDouble(a->a.point().lat()));
                    if(nearest.isPresent()) {
                        var a=nearest.get();var p=a.point();
                        candidates=List.of(new CompanyTourExecution.Point(p.lon(),p.lat(),p.provenance()+":approximate-nearest-house-number:"+a.location().street()));
                    }
                }
            }
            matches.add(new Match(stop.stopId(), List.copyOf(candidates)));
            if (candidates.isEmpty()) missing++; else if (candidates.size() == 1) found++; else ambiguous++;
        }
        return new Lookup(hash, found, ambiguous, missing, List.copyOf(matches));
    }
    private record House(String street,int number) {}
    private static House house(String value) {
        var m=java.util.regex.Pattern.compile("^(.*?)\\s+(\\d+).*$").matcher(value.strip());
        if(!m.matches())return null;
        try{return new House(address(m.group(1)).replace("straße","strasse").replaceAll("str\\.$","strasse"),Integer.parseInt(m.group(2)));}
        catch(NumberFormatException e){return null;}
    }
    private static String cell(TourTableReader.Row row, Map<String, Integer> columns, String key) { return row.cells().get(columns.get(key)).strip(); }
    private static String normal(String s) { return Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFC).replaceAll("[^\\p{L}0-9]", ""); }
    private static String address(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
    private static String key(CompanyTourMapper.Location l) { return address(l.street()) + "|" + address(l.postalCode()) + "|" + address(l.city()); }
}
