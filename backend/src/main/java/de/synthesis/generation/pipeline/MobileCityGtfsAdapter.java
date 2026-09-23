package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

/** Converts a cropped GTFS feed into the abstract, undirected MobileCity transit network. */
public final class MobileCityGtfsAdapter {
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    /** MobileCity's transit handler models a 16-hour day and applies no wait penalty at this value. */
    static final int WALK_FREQUENCY = 138;

    public record Feed(Path path, String name, String source, GtfsCropper.Result crop) {}
    record District(String id, double lat, double lon, Geometry geometry) {
        District(String id, double lat, double lon) { this(id, lat, lon, null); }
    }
    record Node(String id, double lat, double lon, String districtId, int refNodeTrue,
                int nodeType, int nodeActive, String name) {}
    record Link(String fromNodeId, String toNodeId, double distanceKm, double timeMinutes,
                double averageSpeedKmh, int infrastructureType, int mode, int frequency,
                int active, String name) {}
    record Result(List<Node> nodes, List<Link> links, LocalDate serviceDate,
                  LocalDate feedStartDate, LocalDate feedEndDate, String feedSha256,
                  int scheduledTrips, int serviceStops, int serviceLinks, int transferLinks,
                  int accessLinks, int peakBusVehicles, int peakTrainVehicles,
                  Map<Integer, Integer> tripsByRouteType) {
        Map<String, Object> provenance(Feed feed) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", 1);
            result.put("source", feed.source());
            result.put("feedName", feed.name());
            result.put("feedSha256", feedSha256);
            result.put("feedPeriod", Map.of("start", feedStartDate.toString(), "end", feedEndDate.toString()));
            result.put("serviceDate", serviceDate.toString());
            result.put("demandReferenceYear", null);
            result.put("temporalPolicy", "Representative Wednesday with the largest active scheduled trip count; the GTFS service date remains independent of the scenario demand year");
            result.put("frequencyPolicy", "Maximum directional departures per work day on each undirected link; MobileCity interprets freq over a 16-hour service day");
            result.put("accessPolicy", "One reference node at an interior point of each district, connected by walking to the nearest stop in the largest service component");
            result.put("transferPolicy", "GTFS transfers and district access links use mode/infrastructure 9 and frequency 138 to avoid an artificial transit wait penalty");
            result.put("routeTypeMapping", "GTFS route_type to established MobileCity Network Pipeline codes (rail 1/2, tram 3, bus 4, water 5, taxi 6)");
            result.put("scheduledTrips", scheduledTrips);
            result.put("serviceStops", serviceStops);
            result.put("serviceLinks", serviceLinks);
            result.put("transferLinks", transferLinks);
            result.put("accessLinks", accessLinks);
            result.put("peakVehicles", Map.of("bus", peakBusVehicles, "trainAndTram", peakTrainVehicles));
            result.put("tripsByRouteType", tripsByRouteType);
            if (feed.crop() != null) result.put("crop", Map.of(
                    "stopsInRegion", feed.crop().stopsInBbox(), "trips", feed.crop().trips(),
                    "stops", feed.crop().stops(), "routes", feed.crop().routes()));
            return result;
        }
    }

    Result convert(Feed feed, List<District> districts) throws IOException {
        if (feed == null || feed.path() == null || districts.isEmpty()) {
            throw new IOException("GTFS-Adapter benötigt Feed und mindestens einen District");
        }
        try (ZipFile zip = new ZipFile(feed.path().toFile())) {
            Map<String, RawStop> rawStops = readStops(zip);
            Map<String, Station> stations = buildStations(rawStops);
            Map<String, Route> routes = readRoutes(zip);
            Map<String, Trip> trips = readTrips(zip, routes);
            List<CalendarRule> calendar = readCalendar(zip);
            Map<LocalDate, Map<String, Integer>> exceptions = readCalendarDates(zip);
            Map<String, List<Frequency>> frequencies = readFrequencies(zip);
            LocalDate[] period = feedPeriod(zip, calendar, exceptions);
            LocalDate serviceDate = representativeWednesday(period[0], period[1], calendar,
                    exceptions, trips.values(), frequencies);
            Set<String> activeServices = activeServices(serviceDate, calendar, exceptions);
            Map<String, List<StopTime>> stopTimes = readStopTimes(zip, trips, activeServices,
                    rawStops, stations);

            Map<DirectedPair, LinkAccumulator> directed = new LinkedHashMap<>();
            List<Event> busEvents = new ArrayList<>(), trainEvents = new ArrayList<>();
            Map<Integer, Integer> typeCounts = new TreeMap<>();
            int scheduledTrips = 0;
            for (var entry : stopTimes.entrySet()) {
                Trip trip = trips.get(entry.getKey());
                List<StopTime> times = entry.getValue();
                if (trip == null || times.size() < 2) continue;
                times.sort(Comparator.comparingInt(StopTime::sequence));
                int weight = departureCount(frequencies.get(trip.id()));
                if (weight <= 0) weight = 1;
                scheduledTrips += weight;
                typeCounts.merge(trip.route().type(), weight, Integer::sum);
                addVehicleEvents(trip, times, frequencies.get(trip.id()), busEvents, trainEvents);
                Set<DirectedPair> seenOnTrip = new HashSet<>();
                for (int i = 0; i + 1 < times.size(); i++) {
                    StopTime from = times.get(i), to = times.get(i + 1);
                    if (from.stationId().equals(to.stationId())) continue;
                    int depart = from.departure() >= 0 ? from.departure() : from.arrival();
                    int arrive = to.arrival() >= 0 ? to.arrival() : to.departure();
                    if (depart < 0 || arrive <= depart) continue;
                    DirectedPair pair = new DirectedPair(from.stationId(), to.stationId());
                    if (!seenOnTrip.add(pair)) continue;
                    Station a = stations.get(from.stationId()), b = stations.get(to.stationId());
                    if (a == null || b == null) continue;
                    directed.computeIfAbsent(pair, ignored -> new LinkAccumulator(a, b))
                            .add((arrive - depart) / 60d, trip.route(), weight);
                }
            }
            if (directed.isEmpty()) {
                throw new IOException("GTFS enthält am repräsentativen Werktag keine nutzbaren Haltestellenfolgen");
            }

            Map<UndirectedPair, CombinedLink> combined = combineDirections(directed);
            addTransfers(zip, rawStops, stations, combined);
            Set<String> largest = largestComponent(combined.values());
            if (largest.size() < 2) throw new IOException("GTFS-Angebot bildet kein verbundenes Haltestellennetz");

            List<Node> nodes = new ArrayList<>();
            List<Link> links = new ArrayList<>();
            Map<String, Station> keptStations = new TreeMap<>();
            for (String id : largest) keptStations.put(id, stations.get(id));
            for (Station station : keptStations.values()) {
                District district = nearestDistrict(station.lat(), station.lon(), districts);
                nodes.add(new Node(nodeId(station.id()), station.lat(), station.lon(), district.id(),
                        0, 0, 1, station.name()));
            }
            int keptServiceLinks = 0, keptTransferLinks = 0;
            for (var entry : combined.entrySet()) {
                if (!largest.contains(entry.getKey().a()) || !largest.contains(entry.getKey().b())) continue;
                links.add(entry.getValue().toLink());
                if (entry.getValue().walk) keptTransferLinks++; else keptServiceLinks++;
            }
            int accessLinks = 0;
            for (District district : districts.stream().sorted(Comparator.comparing(District::id)).toList()) {
                Station nearest = keptStations.values().stream().min(Comparator
                        .comparingDouble(s -> haversineKm(district.lat(), district.lon(), s.lat(), s.lon())))
                        .orElseThrow();
                String refId = "MC-REF-" + district.id();
                nodes.add(new Node(refId, district.lat(), district.lon(), district.id(), 1, 0, 1,
                        "District access " + district.id()));
                double distance = haversineKm(district.lat(), district.lon(), nearest.lat(), nearest.lon());
                double minutes = Math.max(.1, distance / 4.8 * 60);
                links.add(new Link(refId, nodeId(nearest.id()), distance, minutes,
                        distance == 0 ? 0 : distance / minutes * 60, 9, 9, WALK_FREQUENCY, 1,
                        "District access"));
                accessLinks++;
            }
            nodes.sort(Comparator.comparing(Node::id));
            links.sort(Comparator.comparing(Link::fromNodeId).thenComparing(Link::toNodeId));
            int keptServiceStops = largest.size();
            return new Result(List.copyOf(nodes), List.copyOf(links), serviceDate, period[0], period[1],
                    CensusStructureSource.sha256(feed.path()), scheduledTrips, keptServiceStops,
                    keptServiceLinks, keptTransferLinks, accessLinks, peak(busEvents), peak(trainEvents),
                    Collections.unmodifiableMap(typeCounts));
        }
    }

    private static Map<String, RawStop> readStops(ZipFile zip) throws IOException {
        Map<String, RawStop> result = new LinkedHashMap<>();
        for (Map<String, String> row : table(zip, "stops.txt", true)) {
            String id = row.getOrDefault("stop_id", "").trim();
            if (id.isEmpty()) continue;
            Double lat = number(row.get("stop_lat")), lon = number(row.get("stop_lon"));
            result.put(id, new RawStop(id, row.getOrDefault("parent_station", "").trim(), lat, lon,
                    first(row.get("stop_name"), id)));
        }
        return result;
    }

    private static Map<String, Station> buildStations(Map<String, RawStop> raw) {
        Map<String, StationAccumulator> grouped = new LinkedHashMap<>();
        for (RawStop stop : raw.values()) {
            String canonical = stop.parent().isBlank() ? stop.id() : stop.parent();
            RawStop parent = raw.get(canonical);
            Double lat = parent != null && parent.lat() != null ? parent.lat() : stop.lat();
            Double lon = parent != null && parent.lon() != null ? parent.lon() : stop.lon();
            String name = parent == null ? stop.name() : parent.name();
            if (lat != null && lon != null) grouped.computeIfAbsent(canonical,
                    ignored -> new StationAccumulator(canonical, name)).add(lat, lon);
        }
        Map<String, Station> result = new LinkedHashMap<>();
        grouped.forEach((id, value) -> result.put(id, value.station()));
        return result;
    }

    private static Map<String, Route> readRoutes(ZipFile zip) throws IOException {
        Map<String, Route> result = new LinkedHashMap<>();
        for (Map<String, String> row : table(zip, "routes.txt", true)) {
            String id = row.getOrDefault("route_id", "").trim();
            if (id.isEmpty()) continue;
            int type = integer(row.get("route_type"), 3);
            result.put(id, new Route(id, type,
                    first(row.get("route_short_name"), row.get("route_long_name"), id)));
        }
        return result;
    }

    private static Map<String, Trip> readTrips(ZipFile zip, Map<String, Route> routes) throws IOException {
        Map<String, Trip> result = new LinkedHashMap<>();
        for (Map<String, String> row : table(zip, "trips.txt", true)) {
            Route route = routes.get(row.get("route_id"));
            String id = row.getOrDefault("trip_id", "").trim();
            String service = row.getOrDefault("service_id", "").trim();
            if (route != null && !id.isEmpty() && !service.isEmpty()) result.put(id, new Trip(id, service, route));
        }
        return result;
    }

    private static List<CalendarRule> readCalendar(ZipFile zip) throws IOException {
        List<CalendarRule> result = new ArrayList<>();
        for (Map<String, String> row : table(zip, "calendar.txt", false)) {
            LocalDate start = date(row.get("start_date")), end = date(row.get("end_date"));
            if (start == null || end == null) continue;
            boolean[] days = new boolean[7];
            String[] names = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            for (int i = 0; i < names.length; i++) days[i] = "1".equals(row.get(names[i]));
            result.add(new CalendarRule(row.getOrDefault("service_id", ""), start, end, days));
        }
        return result;
    }

    private static Map<LocalDate, Map<String, Integer>> readCalendarDates(ZipFile zip) throws IOException {
        Map<LocalDate, Map<String, Integer>> result = new TreeMap<>();
        for (Map<String, String> row : table(zip, "calendar_dates.txt", false)) {
            LocalDate day = date(row.get("date"));
            if (day != null) result.computeIfAbsent(day, ignored -> new LinkedHashMap<>())
                    .put(row.getOrDefault("service_id", ""), integer(row.get("exception_type"), 0));
        }
        return result;
    }

    private static LocalDate[] feedPeriod(ZipFile zip, List<CalendarRule> calendar,
                                           Map<LocalDate, Map<String, Integer>> exceptions) throws IOException {
        LocalDate start = calendar.stream().map(CalendarRule::start).min(LocalDate::compareTo).orElse(null);
        LocalDate end = calendar.stream().map(CalendarRule::end).max(LocalDate::compareTo).orElse(null);
        for (Map<String, String> row : table(zip, "feed_info.txt", false)) {
            LocalDate infoStart = date(row.get("feed_start_date")), infoEnd = date(row.get("feed_end_date"));
            if (start == null) start = infoStart;
            if (end == null) end = infoEnd;
        }
        if (!exceptions.isEmpty()) {
            if (start == null) start = exceptions.keySet().stream().min(LocalDate::compareTo).orElse(null);
            if (end == null) end = exceptions.keySet().stream().max(LocalDate::compareTo).orElse(null);
        }
        if (start == null || end == null || end.isBefore(start)) throw new IOException("GTFS-Gültigkeitszeitraum fehlt");
        return new LocalDate[]{start, end};
    }

    private static LocalDate representativeWednesday(LocalDate start, LocalDate end,
                                                       List<CalendarRule> calendar,
                                                       Map<LocalDate, Map<String, Integer>> exceptions,
                                                       Collection<Trip> trips,
                                                       Map<String, List<Frequency>> frequencies) throws IOException {
        Map<String, Integer> tripsPerService = new HashMap<>();
        for (Trip trip : trips) tripsPerService.merge(trip.serviceId(),
                Math.max(1, departureCount(frequencies.get(trip.id()))), Integer::sum);
        List<Map.Entry<LocalDate, Integer>> candidates = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.WEDNESDAY) continue;
            int count = activeServices(day, calendar, exceptions).stream()
                    .mapToInt(s -> tripsPerService.getOrDefault(s, 0)).sum();
            if (count > 0) candidates.add(Map.entry(day, count));
        }
        if (candidates.isEmpty()) {
            for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
                if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
                int count = activeServices(day, calendar, exceptions).stream()
                        .mapToInt(s -> tripsPerService.getOrDefault(s, 0)).sum();
                if (count > 0) candidates.add(Map.entry(day, count));
            }
        }
        if (candidates.isEmpty()) throw new IOException("GTFS-Kalender enthält keinen aktiven Werktag");
        int max = candidates.stream().mapToInt(Map.Entry::getValue).max().orElseThrow();
        List<LocalDate> best = candidates.stream().filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey).toList();
        return best.get(best.size() / 2);
    }

    private static Set<String> activeServices(LocalDate day, List<CalendarRule> calendar,
                                               Map<LocalDate, Map<String, Integer>> exceptions) {
        Set<String> result = new LinkedHashSet<>();
        for (CalendarRule rule : calendar) if (!day.isBefore(rule.start()) && !day.isAfter(rule.end())
                && rule.days()[day.getDayOfWeek().getValue() - 1]) result.add(rule.serviceId());
        for (var exception : exceptions.getOrDefault(day, Map.of()).entrySet()) {
            if (exception.getValue() == 1) result.add(exception.getKey());
            else if (exception.getValue() == 2) result.remove(exception.getKey());
        }
        return result;
    }

    private static Map<String, List<Frequency>> readFrequencies(ZipFile zip) throws IOException {
        Map<String, List<Frequency>> result = new HashMap<>();
        for (Map<String, String> row : table(zip, "frequencies.txt", false)) {
            int start = seconds(row.get("start_time")), end = seconds(row.get("end_time"));
            int headway = integer(row.get("headway_secs"), 0);
            if (start >= 0 && end > start && headway > 0) result.computeIfAbsent(row.get("trip_id"), ignored -> new ArrayList<>())
                    .add(new Frequency(start, end, headway));
        }
        return result;
    }

    private static Map<String, List<StopTime>> readStopTimes(ZipFile zip, Map<String, Trip> trips,
                                                             Set<String> services,
                                                             Map<String, RawStop> rawStops,
                                                             Map<String, Station> stations) throws IOException {
        Map<String, List<StopTime>> result = new LinkedHashMap<>();
        for (Map<String, String> row : table(zip, "stop_times.txt", true)) {
            Trip trip = trips.get(row.get("trip_id"));
            if (trip == null || !services.contains(trip.serviceId())) continue;
            RawStop raw = rawStops.get(row.get("stop_id"));
            if (raw == null) continue;
            String station = raw.parent().isBlank() ? raw.id() : raw.parent();
            if (!stations.containsKey(station)) continue;
            result.computeIfAbsent(trip.id(), ignored -> new ArrayList<>()).add(new StopTime(station,
                    integer(row.get("stop_sequence"), Integer.MAX_VALUE), seconds(row.get("arrival_time")),
                    seconds(row.get("departure_time"))));
        }
        return result;
    }

    private static int addTransfers(ZipFile zip, Map<String, RawStop> rawStops,
                                    Map<String, Station> stations,
                                    Map<UndirectedPair, CombinedLink> links) throws IOException {
        int added = 0;
        for (Map<String, String> row : table(zip, "transfers.txt", false)) {
            if ("3".equals(row.get("transfer_type"))) continue;
            RawStop rawA = rawStops.get(row.get("from_stop_id")), rawB = rawStops.get(row.get("to_stop_id"));
            if (rawA == null || rawB == null) continue;
            String a = rawA.parent().isBlank() ? rawA.id() : rawA.parent();
            String b = rawB.parent().isBlank() ? rawB.id() : rawB.parent();
            if (a.equals(b) || !stations.containsKey(a) || !stations.containsKey(b)) continue;
            UndirectedPair pair = UndirectedPair.of(a, b);
            Station from = stations.get(pair.a()), to = stations.get(pair.b());
            double distance = haversineKm(from.lat(), from.lon(), to.lat(), to.lon());
            int seconds = integer(row.get("min_transfer_time"), 0);
            double minutes = seconds > 0 ? seconds / 60d : Math.max(.1, distance / 4.8 * 60);
            CombinedLink existing = links.get(pair);
            if (existing != null && !existing.walk) continue;
            if (existing == null) {
                links.put(pair, CombinedLink.walk(from, to, minutes, "GTFS transfer"));
                added++;
            } else if (minutes < existing.weightedMinutes) {
                links.put(pair, CombinedLink.walk(from, to, minutes, "GTFS transfer"));
            }
        }
        return added;
    }

    private static Map<UndirectedPair, CombinedLink> combineDirections(
            Map<DirectedPair, LinkAccumulator> directed) {
        Map<UndirectedPair, CombinedLink> result = new LinkedHashMap<>();
        for (var entry : directed.entrySet()) result.computeIfAbsent(
                UndirectedPair.of(entry.getKey().from(), entry.getKey().to()),
                ignored -> new CombinedLink(entry.getValue().from, entry.getValue().to))
                .addDirection(entry.getValue());
        return result;
    }

    private static Set<String> largestComponent(Collection<CombinedLink> links) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (CombinedLink link : links) {
            adjacency.computeIfAbsent(link.from.id(), ignored -> new LinkedHashSet<>()).add(link.to.id());
            adjacency.computeIfAbsent(link.to.id(), ignored -> new LinkedHashSet<>()).add(link.from.id());
        }
        Set<String> best = Set.of(), visited = new HashSet<>();
        for (String start : new TreeSet<>(adjacency.keySet())) {
            if (!visited.add(start)) continue;
            Set<String> component = new TreeSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.remove(); component.add(current);
                for (String next : adjacency.getOrDefault(current, Set.of())) if (visited.add(next)) queue.add(next);
            }
            if (component.size() > best.size()) best = component;
        }
        return best;
    }

    private static void addVehicleEvents(Trip trip, List<StopTime> times, List<Frequency> frequencies,
                                         List<Event> bus, List<Event> train) {
        int first = times.stream().mapToInt(t -> t.departure() >= 0 ? t.departure() : t.arrival()).filter(t -> t >= 0).min().orElse(-1);
        int last = times.stream().mapToInt(t -> t.arrival() >= 0 ? t.arrival() : t.departure()).filter(t -> t >= 0).max().orElse(-1);
        if (first < 0 || last <= first) return;
        List<Event> target = trainMode(mode(trip.route().type())[0]) ? train : bus;
        if (frequencies == null || frequencies.isEmpty()) {
            target.add(new Event(first, 1)); target.add(new Event(last, -1)); return;
        }
        int duration = last - first;
        for (Frequency frequency : frequencies) for (int start = frequency.start(); start < frequency.end(); start += frequency.headway()) {
            target.add(new Event(start, 1)); target.add(new Event(start + duration, -1));
        }
    }

    private static int departureCount(List<Frequency> frequencies) {
        if (frequencies == null || frequencies.isEmpty()) return 1;
        int result = 0;
        for (Frequency frequency : frequencies) result += (frequency.end() - frequency.start() + frequency.headway() - 1) / frequency.headway();
        return result;
    }

    private static int peak(List<Event> events) {
        events.sort(Comparator.comparingInt(Event::time).thenComparingInt(Event::delta));
        int current = 0, peak = 0;
        for (Event event : events) { current += event.delta(); peak = Math.max(peak, current); }
        return peak;
    }

    /** Established mapping from the supplied MobileCity Network Pipeline configuration. */
    static int[] mode(int routeType) {
        return switch (routeType) {
            case 0, 1, 900 -> new int[]{3, 3};
            case 2, 101, 102, 103, 106, 201 -> new int[]{1, 1};
            case 109, 300, 400 -> new int[]{2, 2};
            case 3, 700, 704, 715 -> new int[]{4, 4};
            case 4, 1000 -> new int[]{5, 5};
            case 5 -> new int[]{0, 0};
            case 1501 -> new int[]{6, 4};
            default -> routeType >= 100 && routeType < 300 ? new int[]{1, 1}
                    : routeType >= 300 && routeType < 500 ? new int[]{2, 2}
                    : routeType >= 700 && routeType < 800 ? new int[]{4, 4}
                    : routeType >= 900 && routeType < 1000 ? new int[]{3, 3}
                    : routeType >= 1000 && routeType < 1100 ? new int[]{5, 5}
                    : routeType >= 1500 && routeType < 1600 ? new int[]{6, 4}
                    : new int[]{4, 4};
        };
    }

    private static boolean trainMode(int mode) { return mode == 1 || mode == 2 || mode == 3; }
    private static District nearestDistrict(double lat, double lon, List<District> districts) {
        var point = MobileCityGeometry.FACTORY.createPoint(new Coordinate(lon, lat));
        for (District district : districts.stream().sorted(Comparator.comparing(District::id)).toList()) {
            if (district.geometry() != null && district.geometry().covers(point)) return district;
        }
        return districts.stream().min(Comparator.comparingDouble(d -> haversineKm(lat, lon, d.lat(), d.lon()))).orElseThrow();
    }
    private static double haversineKm(double aLat, double aLon, double bLat, double bLon) {
        double p1 = Math.toRadians(aLat), p2 = Math.toRadians(bLat);
        double dp = Math.toRadians(bLat - aLat), dl = Math.toRadians(bLon - aLon);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371.0088 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
    private static String nodeId(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "GTFS-" + HexFormat.of().formatHex(hash, 0, 10);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static List<Map<String, String>> table(ZipFile zip, String name, boolean required) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            if (required) throw new IOException("GTFS-Pflichttabelle fehlt: " + name);
            return List.of();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first == null) return List.of();
            List<String> header = parseCsv(first.replace("\uFEFF", ""));
            List<Map<String, String>> rows = new ArrayList<>(); String line;
            while ((line = reader.readLine()) != null) if (!line.isBlank()) {
                List<String> cells = parseCsv(line); Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
                rows.add(row);
            }
            return rows;
        }
    }
    private static List<String> parseCsv(String line) throws IOException {
        List<String> result = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { result.add(cell.toString()); cell.setLength(0); }
            else cell.append(c);
        }
        if (quoted) throw new IOException("Ungültige GTFS-CSV-Zeile");
        result.add(cell.toString()); return result;
    }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return ""; }
    private static Double number(String value) { try { return value == null || value.isBlank() ? null : Double.parseDouble(value); } catch (NumberFormatException e) { return null; } }
    private static int integer(String value, int fallback) { try { return value == null || value.isBlank() ? fallback : Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; } }
    private static int seconds(String value) {
        if (value == null || value.isBlank()) return -1;
        String[] parts = value.trim().split(":");
        try { return parts.length == 3 ? Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]) : -1; }
        catch (NumberFormatException e) { return -1; }
    }
    private static LocalDate date(String value) {
        try { return value == null || value.isBlank() ? null : LocalDate.parse(value.trim(), GTFS_DATE); }
        catch (DateTimeParseException e) { return null; }
    }

    private record RawStop(String id, String parent, Double lat, Double lon, String name) {}
    private record Station(String id, double lat, double lon, String name) {}
    private static final class StationAccumulator {
        final String id, name; double lat, lon; int count;
        StationAccumulator(String id, String name) { this.id = id; this.name = name; }
        void add(double lat, double lon) { this.lat += lat; this.lon += lon; count++; }
        Station station() { return new Station(id, lat / count, lon / count, name); }
    }
    private record Route(String id, int type, String name) {}
    private record Trip(String id, String serviceId, Route route) {}
    private record CalendarRule(String serviceId, LocalDate start, LocalDate end, boolean[] days) {}
    private record Frequency(int start, int end, int headway) {}
    private record StopTime(String stationId, int sequence, int arrival, int departure) {}
    private record Event(int time, int delta) {}
    private record DirectedPair(String from, String to) {}
    private record UndirectedPair(String a, String b) {
        static UndirectedPair of(String a, String b) { return a.compareTo(b) <= 0 ? new UndirectedPair(a, b) : new UndirectedPair(b, a); }
    }
    private static final class LinkAccumulator {
        final Station from, to; final Map<Integer, Integer> modeWeights = new HashMap<>();
        final Set<String> names = new TreeSet<>(); double weightedMinutes; int frequency;
        LinkAccumulator(Station from, Station to) { this.from = from; this.to = to; }
        void add(double minutes, Route route, int weight) {
            weightedMinutes += minutes * weight; frequency += weight; names.add(route.name());
            modeWeights.merge(route.type(), weight, Integer::sum);
        }
    }
    private static final class CombinedLink {
        final Station from, to; final Map<Integer, Integer> modeWeights = new HashMap<>();
        final Set<String> names = new TreeSet<>(); double weightedMinutes; int timeWeight;
        int frequency; boolean walk;
        CombinedLink(Station from, Station to) { this.from = from; this.to = to; }
        void addDirection(LinkAccumulator link) {
            weightedMinutes += link.weightedMinutes; timeWeight += link.frequency;
            frequency = Math.max(frequency, link.frequency); names.addAll(link.names);
            link.modeWeights.forEach((type, weight) -> modeWeights.merge(type, weight, Integer::sum));
        }
        static CombinedLink walk(Station from, Station to, double minutes, String name) {
            CombinedLink link = new CombinedLink(from, to); link.walk = true; link.frequency = WALK_FREQUENCY;
            link.weightedMinutes = minutes; link.timeWeight = 1; link.names.add(name); return link;
        }
        Link toLink() {
            double distance = haversineKm(from.lat(), from.lon(), to.lat(), to.lon());
            double minutes = Math.max(.1, weightedMinutes / Math.max(1, timeWeight));
            int[] mapping = walk ? new int[]{9, 9} : mode(modeWeights.entrySet().stream()
                    .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey).orElse(3));
            return new Link(nodeId(from.id()), nodeId(to.id()), distance, minutes,
                    distance == 0 ? 0 : distance / minutes * 60, mapping[1], mapping[0],
                    frequency, 1, String.join(", ", names));
        }
    }
}
