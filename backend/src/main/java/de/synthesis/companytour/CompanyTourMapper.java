package de.synthesis.companytour;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Finite mapping vocabulary, positional columns and source-row evidence. Never executes LLM code. */
public final class CompanyTourMapper {
    public static final List<String> FIELDS = List.of("tour", "date", "transaction", "order", "street", "postalCode", "city",
            "arrival", "departure", "windowStart", "windowEnd", "orderType", "workValue");
    public static final Set<String> REQUIRED = Set.of("tour", "date", "transaction", "street", "postalCode", "city", "arrival", "departure");
    public static final Set<String> DATE_FORMATS = Set.of("yyyyMMdd", "dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yyyy");
    public record Join(List<Integer> columns,String separator) {}
    public record Mapping(String tableId, int headerRow, Map<String, Integer> columns, String dateFormat,
                          String timezone, boolean groupStops, List<String> fillDownFields, Map<String,Join> joins, String timeFormat) {
        public Mapping {fillDownFields=fillDownFields==null?List.of():List.copyOf(fillDownFields);joins=joins==null?Map.of():Map.copyOf(joins);timeFormat=timeFormat==null?"HH:mm":timeFormat;}
        public Mapping(String tableId,int headerRow,Map<String,Integer> columns,String dateFormat,String timezone,boolean groupStops){this(tableId,headerRow,columns,dateFormat,timezone,groupStops,List.of(),Map.of(),"HH:mm");}
    }
    public record Column(int index, String header, Map<String, Integer> types) {}
    public record Profile(String id, String name, int headerRow, int dataRows, List<Column> columns) {}
    public record Issue(String code, int sourceRow, String field) {}
    public record Order(String transactionId, String orderId, String orderType, String workValue,
                        String windowStart, String windowEnd, int sourceRow) {}
    public record Location(String street, String postalCode, String city) {}
    public record Stop(String stopId, int sequence, Location location, String plannedArrival, String plannedDeparture,
                       long plannedDwellSec, String windowStart, String windowEnd, List<Order> orders, List<Integer> sourceRows) {}
    public record Tour(String tourId, String sourceTourCode, String serviceDate, String vehicleId,
                       String start, String end, String returnPolicy, List<Stop> stops) {}
    public record Draft(String schemaVersion, String sourceHash, String mappingHash, String timezone, String status,
                        boolean simulationReady, int inputRows, int mappedRows, int rejectedRows, int blankRows,
                        List<Integer> unmappedColumns, Mapping mapping, List<Issue> issues, List<Tour> tours) {}

