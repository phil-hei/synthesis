package de.synthesis.companytour;

import java.util.*;

/** Business identity across files; independent of salted source IDs, vehicle and export target. */
final class CompanyTourRevisions {
    record Key(String companyId, String simulationDate, String sourceTourCode) {}
    private final Map<Key, String> owners = new HashMap<>();

    void include(CompanyTourMapper.Draft draft, CompanyTourExecution.Prepared prepared) {
        Set<String> selected = new HashSet<>();
        prepared.legs().forEach(leg -> selected.add(leg.tourId()));
        for (var tour : draft.tours()) {
            if (!selected.contains(tour.tourId())) continue;
            if (tour.sourceTourCode() == null || tour.sourceTourCode().isBlank())
                throw new IllegalArgumentException("Ausgewählte Unternehmenstour benötigt eine eindeutige Quell-Tourkennung");
            var key = new Key(prepared.companyId(), prepared.simulationDate(), tour.sourceTourCode().strip());
            String previous = owners.putIfAbsent(key, prepared.importId());
            if (previous != null)
                throw new IllegalArgumentException("Planrevision mehrfach ausgewählt: Unternehmen " + key.companyId()
                        + ", Tour " + key.sourceTourCode() + ", Bezugsdatum " + key.simulationDate()
                        + ". Bisherigen Import " + previous.substring(0, Math.min(12, previous.length()))
                        + " zuerst abwählen oder die betroffene Tour dort ausschließen und die Auswahl erneut prüfen.");
        }
    }
}
