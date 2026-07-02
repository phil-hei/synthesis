package de.synthesis.generation.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates tour-based weekly activity schedules per person
 * (activity.csv: personId;activityType;observedTripDuration;startTime;duration).
 * Times are minutes since Monday 00:00; durations in minutes. Activity types are
 * written as numeric mobiTopp codes (LegacyActivityType), validated against the
 * mobiTopp-tutorial; see docs/05-validierung.md.
 *
 * <p>A day is a list of tours; a tour is a chain of out-of-home activities
 * (HOME → a1 → a2 → HOME). Chained stops and a separate evening tour raise the
 * trips/person/day toward the MiD-2017 reference (~3.1) instead of the single
 * out-and-back the first template produced.
 */
public class ActivityGenerator {

    private static final int DAY = 1440;
    private static final int TRIP = 20;
    private static final int DAY_MARGIN = 40; // letzte Heimkehr muss vor Mitternacht liegen

    private final Random random;

    public ActivityGenerator(Random random) {
        this.random = random;
    }

    /** One out-of-home activity with its dwell time in minutes. */
    private record Act(String type, int duration) {
    }

    /** Numeric activity codes as defined by mobiTopp's LegacyActivityType. */
    static int code(String type, int age) {
        return switch (type) {
            case "WORK" -> 1;
            case "EDUCATION" -> age < 11 ? 31 : age < 19 ? 32 : 33;
            case "SERVICE" -> 6;
            case "HOME" -> 7;
            case "PRIVATE_VISIT" -> 12;
            case "SHOPPING_DAILY" -> 41;
            case "SHOPPING_OTHER" -> 42;
            case "LEISURE_INDOOR" -> 51;
            case "LEISURE_OUTDOOR" -> 52;
            default -> throw new IllegalArgumentException("Unbekannter Aktivitätstyp: " + type);
        };
    }