    public Profile profile(TourTableReader.Table table, int headerRow) {
        var header = table.rows().stream().filter(r -> r.sourceRow() == headerRow).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kopfzeile nicht gefunden"));
        var data = table.rows().stream().filter(r -> r.sourceRow() > headerRow).toList();
        int width = table.rows().stream().filter(r -> r.sourceRow() >= headerRow).mapToInt(r -> r.cells().size()).max().orElse(0);
        List<Column> columns = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            Map<String, Integer> types = new TreeMap<>();
            for (var row : data) types.merge(type(value(row, c)), 1, Integer::sum);
            columns.add(new Column(c, value(header, c), types));
        }
        return new Profile(table.id(), table.name(), headerRow, data.size(), columns);
    }
    public List<Profile> profiles(List<TourTableReader.Table> tables) {
        return tables.stream().map(t -> profile(t, t.rows().stream().filter(r -> r.cells().stream().anyMatch(s -> !s.isBlank()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Leeres Tabellenblatt")).sourceRow())).toList();
    }
    public Mapping bestMapping(List<TourTableReader.Table> tables) {
        return tables.stream().flatMap(t->t.rows().stream().limit(30).filter(r->r.cells().stream().anyMatch(v->!v.isBlank())).map(r->suggest(profile(t,r.sourceRow()))))
                .max(Comparator.comparingInt(m->m.columns().size())).orElseThrow(()->new IllegalArgumentException("Keine Kopfzeile gefunden"));
    }
    public Mapping suggest(Profile profile) {
        Map<String, Integer> columns = new LinkedHashMap<>(); int window = 0;
        for (Column c : profile.columns()) {
            String field = field(c.header());
            if (normal(c.header()).equals("zeitfensterkunde")) field = window++ == 0 ? "windowStart" : "windowEnd";
            if (field != null && !columns.containsKey(field)) columns.put(field, c.index());
        }
        String format = "yyyyMMdd";
        var date = profile.columns().stream().filter(c -> Objects.equals(c.index(), columns.get("date"))).findFirst();
        if (date.isPresent()) {
            var types = date.get().types();
            if (types.getOrDefault("isoDate", 0) > 0) format = "yyyy-MM-dd";
            else if (types.getOrDefault("germanDate", 0) > 0) format = "dd.MM.yyyy";
        }
        return new Mapping(profile.id(), profile.headerRow(), columns, format, "Europe/Berlin", true);
    }
    public static String field(String header) {
        return switch (normal(header)) {
            case "tour", "tourid", "tourcode", "routeid", "route" -> "tour";
            case "liefertag", "datum", "date", "deliverydate", "servicedate" -> "date";
            case "vorgang", "transaction", "transactionid", "vorgangid" -> "transaction";
            case "auftragsnummer", "order", "orderid" -> "order";
            case "strasse", "street", "adresse", "address" -> "street";
            case "plz", "postalcode", "zipcode" -> "postalCode";
            case "ort", "city", "stadt" -> "city";
            case "geplanteankunftpass", "ankunft", "arrival", "plannedarrival" -> "arrival";
            case "geplanteabfahrtpass", "abfahrt", "departure", "planneddeparture" -> "departure";
            case "windowstart", "zeitfensterbeginn" -> "windowStart";
            case "windowend", "zeitfensterende" -> "windowEnd";
            case "auftragsart", "ordertype" -> "orderType";
            case "arbeitswerte", "workvalue" -> "workValue";
            default -> null;
        };
    }
    public void validate(Mapping m, Profile p) {
        if (m == null || m.columns() == null || !Objects.equals(m.tableId(), p.id()) || m.headerRow() != p.headerRow())
            throw new IllegalArgumentException("Mapping passt nicht zur gewählten Tabelle/Kopfzeile");
        if (m.dateFormat() == null || m.timezone() == null || !DATE_FORMATS.contains(m.dateFormat()) || !Set.of("Europe/Berlin", "UTC").contains(m.timezone()))
            throw new IllegalArgumentException("Nicht unterstütztes Datum oder Zeitzone");
        if(!Set.of("HH:mm","HH.mm","HHmm").contains(m.timeFormat()) || !m.columns().keySet().containsAll(m.fillDownFields()) || m.fillDownFields().size()>FIELDS.size())
            throw new IllegalArgumentException("Ungültige Zeitumwandlung oder Weiterführung");
        for(var e:m.joins().entrySet()) {
            if(!m.columns().containsKey(e.getKey())||e.getValue()==null||e.getValue().separator()==null||e.getValue().separator().length()>5||e.getValue().columns()==null||e.getValue().columns().isEmpty()||e.getValue().columns().size()>5
                ||e.getValue().columns().stream().anyMatch(c->c==null||c<0||c>=p.columns().size()))throw new IllegalArgumentException("Ungültige Spaltenzusammenführung");
        }
        Set<Integer> used = new HashSet<>();
        for (var e : m.columns().entrySet()) {
            if (!FIELDS.contains(e.getKey()) || e.getValue() == null || e.getValue() < 0 || e.getValue() >= p.columns().size()
                    || !used.add(e.getValue())) throw new IllegalArgumentException("Ungültige oder doppelte Spaltenzuordnung");
        }
    }
    public Draft apply(TourTableReader.Table table, Mapping m, String sourceHash) {
        var p = profile(table, m.headerRow()); validate(m, p);
        List<Issue> issues = new ArrayList<>();
        for (String field : new TreeSet<>(REQUIRED)) if (!m.columns().containsKey(field)) issues.add(new Issue("missing-column", 0, field));
        Set<Integer> usedColumns = new HashSet<>(m.columns().values());
        m.joins().values().forEach(j -> usedColumns.addAll(j.columns()));
        List<Integer> unmapped = p.columns().stream().map(Column::index).filter(c -> !usedColumns.contains(c)).toList();
        Map<List<String>, List<Parsed>> groups = new LinkedHashMap<>(); Set<String> transactions = new HashSet<>();
        int input = 0, rejected = 0, blanks = 0;Map<String,String> carried=new HashMap<>();
        for (var sourceRow : table.rows()) {
            var row=sourceRow;
            if (row.sourceRow() <= m.headerRow()) continue; input++;
            if (row.cells().stream().allMatch(String::isBlank)) { blanks++; continue; }
            if (!m.columns().keySet().containsAll(REQUIRED)) { rejected++; continue; }
            try {
                if (row.cells().size() != p.columns().size()) throw new FieldError("row-width", "");
                List<String> cells=new ArrayList<>(row.cells());
                for(var field:m.columns().entrySet()) {
                    String raw=value(row,field.getValue()).strip();var join=m.joins().get(field.getKey());
                    if(join!=null){var original=row;raw=join.columns().stream().map(c->value(original,c).strip()).filter(v->!v.isBlank()).collect(java.util.stream.Collectors.joining(join.separator()));}
                    if(m.fillDownFields().contains(field.getKey())){if(raw.isBlank())raw=carried.getOrDefault(field.getKey(),"");else carried.put(field.getKey(),raw);}
                    cells.set(field.getValue(),raw);
                }
                row=new TourTableReader.Row(row.sourceRow(),List.copyOf(cells));
                for (String f : REQUIRED) if (get(row, m, f).isBlank()) throw new FieldError("missing-value", f);
                if (row.cells().size() != p.columns().size()) throw new FieldError("row-width", "");
                LocalDate date = date(get(row, m, "date"), m.dateFormat());
                ZonedDateTime arrival = timestamp(date, get(row, m, "arrival"), m.timezone(), "arrival");
                ZonedDateTime departure = timestamp(date, get(row, m, "departure"), m.timezone(), "departure");
                if (departure.isBefore(arrival)) throw new FieldError("overnight-or-negative-dwell", "departure");
                String transaction = get(row, m, "transaction");
                if (!transactions.add(date + "|" + transaction)) throw new FieldError("duplicate-transaction", "transaction");
                String ws = window(row, m, "windowStart"), we = window(row, m, "windowEnd");
                if (!ws.isBlank() && !we.isBlank()) {
                    if (LocalTime.parse(we).isBefore(LocalTime.parse(ws))) throw new FieldError("invalid-window", "windowEnd");
                    if (arrival.toLocalTime().isBefore(LocalTime.parse(ws)) || departure.toLocalTime().isAfter(LocalTime.parse(we)))
                        issues.add(new Issue("window-semantics-review", row.sourceRow(), "windowStart"));
                }
                Order order = new Order(token(sourceHash + "\n" + date + "\n" + transaction),
                        get(row, m, "order").isBlank() ? "" : token(sourceHash + "\n" + date + "\n" + get(row, m, "order")),
                        get(row, m, "orderType"), get(row, m, "workValue"), ws, we, row.sourceRow());
            var parsed = new Parsed(get(row, m, "tour"), date.toString(), new Location(get(row, m, "street"), get(row, m, "postalCode"), get(row, m, "city")), arrival, departure, ws, we, order);
                groups.computeIfAbsent(List.of(parsed.tour, parsed.date), k -> new ArrayList<>()).add(parsed);
            } catch (FieldError e) { issues.add(new Issue(e.code, row.sourceRow(), e.field)); rejected++; }
        }
        List<Tour> tours = new ArrayList<>();
        for (var e : groups.entrySet()) {
            var rows = e.getValue(); rows.sort(Comparator.comparing(Parsed::arrival).thenComparingInt(r -> r.order.sourceRow()));
            Map<List<String>, List<Parsed>> stops = new LinkedHashMap<>();
            for (Parsed r : rows) {
                List<String> key = List.of(address(r.location.street), address(r.location.postalCode), address(r.location.city),
                        r.arrival.toString(), r.departure.toString(), m.groupStops() ? "" : String.valueOf(r.order.sourceRow()));
                stops.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            List<Stop> result = new ArrayList<>(); ZonedDateTime previous = null; int seq = 0;
            String tourId = "tour-" + hash(sourceHash + "\n" + String.join("\n", e.getKey())).substring(0, 20);
            for (var candidates : stops.values()) {
                Parsed r = candidates.getFirst();
                if (previous != null && r.arrival.isBefore(previous)) issues.add(new Issue("overlapping-stops", r.order.sourceRow(), "arrival"));
                previous = r.departure;
                boolean sameWindow = candidates.stream().allMatch(c -> c.windowStart.equals(r.windowStart) && c.windowEnd.equals(r.windowEnd));
                if (!sameWindow)
                    issues.add(new Issue("conflicting-window", r.order.sourceRow(), "windowStart"));
                result.add(new Stop(tourId + ":" + ++seq, seq, r.location, r.arrival.toOffsetDateTime().toString(), r.departure.toOffsetDateTime().toString(),
                        Duration.between(r.arrival, r.departure).toSeconds(), sameWindow ? r.windowStart : "", sameWindow ? r.windowEnd : "",
                        candidates.stream().map(Parsed::order).toList(), candidates.stream().map(c -> c.order.sourceRow()).toList()));
            }
            tours.add(new Tour(tourId, rows.getFirst().tour, rows.getFirst().date, null, null, null, "unknown", result));
        }
        boolean invalid = rejected > 0 || issues.stream().anyMatch(i -> Set.of("missing-column", "overlapping-stops").contains(i.code()));
        issues.add(new Issue("missing-depot-and-vehicle-context", 0, ""));
        issues.add(new Issue("geocoding-and-routing-required", 0, ""));
        issues.add(new Issue("chronological-sequence-needs-review", 0, ""));
        if (tours.isEmpty()) invalid = true;
        String mappingHash = hash(m.tableId() + "\n" + m.headerRow() + "\n" + new TreeMap<>(m.columns()) + "\n"
                + m.dateFormat() + "\n" + m.timezone() + "\n" + m.groupStops());
        if(!m.fillDownFields().isEmpty()||!m.joins().isEmpty()||!m.timeFormat().equals("HH:mm"))mappingHash=hash(mappingHash+"\n"+new TreeSet<>(m.fillDownFields())+"\n"+new TreeMap<>(m.joins())+"\n"+m.timeFormat());
        return new Draft("synthesis-company-tours-v1", sourceHash, mappingHash, m.timezone(), invalid ? "invalid" : "needs-context",
                false, input, input - rejected - blanks, rejected, blanks, unmapped, m, issues, tours);
    }
    private record Parsed(String tour, String date, Location location, ZonedDateTime arrival, ZonedDateTime departure,
                          String windowStart, String windowEnd, Order order) {}
    private static final class FieldError extends RuntimeException {
        final String code, field; FieldError(String code, String field) { this.code = code; this.field = field; }
    }
    private LocalDate date(String raw, String format) {
        try {
            // OOXML typed dates are normalised to ISO independent of text-column format.
            if (raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(raw);
            return LocalDate.parse(raw, DateTimeFormatter.ofPattern(format.replace("yyyy", "uuuu")).withResolverStyle(ResolverStyle.STRICT));
        } catch (DateTimeException e) { throw new FieldError("invalid-date", "date"); }
    }
    private ZonedDateTime timestamp(LocalDate date, String raw, String timezone, String field) {
        try {
            LocalTime t = LocalTime.parse(raw, DateTimeFormatter.ISO_LOCAL_TIME);
            var local = date.atTime(t); var zone = ZoneId.of(timezone); var offsets = zone.getRules().getValidOffsets(local);
            if (offsets.size() != 1) throw new FieldError("ambiguous-or-invalid-local-time", field);
            return ZonedDateTime.ofStrict(local, offsets.getFirst(), zone);
        } catch (DateTimeException e) { throw new FieldError("invalid-time", field); }
    }
    private String window(TourTableReader.Row row, Mapping m, String field) {
        String raw = get(row, m, field); if (raw.isBlank()) return "";
        try { return LocalTime.parse(raw).toString(); } catch (DateTimeException e) { throw new FieldError("invalid-window-time", field); }
    }
    private static String get(TourTableReader.Row row, Mapping m, String f) { Integer c = m.columns().get(f); String v=c == null ? "" : value(row, c).strip();
        if(Set.of("arrival","departure","windowStart","windowEnd").contains(f)&&!v.isBlank()){
            if(m.timeFormat().equals("HH.mm"))v=v.replace('.',':');
            if(m.timeFormat().equals("HHmm")&&v.matches("[0-9]{4}"))v=v.substring(0,2)+":"+v.substring(2);
        }return v; }
    private static String value(TourTableReader.Row row, int c) { return de.synthesis.data.importing.ImportSupport.value(row,c); }
    private static String normal(String s) { return Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD).replaceAll("[^a-z0-9]", ""); }
    private static String address(String s) { return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    private static String token(String s) { return s.isBlank() ? "" : "ref-" + hash(s).substring(0, 20); }
    public static String hash(String s) { return hash(s.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] bytes) {
        return de.synthesis.data.importing.ImportSupport.hash(bytes);
    }
    private static String type(String s) {
        if (s.isBlank()) return "blank";
        if (s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return "isoDate";
        if (s.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")) return "germanDate";
        if (s.matches("[0-9]{2}:[0-9]{2}(:[0-9]{2})?")) return "time";
        if (s.matches("[0-9]{8}")) return "eightDigits";
        if (s.matches("-?[0-9]+([.,][0-9]+)?")) return "numeric";
        return "text";
    }
}
