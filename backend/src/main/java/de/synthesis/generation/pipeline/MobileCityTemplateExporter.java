package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Experimental template merge. Uncovered districts and all other model modules remain in the baseline. */
final class MobileCityTemplateExporter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<Zone> zones;
    private final Scenario scenario;
    private final Path root;
    MobileCityTemplateExporter(List<Zone> zones, Scenario scenario, Path root) {
        this.zones=zones; this.scenario=scenario; this.root=root;
    }

    int export(Path out, MobileCityPopulation population) throws IOException {
        return export(out,population,null);
    }

    int export(Path out, MobileCityPopulation population, MobileCityFleetSource.Result fleet) throws IOException {
        String id=scenario.mobileCity.templateId;
        if (root==null || id==null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new IOException("Ungültiger MobileCity-Vorlagenpfad");
        Path source=root.resolve(id).toAbsolutePath().normalize();
        Path manifestFile=source.resolve("template-manifest.json");
        if (!Files.isRegularFile(manifestFile)) throw new IOException("MobileCity-Vorlage fehlt: " + id + ". Zuerst scripts/prepare-mobilecity.py ausführen.");
        JsonNode manifest=JSON.readTree(manifestFile.toFile());
        if (manifest.path("schemaVersion").asInt()!=1 || !id.equals(manifest.path("id").asText())) throw new IOException("Ungültige MobileCity-Vorlagenversion");
        List<Path> files=new ArrayList<>();
        var fields=manifest.path("files").fields();
        while (fields.hasNext()) {
            var entry=fields.next(); Path file=source.resolve(entry.getKey()).normalize();
            if (!file.startsWith(source) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(source.toRealPath())
                    || !sha256(file).equals(entry.getValue().asText())) throw new IOException("MobileCity-Vorlage verändert/ungültig: " + entry.getKey());
            files.add(file);
        }
        Map<String,MobileCityCsv> csv=new LinkedHashMap<>();
        JsonNode contract=JSON.readTree(MobileCityTemplateExporter.class.getResourceAsStream("/mobilecity-contract.json"));
        var families=contract.fields();
        while (families.hasNext()) {
            var family=families.next(); Path path=source.resolve(family.getKey()+".csv");
            if (!files.contains(path)) throw new IOException("Nicht versionierte Pflichtdatei: " + path.getFileName());
            MobileCityCsv data=MobileCityCsv.read(path);
            for (JsonNode block : family.getValue()) data.table(block.asText());
            csv.put(family.getKey(),data);
        }
        if (!files.contains(source.resolve("districts.geojson"))) throw new IOException("District-Geometrie fehlt im Manifest");
        var districtTable=csv.get("REGdistricts").table("districts");
        List<String> ids=districtTable.rows.stream().map(List::getFirst).toList();
        if (new HashSet<>(ids).size()!=ids.size()) throw new IOException("Doppelte District-ID");
        Map<String,Geometry> geometries=new LinkedHashMap<>();
        for (JsonNode feature : JSON.readTree(source.resolve("districts.geojson").toFile()).path("features")) {
            String district=feature.path("properties").path("districtID").asText();
            Geometry geometry=MobileCityGeometry.read(feature.get("geometry"));
            if (!geometry.isValid() || geometry.isEmpty() || geometries.putIfAbsent(district,geometry)!=null)
                throw new IOException("Ungültige/doppelte District-Geometrie: " + district);
        }
        if (!geometries.keySet().equals(new HashSet<>(ids))) throw new IOException("District-IDs und Geometrien stimmen nicht überein");
        validateNetworks(csv,new HashSet<>(ids));

        var r=scenario.region;
        if (r==null || !Epsg3035.covers(r.minLat,r.minLon,r.maxLat,r.maxLon)) throw new IOException("Vorlagen-Mapping benötigt eine Region im EPSG:3035-Gebiet");
        Geometry region=MobileCityGeometry.FACTORY.toGeometry(new Envelope(r.minLon,r.maxLon,r.minLat,r.maxLat));
        Geometry[] cells=new Geometry[zones.size()];
        List<Geometry> internal=new ArrayList<>();
        for (Zone z:zones) if (!z.external) { cells[z.index]=MobileCityGeometry.zone(z); internal.add(cells[z.index]); }
        Geometry coverage=UnaryUnionOp.union(internal);
        double[][] weights=new double[ids.size()][zones.size()];
        List<String> updated=new ArrayList<>();
        for (int d=0;d<ids.size();d++) {
            Geometry wgs=geometries.get(ids.get(d));
            if (!region.covers(wgs)) continue;
            Geometry district=wgs;
            // Tolerance only for numeric projection noise: at most 1 m² or one millionth of area.
            if (MobileCityGeometry.project(district.difference(coverage)).getArea()
                    >Math.max(1,MobileCityGeometry.project(district).getArea()*1e-6)) continue;
            updated.add(ids.get(d));
            for (Zone z:zones) if (!z.external && district.getEnvelopeInternal().intersects(cells[z.index].getEnvelopeInternal())) {
                weights[d][z.index]=Math.min(1,MobileCityGeometry.project(district.intersection(cells[z.index])).getArea()
                        /MobileCityGeometry.project(cells[z.index]).getArea());
            }
        }
        if (updated.isEmpty()) throw new IOException("Kein vollständiger Vorlagen-District abgedeckt. Region vergrößern oder MobileCity-Teilbeitrag wählen.");
        for (Zone z:zones) {
            double sum=0; for (double[] w:weights) sum+=w[z.index];
            if (sum>1.00001) throw new IOException("Überlappende Vorlagen-Districts bei Zone " + z.id);
        }
        long[][] ages=new long[ids.size()][3]; long[] cars=new long[ids.size()];
        for (int g=0;g<4;g++) {
            double[] exact=new double[ids.size()];
            for (int d=0;d<ids.size();d++) for (Zone z:zones)
                exact[d]+=weights[d][z.index]*(g<3 ? population.ages[z.index][g] : population.cars[z.index]);
            long[] rounded=MobileCityPopulation.rounded(exact);
            for (int d=0;d<ids.size();d++) { if (g<3) ages[d][g]=rounded[d]; else cars[d]=rounded[d]; }
        }
        int year=scenario.referenceYear;
        var params=csv.get("StudyAreaConfig").table("studyAreaParametersList");
        int earliest=Integer.parseInt(params.row("earliestDataYear").get(1));
        int end=Integer.parseInt(params.row("endYear").get(1));
        if (year<earliest || year>=end) throw new IOException("Bezugsjahr muss im Vorlagenzeitraum " + earliest + " bis " + (end-1) + " liegen");
        // MobileCity's referenceYear is its simulation start, including historic warm-up.
        // Moving it to the contribution year loses lagged state (e.g. transit capacity).
        int simulationStartYear=Integer.parseInt(params.row("referenceYear").get(1));
        if (year<simulationStartYear) throw new IOException("Beitragsjahr liegt vor dem Simulationsstart der Vorlage");
        int typeColumn=districtTable.header.indexOf("districtType");
        if (typeColumn<0) throw new IOException("districtType fehlt");
        boolean[] studyArea=new boolean[ids.size()]; int internalCount=0;
        for (int d=0;d<ids.size();d++) { studyArea[d]=Integer.parseInt(districtTable.rows.get(d).get(typeColumn))>0; if(studyArea[d]) internalCount++; }
        params.row("studyAreaDistrictCount").set(1,Integer.toString(internalCount));
        params.row("surroundingDistrictCount").set(1,Integer.toString(ids.size()-internalCount));

        double[] selectedFractions=new double[zones.size()];
        for (int d=0;d<ids.size();d++) if (studyArea[d]) for (int z=0;z<zones.size();z++) selectedFractions[z]+=weights[d][z];
        double[][] generatedRates=population.tripRates(selectedFractions), finalRates=new double[3][4];
        long[] totals=new long[3]; long totalCars=0;
        for (int g=0;g<3;g++) {
            var pop=csv.get("POPinhabitants").table("population[AgeGroups."+MobileCityPopulation.GROUPS[g]+"]");
            int column=pop.ensureYear(year,true); double untouched=0, replaced=0;
            for (int d=0;d<ids.size();d++) {
                if (updated.contains(ids.get(d))) pop.row(ids.get(d)).set(column,Long.toString(ages[d][g]));
                if (studyArea[d]) {
                    double count=pop.atYear(ids.get(d),year);
                    if (updated.contains(ids.get(d))) replaced+=count; else untouched+=count;
                }
            }
            totals[g]=Math.round(replaced+untouched);
            var trips=csv.get("POPtrips").table("averageDailyTrips[AgeGroups."+MobileCityPopulation.GROUPS[g]+"]");
            int tripColumn=trips.ensureYear(year);
            for (int p=0;p<4;p++) {
                String purpose=MobileCityPopulation.PURPOSES[p];
                double prior=trips.atYear(purpose,year);
                double rate=replaced+untouched>0 ? (prior*untouched+generatedRates[g][p]*replaced)/(replaced+untouched) : prior;
                finalRates[g][p]=rate; trips.row(purpose).set(tripColumn,MobileCityCsv.number(rate));
            }
        }
        var stock=csv.get("TECCarFleet").table("stock.total"); int carColumn=stock.ensureYear(year,true);
        var electricShares=csv.get("TECCarFleet").table("stock.shareElectric");
        Map<String,Double> retainedShares=new LinkedHashMap<>();
        for (String district:updated) retainedShares.put(district,electricShares.atYear(district,year));
        List<String> fleetFallbackDistricts=new ArrayList<>();
        if(fleet!=null) for(int d=0;d<ids.size();d++) if(updated.contains(ids.get(d))) {
            double knownCars=0,electricCars=0; boolean missing=false;
            for(Zone z:zones) if(weights[d][z.index]>0 && population.cars[z.index]>0) {
                double count=weights[d][z.index]*population.cars[z.index];
                var share=fleet.shares().get(z.index);
                if(share==null) missing=true;
                else {knownCars+=count;electricCars+=count*share.bev();}
            }
            if(!missing && knownCars>0) retainedShares.put(ids.get(d),electricCars/knownCars);
            else fleetFallbackDistricts.add(ids.get(d));
        }
        int shareColumn=electricShares.ensureSparseYear(year);
        for (int d=0;d<ids.size();d++) {
            if (updated.contains(ids.get(d))) {
                stock.row(ids.get(d)).set(carColumn,Long.toString(cars[d]));
                // Native fleet module only uses stock.total if shareElectric exists for this year.
                electricShares.row(ids.get(d)).set(shareColumn,MobileCityCsv.number(retainedShares.get(ids.get(d))));
            }
            if (studyArea[d]) totalCars+=Math.round(stock.atYear(ids.get(d),year));
        }
        Files.createDirectories(out);
        for (Path file:files) { Path dest=out.resolve(source.relativize(file)); Files.createDirectories(dest.getParent()); Files.copy(file,dest,StandardCopyOption.REPLACE_EXISTING); }
        for (String changed:List.of("StudyAreaConfig","POPinhabitants","POPtrips","TECCarFleet")) csv.get(changed).write(out.resolve(changed+".csv"));
        ShapefileWriter.writePolygons(out.resolve("districts"),ids,ids.stream().map(geometries::get).toList(),"districtID");
        Map<String,Object> provenance=new LinkedHashMap<>();
        provenance.put("templateId",id); provenance.put("templateManifestSha256",sha256(manifestFile));
        provenance.put("referenceYear",year); provenance.put("samplingFraction",scenario.population.samplingFraction);
        provenance.put("simulationStartYear",simulationStartYear);
        provenance.put("updatedDistricts",updated); provenance.put("ageAndCarImputationZoneIds",population.imputedZones);
        provenance.put("spatialAllocation","EPSG:3035 area fractions, uniform population within each zone; complete districts only");
        provenance.put("timeSeriesPolicy","Existing years preserved; missing reference year linearly interpolated, endpoints held constant");
        provenance.put("attractivityPolicy","Reference values preserved: regional normalization differs from synthetic zone scores");
        provenance.put("tripRatesPolicy","Age-specific rates blended by updated and retained study-area population; surrounding districts excluded");
        provenance.put("carElectricSharePolicy","Reference shares interpolated/endpoints held for updated districts only; required for native stock.total adoption");
        if(fleet!=null) {
            provenance.put("carElectricSharePolicy","Private KBA BEV shares or explicit user assumption, weighted by generated cars; missing districts retain reference shares");
            provenance.put("fleetReferenceFallbackDistricts",fleetFallbackDistricts);
            provenance.put("fleetSourceReport","fleet-provenance.json");
        }
        provenance.put("templateRepairs",manifest.path("repairs"));
        provenance.put("referenceIssues",manifest.path("issues"));
        provenance.put("validation","CSV contract and IDs checked; Config Tool validation must be run for this package");
        Map<String,String> hashes=new TreeMap<>();
        try(var walk=Files.walk(out)) { for(Path p:walk.filter(Files::isRegularFile).sorted().toList()) hashes.put(out.relativize(p).toString(),sha256(p)); }
        provenance.put("files",hashes);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("provenance.json").toFile(),provenance);
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("tables",csv.size()); summary.put("mode","template"); summary.put("referenceYear",year); summary.put("templateId",id);
        summary.put("updatedDistricts",updated); summary.put("validationStatus","pending-config-tool");
        summary.put("simulationStartYear",simulationStartYear);
        summary.put("districts",Map.of("total",ids.size(),"studyArea",internalCount,"surrounding",ids.size()-internalCount));
        summary.put("inhabitants",Map.of("children",totals[0],"adults",totals[1],"seniors",totals[2],"total",Arrays.stream(totals).sum()));
        summary.put("privateCars",totalCars);
        summary.put("tripsPerDay",Map.of("children",Arrays.stream(finalRates[0]).sum(),"adults",Arrays.stream(finalRates[1]).sum(),"seniors",Arrays.stream(finalRates[2]).sum()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(),summary);
        List<Path> exported;
        try(var walk=Files.walk(out)) { exported=walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".zip")).sorted().toList(); }
        try(var zip=new ZipOutputStream(Files.newOutputStream(out.resolve("mobilecity-import.zip")))) {
            for(Path file:exported) { ZipEntry entry=new ZipEntry("Import/"+out.relativize(file)); entry.setTime(0); zip.putNextEntry(entry); Files.copy(file,zip); zip.closeEntry(); }
        }
        return exported.size()+1;
    }

    private static void validateNetworks(Map<String,MobileCityCsv> csv, Set<String> districts) throws IOException {
        for(String mode:List.of("car","bike","transit")) {
            var nodes=csv.get("INF"+mode+"Nodes").table(mode+"Nodes"); Set<String> ids=new HashSet<>(), represented=new HashSet<>();
            int district=nodes.header.indexOf("districtID"),ref=nodes.header.indexOf("refNodeTrue"),active=nodes.header.indexOf("nodeActive");
            if(district<0 || ref<0) throw new IOException("Netzknoten-Spalten fehlen: "+mode);
            for(var row:nodes.rows) {
                if(!ids.add(row.getFirst())) throw new IOException("Doppelter Netzknoten: "+row.getFirst());
                if(row.get(ref).equals("1") && (active<0 || row.get(active).equals("1"))) {
                    if(!districts.contains(row.get(district))) throw new IOException("Fremder Referenz-District: "+row.get(district));
                    represented.add(row.get(district));
                }
            }
            if(!represented.containsAll(districts)) throw new IOException("Fehlende District-Referenzknoten: "+mode);
            var links=csv.get("INF"+mode+"Links").table(mode+"Links"); Set<String> edges=new HashSet<>();
            int from=links.header.indexOf("fromNodeID"),to=links.header.indexOf("toNodeID");
            if(from<0 || to<0) throw new IOException("Netzkanten-Spalten fehlen: "+mode);
            for(var row:links.rows) if(!ids.contains(row.get(from)) || !ids.contains(row.get(to)) || !edges.add(row.get(from)+"\n"+row.get(to)))
                throw new IOException("Ungültige/doppelte Netzkante: "+mode+" "+row.get(from)+" -> "+row.get(to));
        }
    }
    static String sha256(Path file) throws IOException {
        try { MessageDigest digest=MessageDigest.getInstance("SHA-256"); try(var in=Files.newInputStream(file)) { byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); } return HexFormat.of().formatHex(digest.digest()); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