    public long generate(Path file, List<PopulationGenerator.PersonRef> persons) throws IOException {
        Files.createDirectories(file.getParent());
        long activityCount = 0;
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("personId;activityType;observedTripDuration;startTime;duration\n");
            for (PopulationGenerator.PersonRef person : persons) {
                activityCount += writeWeek(w, person);
            }
        }
        return activityCount;
    }

    /**
     * Writes activity.csv from a KIT actiTopp schedule
     * (personId;activityType;startMinute;durationMinute). actiTopp's calibrated
     * activity types are mapped to the legacy LegacyActivityType codes; persons
     * without an actiTopp schedule (infants) get a home-only week. The tour
     * structure itself is derived later by the legacy export from the HOME / out-of-
     * home sequence (see LegacyExportGenerator).
     */
    public long generateFromActitopp(Path scheduleCsv, List<PopulationGenerator.PersonRef> persons,
                                     Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Map<Long, List<String[]>> schedule = readSchedule(scheduleCsv);
        long activityCount = 0;
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("personId;activityType;observedTripDuration;startTime;duration\n");
            for (PopulationGenerator.PersonRef person : persons) {
                List<String[]> rows = schedule.get(person.id());
                if (rows == null || rows.isEmpty()) {
                    // actiTopp plant keine Kleinkinder (age<6) → begleitete Wege; sonst Heimwoche
                    for (int day = 0; day < 7; day++) {
                        if (person.age() < 6) {
                            activityCount += scheduleDay(w, person, day * DAY,
                                    youngChildDay(day < 5), day < 5 ? 480 : 540);
                        } else {
                            activityCount += activity(w, person, "HOME", day * DAY, DAY);
                        }
                    }
                    continue;
                }
                for (String[] row : rows) {
                    int start = Integer.parseInt(row[1]);
                    int duration = Integer.parseInt(row[2]);
                    activityCount += activity(w, person, mapActitoppType(row[0], duration), start, duration);
                }
            }
        }
        return activityCount;
    }

    /** One out-of-home activity from a generative (GTA/LLM) plan; HOME is implicit. */
    public record GtaActivity(String type, int durationMinutes) {
    }

    /** A person's weekly behaviour as tours (lists of activities) for weekdays and weekends. */
    public record GtaWeek(List<List<GtaActivity>> weekdayTours, List<List<GtaActivity>> weekendTours) {
    }

    /**
     * Writes activity.csv from generative (GTA/LLM) plans. The LLM supplies only the
     * out-of-home tours and durations (the behavioural substance); the proven
     * {@link #scheduleDay} lays them out into a valid gapless week (HOME weaving, jitter,
     * before-midnight fitting), so per-person timing varies even for shared personas.
     * Persons without a plan (young children / not covered by the persona budget) get the
     * same fallbacks as the actiTopp path.
     */
    public long generateFromGta(Path file, List<PopulationGenerator.PersonRef> persons,
                                java.util.function.Function<PopulationGenerator.PersonRef, GtaWeek> planFor)
            throws IOException {
        Files.createDirectories(file.getParent());
        long activityCount = 0;
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("personId;activityType;observedTripDuration;startTime;duration\n");
            for (PopulationGenerator.PersonRef person : persons) {
                GtaWeek plan = planFor.apply(person);
                if (plan == null) {
                    for (int day = 0; day < 7; day++) {
                        if (person.age() < 6) {
                            activityCount += scheduleDay(w, person, day * DAY,
                                    youngChildDay(day < 5), day < 5 ? 480 : 540);
                        } else {
                            activityCount += activity(w, person, "HOME", day * DAY, DAY);
                        }
                    }
                    continue;
                }
                for (int day = 0; day < 7; day++) {
                    boolean weekday = day < 5;
                    List<List<Act>> tours = toTours(
                            weekday ? plan.weekdayTours() : plan.weekendTours());
                    activityCount += scheduleDay(w, person, day * DAY, tours, weekday ? 450 : 540);
                }
            }
        }
        return activityCount;
    }

    /** GTA tours → internal Act tours: map types, clamp durations, drop implicit HOME. */
    private List<List<Act>> toTours(List<List<GtaActivity>> gtaTours) {
        if (gtaTours == null) {
            return List.of();
        }
        List<List<Act>> tours = new ArrayList<>();
        for (List<GtaActivity> gtaTour : gtaTours) {
            if (gtaTour == null || gtaTour.isEmpty()) {
                continue;
            }
            List<Act> tour = new ArrayList<>();
            for (GtaActivity ga : gtaTour) {
                if (ga == null || ga.type() == null) {
                    continue;
                }
                int dur = Math.max(10, Math.min(600, ga.durationMinutes()));
                String type = mapActitoppType(ga.type().trim().toUpperCase(), dur);
                if ("HOME".equals(type)) {
                    continue; // Zuhause ist implizit – scheduleDay webt es ein
                }
                tour.add(new Act(type, dur));
            }
            if (!tour.isEmpty()) {
                tours.add(tour);
            }
        }
        return tours;
    }

    private static Map<Long, List<String[]>> readSchedule(Path scheduleCsv) throws IOException {
        Map<Long, List<String[]>> schedule = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(scheduleCsv, StandardCharsets.UTF_8)) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                // personId;activityType;startMinute;durationMinute
                String[] c = line.split(";", -1);
                schedule.computeIfAbsent(Long.parseLong(c[0]), k -> new ArrayList<>())
                        .add(new String[]{c[1], c[2], c[3]});
            }
        }
        return schedule;
    }

    /** actiTopp ActivityType → SyntheSis/LegacyActivityType category. */
    private static String mapActitoppType(String actitoppType, int durationMinutes) {
        return switch (actitoppType) {
            case "WORK" -> "WORK";
            case "EDUCATION" -> "EDUCATION";
            case "SHOPPING" -> durationMinutes < 60 ? "SHOPPING_DAILY" : "SHOPPING_OTHER";
            case "LEISURE" -> "LEISURE_OUTDOOR";
            case "TRANSPORT" -> "SERVICE"; // Begleit-/Bringwege
            default -> "HOME";
        };
    }

    private int writeWeek(Writer w, PopulationGenerator.PersonRef person) throws IOException {
        int count = 0;
        for (int day = 0; day < 7; day++) {
            int t = day * DAY;
            boolean weekday = day < 5;
            List<List<Act>> tours = switch (person.employment()) {
                case "FULLTIME" -> weekday ? workerDay(person, "WORK", 480) : weekendDay();
                case "PARTTIME" -> weekday ? workerDay(person, "WORK", 300) : weekendDay();
                case "EDUCATION" -> weekday ? workerDay(person, "EDUCATION", 360) : weekendDay();
                // Studierende: Freitag (Index 4) frei wie ein Wochenendtag
                case "STUDENT" -> (weekday && day != 4) ? workerDay(person, "EDUCATION", 420) : weekendDay();
                case "INFANT" -> youngChildDay(weekday);
                default -> weekday ? flexibleDay() : weekendDay();
            };
            count += scheduleDay(w, person, t, tours, weekday ? 450 : 540);
        }
        return count;
    }

    /**
     * Young child (age &lt; 6, no actiTopp plan): escorted trips — day-care on most
     * weekdays plus occasional accompanied leisure/shopping. Lifts the otherwise zero
     * trip rate of toddlers toward the MiD reference for the 0–17 age group.
     */
    private List<List<Act>> youngChildDay(boolean weekday) {
        List<List<Act>> tours = new ArrayList<>();
        if (weekday && random.nextDouble() < 0.6) {
            tours.add(List.of(new Act("EDUCATION", 240 + random.nextInt(120)))); // Kita
        }
        if (random.nextDouble() < (weekday ? 0.3 : 0.45)) {
            tours.add(List.of(random.nextDouble() < 0.5
                    ? new Act("LEISURE_OUTDOOR", 90) : new Act("SHOPPING_DAILY", 40))); // Begleitweg
        }
        return tours;
    }

    /**
     * Worker/pupil day: a main tour (work/education, often with an errand chained on
     * the way home) plus — most days — a separate evening leisure tour.
     */
    private List<List<Act>> workerDay(PopulationGenerator.PersonRef person, String mainType, int mainDuration) {
        List<List<Act>> tours = new ArrayList<>();
        List<Act> main = new ArrayList<>();
        main.add(new Act(mainType, mainDuration));
        if (random.nextDouble() < 0.55) {
            main.add(errand());
        }
        tours.add(main);
        if (random.nextDouble() < 0.6) {
            tours.add(List.of(eveningActivity()));
        }
        return tours;
    }

    /**
     * Non-employed adult on a weekday: errands and leisure on most days, with a small
     * share of pure home days and frequent second tours.
     */
    private List<List<Act>> flexibleDay() {
        if (random.nextDouble() < 0.10) {
            return List.of();
        }
        List<List<Act>> tours = new ArrayList<>();
        List<Act> first = new ArrayList<>();
        first.add(daytimeActivity());
        if (random.nextDouble() < 0.45) {
            first.add(errand());
        }
        tours.add(first);
        if (random.nextDouble() < 0.5) {
            tours.add(List.of(eveningActivity()));
        }
        return tours;
    }

    private List<List<Act>> weekendDay() {
        if (random.nextDouble() < 0.25) {
            return List.of();
        }
        List<List<Act>> tours = new ArrayList<>();
        List<Act> first = new ArrayList<>();
        first.add(weekendActivity());
        if (random.nextDouble() < 0.35) {
            first.add(errand());
        }
        tours.add(first);
        if (random.nextDouble() < 0.4) {
            tours.add(List.of(eveningActivity()));
        }
        return tours;
    }

    // --- Aktivitätsauswahl ----------------------------------------------------

    private Act errand() {
        return random.nextDouble() < 0.6
                ? new Act("SHOPPING_DAILY", 30)
                : new Act("SERVICE", 40);
    }

    private Act daytimeActivity() {
        double roll = random.nextDouble();
        if (roll < 0.4) {
            return new Act("SHOPPING_DAILY", 45);
        }
        if (roll < 0.7) {
            return new Act("LEISURE_OUTDOOR", 90);
        }
        return new Act("SERVICE", 60);
    }

    private Act eveningActivity() {
        double roll = random.nextDouble();
        if (roll < 0.45) {
            return new Act("LEISURE_INDOOR", 110);
        }
        if (roll < 0.75) {
            return new Act("PRIVATE_VISIT", 130);
        }
        return new Act("SHOPPING_DAILY", 40);
    }

    private Act weekendActivity() {
        double roll = random.nextDouble();
        if (roll < 0.4) {
            return new Act("SHOPPING_OTHER", 100);
        }
        if (roll < 0.75) {
            return new Act("LEISURE_OUTDOOR", 150);
        }
        return new Act("PRIVATE_VISIT", 160);
    }

    // --- Zeitliche Anordnung --------------------------------------------------

    /**
     * Lays out a day's tours sequentially from {@code firstStart} (jittered): a leading
     * HOME stay, each tour's chained activities, a HOME gap between tours, and a trailing
     * HOME stay. Activities that no longer fit before midnight are dropped so the day
     * always ends at home (required by the legacy tour-pattern parser).
     */
    private int scheduleDay(Writer w, PopulationGenerator.PersonRef person, int t,
                            List<List<Act>> tours, int firstStart) throws IOException {
        List<List<Act>> nonEmpty = tours.stream().filter(tour -> !tour.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            return activity(w, person, "HOME", t, DAY);
        }
        int count = 0;
        int cursor = firstStart + random.nextInt(60) - 30; // ± 30 min Jitter
        cursor = Math.max(TRIP + 30, cursor);
        count += activity(w, person, "HOME", t, cursor - TRIP);

        for (int ti = 0; ti < nonEmpty.size(); ti++) {
            for (Act act : nonEmpty.get(ti)) {
                if (cursor + act.duration + TRIP > DAY - DAY_MARGIN) {
                    return finishAtHome(w, person, t, cursor, count);
                }
                count += activity(w, person, act.type, t + cursor, act.duration);
                cursor += act.duration + TRIP;
            }
            if (ti < nonEmpty.size() - 1) {
                int gap = 60 + random.nextInt(120); // Heimaufenthalt zwischen Touren
                if (cursor + gap + TRIP > DAY - DAY_MARGIN) {
                    return finishAtHome(w, person, t, cursor, count);
                }
                count += activity(w, person, "HOME", t + cursor, gap);
                cursor += gap + TRIP;
            }
        }
        return finishAtHome(w, person, t, cursor, count);
    }

    private int finishAtHome(Writer w, PopulationGenerator.PersonRef person, int t, int cursor, int count)
            throws IOException {
        return count + activity(w, person, "HOME", t + cursor, DAY - cursor);
    }

    private int activity(Writer w, PopulationGenerator.PersonRef person, String type,
                         int start, int duration) throws IOException {
        if (duration <= 0) {
            return 0;
        }
        w.write(person.id() + ";" + code(type, person.age()) + ";" + TRIP + ";" + start + ";" + duration + "\n");
        return 1;
    }
}
