package de.synthesis.generation.pipeline;

import com.fasterxml.jackson.databind.*;
import de.synthesis.scenario.Scenario;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntToDoubleFunction;
import java.util.zip.*;

/** Complete regional baseline with explicit model-profile and aggregate-network assumptions. */
final class MobileCityRegionalExporter {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final List<Zone> zones;
    private final Scenario scenario;
    private final Path profiles;
    private final Map<String,Object> runSources;
    private final MobileCityGtfsAdapter.Feed transitFeed;
    private final MobileCitySurroundingSource.Result automaticSurrounding;
    private MobileCityGtfsAdapter.Result transitResult;
    private MobileCityChargingSource.Result chargingResult;
    private long effectiveSurroundingPopulation;
    private boolean surroundingAgesFromCensus;
    private final Map<String,MobileCityCsv> csv=new LinkedHashMap<>();
    private final List<District> districts=new ArrayList<>();
    private final List<District> studyDistricts=new ArrayList<>();
    private final List<District> surroundingDistricts=new ArrayList<>();
    private final Map<String,Object> provenance=new LinkedHashMap<>();
    private final List<String> years=new ArrayList<>();
    private static final class District {
        final Zone representative; final String id; final List<Zone> members=new ArrayList<>();
        final boolean surrounding;
        final long[] ages=new long[3]; long cars; double electricCars; Geometry geometry;
        District(Zone representative,int index,boolean surrounding){
            this.representative=representative;this.surrounding=surrounding;
            this.id=String.format(Locale.ROOT,surrounding?"OUT-%03d":"SYN-%03d",index+1);
        }
    }
    MobileCityRegionalExporter(List<Zone> zones,Scenario scenario,Path profiles,Map<String,Object> runSources,
                               MobileCityGtfsAdapter.Feed transitFeed,
                               MobileCitySurroundingSource.Result automaticSurrounding){
        this.zones=zones;this.scenario=scenario;this.profiles=profiles;
        this.runSources=new LinkedHashMap<>(runSources);
        this.transitFeed=transitFeed;
        this.automaticSurrounding=automaticSurrounding;
    }

    int export(Path artifacts,Path out,MobileCityPopulation population,MobileCityFleetSource.Result fleet) throws IOException {
        if(scenario.mobileCity.maxDistricts<1 || scenario.mobileCity.maxDistricts>100 || scenario.mobileCity.useTemplate)
            throw new IOException("Ungültige Regionalexport-Konfiguration");
        if(scenario.region==null)throw new IOException("Region fehlt");
        if(fleet==null) throw new IOException("Regionalpaket benötigt KBA-Anteile oder eine explizite BEV-Annahme");
        validateSurroundingConfiguration();
        int start=scenario.referenceYear,end=Math.min(2050,start+10);
        if(start<2018 || start>2048) throw new IOException("Regionalprofil unterstützt Bezugsjahre 2018 bis 2048");
        for(int y=start-2;y<=end;y++) years.add(Integer.toString(y));
        Path profile=loadProfile();
        aggregate(population,fleet);
        prepareTransit(out);
        prepareCharging(out);
        provenance.put("mode","regional");provenance.put("schemaVersion",1);
        provenance.put("referenceYear",start);provenance.put("endYear",end);
        provenance.put("districtAllocation",scenario.mobileCity.autoSurrounding
                ? "Deterministic study-district aggregation; surrounding districts are the non-overlapping directional sectors of a metric census buffer and use the existing external anchors as impedance representatives"
                : "Deterministic farthest-point representatives and nearest-centroid assignment for study districts; explicitly enabled surrounding population is distributed deterministically over the existing eight directional anchors");
        provenance.put("districts",districts.stream().map(d->Map.of("districtID",d.id,"districtType",d.surrounding?0:1,"representativeZoneId",d.representative.id,"sourceZoneIds",d.members.stream().map(z->z.id).toList())).toList());
        Set<Long> usedExternalZoneIds=surroundingDistricts.stream()
                .map(d->d.representative.id).collect(java.util.stream.Collectors.toSet());
        provenance.put("excludedExternalDestinationZoneIds",zones.stream()
                .filter(z->z.external && !usedExternalZoneIds.contains(z.id)).map(z->z.id).toList());
        provenance.put("surroundingPolicy",surroundingDistricts.isEmpty()
                ? "No surrounding resident population configured; existing outbound destination anchors excluded"
                : scenario.mobileCity.autoSurrounding
                ? "Directional buffer geometries and population distribution from Census 2022; optional manual total scales the observed spatial weights; Census ages used where available; study-area car ownership and BEV structures extrapolated; inbound purpose shares supplied by the user"
                : "User-defined total population distributed equally over directional anchors; study-area age, car-ownership and BEV structures extrapolated; purpose-specific inbound trip shares supplied by the user");
        provenance.put("temporalPolicy","Constant baseline stocks and trip rates, including two initialization years; these are model assumptions, not historic observations or forecasts");
        provenance.put("networkPolicy",transitResult==null
                ? "Undirected complete graphs of district representatives from symmetrized OD impedances; public transport uses the disclosed synthetic fallback."
                : "Car and bike use undirected district graphs from symmetrized OD impedances; transit uses the representative-day GTFS stop network with derived district access links.");
        provenance.put("modelPolicy",transitResult==null
                ? "TRAMIGO coefficients used as an uncalibrated model profile; positive synthetic transit supply from explicit vehicles-per-1000 fallback"
                : "TRAMIGO coefficients used as an uncalibrated model profile; observed GTFS service network and peak vehicle counts replace the synthetic transit fallback");
        Map<String,Object> assumptions=new LinkedHashMap<>();
        assumptions.put("settlementAreaShare",scenario.mobileCity.settlementAreaShare);
        if(!surroundingDistricts.isEmpty()) {
            Map<String,Object> surroundingAssumption=new LinkedHashMap<>();
            surroundingAssumption.put("mode",scenario.mobileCity.autoSurrounding?"census-buffer":"manual");
            surroundingAssumption.put("population",effectiveSurroundingPopulation);
            surroundingAssumption.put("manualPopulationOverride",scenario.mobileCity.surroundingPopulation);
            surroundingAssumption.put("bufferKm",scenario.mobileCity.autoSurrounding?scenario.mobileCity.surroundingBufferKm:null);
            surroundingAssumption.put("educationTripShare",value(scenario.mobileCity.surroundingEducationTripShare));
            surroundingAssumption.put("workTripShare",value(scenario.mobileCity.surroundingWorkTripShare));
            surroundingAssumption.put("shoppingTripShare",value(scenario.mobileCity.surroundingShoppingTripShare));
            surroundingAssumption.put("leisureTripShare",value(scenario.mobileCity.surroundingLeisureTripShare));
            assumptions.put("surrounding",surroundingAssumption);
        }
        if(transitResult==null) assumptions.put("syntheticTransitVehiclesPer1000",scenario.mobileCity.transitVehiclesPer1000);
        provenance.put("assumptions",assumptions);
        provenance.put("fleetSourceReport","fleet-provenance.json");
        var params=table("StudyAreaConfig","studyAreaParametersList");
        for(var entry:Map.of("referenceYear",start,"earliestDataYear",start-2,"endYear",end,"studyAreaDistrictCount",studyDistricts.size(),"surroundingDistrictCount",surroundingDistricts.size()).entrySet()) params.row(entry.getKey()).set(1,entry.getValue().toString());
        params.row("centerLatitude").set(1,num((scenario.region.minLat+scenario.region.maxLat)/2));
        params.row("centerLongitude").set(1,num((scenario.region.minLon+scenario.region.maxLon)/2));
        replace("REGdistricts","districts",List.of("districtID","name","districtType","totalArea","settlementArea","urbanization"),
                districts.stream().map(d->row(d.id,d.id,d.surrounding?0:1,area(d),area(d)*scenario.mobileCity.settlementAreaShare,1)).toList());
        double[] totals=new double[4];
        for(var d:districts) for(var z:d.members) for(int p=0;p<4;p++) totals[p]+=score(z,p);
        List<List<String>> attraction=new ArrayList<>();
        for(var d:districts) {
            List<String> r=row(d.id,d.id);
            for(int p=0;p<4;p++){final int purpose=p;double value=d.members.stream().mapToDouble(z->score(z,purpose)).sum();r.add(num(totals[p]>0?100*value/totals[p]:100.0/districts.size()));}
            attraction.add(r);
        }
        replace("REGattractivity","attractivity",List.of("districtID","name","TripPurpose.Education","TripPurpose.Work","TripPurpose.Shopping","TripPurpose.Leisure"),attraction);
        List<List<String>> chargerRows=new ArrayList<>();
        for(var d:districts){List<String> row=row(d.id);for(String year:years){Integer value=chargingResult.value(d.id,Integer.parseInt(year));row.add(value==null?"":value.toString());}chargerRows.add(row);}
        replace("REGchargers","numberOfChargingStations",yearHeader("districtID"),chargerRows);
        provenance.put("chargingPolicy","Public charging-point counts come from charging-provenance.json; empty future cells delegate growth to the native MobileCity model.");
        for(int g=0;g<3;g++){final int group=g;series("POPinhabitants","population[AgeGroups."+MobileCityPopulation.GROUPS[g]+"]",i->districts.get(i).ages[group]);}
        double[] fractions=new double[zones.size()];for(var z:zones) if(!z.external) fractions[z.index]=1;
        double[][] rates=population.tripRates(fractions);
        long adults=studyDistricts.stream().mapToLong(d->d.ages[1]).sum();
        double dailyTrips=0;
        for(var d:studyDistricts)for(int g=0;g<3;g++)dailyTrips+=d.ages[g]*Arrays.stream(rates[g]).sum();
        if(adults==0 || dailyTrips<=0)throw new IOException("Das native MobileCity-Modell benötigt Erwachsene und positive Wegehäufigkeiten für seine Kennzahlen");
        for(int g=0;g<3;g++) {
            List<List<String>> rows=new ArrayList<>();for(int p=0;p<4;p++)rows.add(constant(MobileCityPopulation.PURPOSES[p],rates[g][p]));
            replace("POPtrips","averageDailyTrips[AgeGroups."+MobileCityPopulation.GROUPS[g]+"]",yearHeader("tripPurpose"),rows);
        }
        // Native CSV parser requires at least two columns even with no surrounding districts.
        List<District> surroundingColumns=surroundingDistricts.isEmpty()?studyDistricts:surroundingDistricts;
        double[] surroundingShares={value(scenario.mobileCity.surroundingEducationTripShare),
                value(scenario.mobileCity.surroundingWorkTripShare),
                value(scenario.mobileCity.surroundingShoppingTripShare),
                value(scenario.mobileCity.surroundingLeisureTripShare)};
        List<List<String>> surroundingRows=new ArrayList<>();
        for(int p=0;p<MobileCityPopulation.PURPOSES.length;p++){
            List<String> r=row(MobileCityPopulation.PURPOSES[p]);
            for(int i=0;i<surroundingColumns.size();i++)r.add(num(surroundingDistricts.isEmpty()?0:surroundingShares[p]));
            surroundingRows.add(r);
        }
        replace("POPtripsSurrounding","tripShareToStudyArea",districtHeader("TripPurpose",surroundingColumns),surroundingRows);
        series("TECCarFleet","stock.total",i->districts.get(i).cars);
        series("TECCarFleet","stock.shareElectric",i->districts.get(i).cars>0?districts.get(i).electricCars/districts.get(i).cars:0);
        series("TECCarFleet","registrations.total",i->Math.round(districts.get(i).cars/8.0));
        series("TECCarFleet","registrations.shareElectric",i->districts.get(i).cars>0?districts.get(i).electricCars/districts.get(i).cars:0);
        provenance.put("registrationPolicy","Annual registrations assumed as stock/8; BEV share held equal to stock share. KBA registration observations not available in this source.");
        long sharingPeople=studyDistricts.stream().mapToLong(d->Arrays.stream(d.ages).sum()).sum();
        double sharingVehicles=scenario.mobileCity.carsharingVehiclesPer1000==null?0:scenario.mobileCity.carsharingVehiclesPer1000;
        double sharingMemberShare=scenario.mobileCity.carsharingMemberShare==null?0:scenario.mobileCity.carsharingMemberShare;
        replace("TECCarsharing","carsharing.member",yearHeader("carsharing.member"),
                List.of(constant("default",Math.round(sharingPeople*sharingMemberShare))));
        series("TECCarsharing","carsharing.stock.total",i->Math.round(
                Arrays.stream(districts.get(i).ages).sum()*sharingVehicles/1000d));
        provenance.put("sharingPolicy",scenario.mobileCity.carsharingVehiclesPer1000==null
                && scenario.mobileCity.carsharingMemberShare==null
                ? "Explicit zero baseline; no reference-region service areas copied"
                : "User-defined carsharing vehicles per 1,000 inhabitants and study-area member share; held constant over the export horizon; no reference-region service areas copied");
        provenance.put("carsharingAssumption",Map.of("vehiclesPer1000",sharingVehicles,
                "memberShare",sharingMemberShare,"members",Math.round(sharingPeople*sharingMemberShare)));
        for(String name:List.of("serviceUpdates","specialEffectDemandGrowthImpact","serviceAreaDefinition")) table("TECRidepooling",name).rows.clear();
        var transit=table("TECTransit","TECTransitYearlyParameters");
        List<List<String>> transitRows=new ArrayList<>();double people=studyDistricts.stream().mapToLong(d->Arrays.stream(d.ages).sum()).sum();
        for(var r:transit.rows){double value=transit.atYear(r.getFirst(),start);if(r.getFirst().equals("bus.stock.total"))value=transitResult==null?Math.max(1,Math.round(people/1000*scenario.mobileCity.transitVehiclesPer1000)):transitResult.peakBusVehicles();if(r.getFirst().equals("train.stock.total"))value=transitResult==null?0:transitResult.peakTrainVehicles();transitRows.add(constant(r.getFirst(),value));}
        replace("TECTransit","TECTransitYearlyParameters",yearHeader("parameterName"),transitRows);
        replace("TECTransit","seasonTicketRate",List.of("districtID","Value"),List.of(row("default",.25)));
        Map<String,double[][]> matrices=new LinkedHashMap<>();
        for(String name:List.of("CAR_TTC","BIKE_TTC","PED_TTC","PUT_TTC")) matrices.put(name,readMatrix(artifacts.resolve("matrix/travel_times/"+name+".mtx.bz2"),zones));
        for(String name:List.of("CAR_DIS","BIKE_DIS","PED_DIS")) matrices.put(name,readMatrix(artifacts.resolve("matrix/other/"+name+".mtx.bz2"),zones));
        Map<String,String> hashes=new TreeMap<>();
        for(String name:matrices.keySet()){Path f=artifacts.resolve("matrix/"+(name.endsWith("DIS")?"other/":"travel_times/")+name+".mtx.bz2");hashes.put(name,CensusStructureSource.sha256(f));}
        provenance.put("inputMatrixSha256",hashes);
        for(var d:districts)for(var entry:matrices.entrySet())if(entry.getValue()[d.representative.index][d.representative.index]<=0)
            throw new IOException("Intrazonale Impedanz muss positiv sein: "+entry.getKey());
        networks(matrices);
        var original=table("INFdistricts","districtVariables");List<List<String>> districtRows=new ArrayList<>();
        for(var r:original.rows){List<String> values=row(r.getFirst());for(var d:districts){String key=r.getFirst();int i=d.representative.index;double value=Double.parseDouble(r.get(1));
            if(key.startsWith("timeIntra"))value=matrices.get(key.endsWith("Car")?"CAR_TTC":key.endsWith("Bike")?"BIKE_TTC":"PUT_TTC")[i][i];
            else if(key.startsWith("distanceIntra"))value=matrices.get(key.endsWith("Bike")?"BIKE_DIS":"CAR_DIS")[i][i]/1000;
            else if(key.equals("avSpeedIntraCar"))value=matrices.get("CAR_DIS")[i][i]/1000/matrices.get("CAR_TTC")[i][i]*60;
            values.add(num(value));}districtRows.add(values);}
        replace("INFdistricts","districtVariables",districtHeader("districtID"),districtRows);
        for(String family:List.of("INFpedestrian","TECTravelDistance")) {
            for(String name:new ArrayList<>(csv.get(family).tables.keySet())) {
                List<List<String>> rows=new ArrayList<>();
                for(var a:districts){List<String> r=row(a.id);for(var b:districts){int i=a.representative.index,j=b.representative.index;
                    r.add(num(family.equals("TECTravelDistance")?1:matrices.get(name.contains("Time")?"PED_TTC":"PED_DIS")[i][j]/(name.contains("Time")?1:1000)));}rows.add(r);}
                replace(family,name,districtHeader("districtID"),rows);
            }
        }
        // Financial constants are a model profile; region-sized budgets use an explicit per-capita transfer.
        int financeYear=Integer.parseInt(MobileCityCsv.read(profile.resolve("StudyAreaConfig.csv")).table("studyAreaParametersList").row("referenceYear").get(1));
        var profileDistricts=MobileCityCsv.read(profile.resolve("REGdistricts.csv")).table("districts");
        Set<String> studyIds=new HashSet<>();
        for(var r:profileDistricts.rows)if(Integer.parseInt(r.get(profileDistricts.header.indexOf("districtType")))>=1)studyIds.add(r.getFirst());
        double profilePopulation=0;var baseline=MobileCityCsv.read(profile.resolve("POPinhabitants.csv"));
        for(var t:baseline.tables.values())for(var r:t.rows)if(studyIds.contains(r.getFirst()))profilePopulation+=t.atYear(r.getFirst(),financeYear);
        if(profilePopulation<=0)throw new IOException("Modellprofil ohne positive Bezugsbevölkerung");
        provenance.put("financeReference",Map.of("year",financeYear,"population",profilePopulation,"scope","districtType >= StudyArea (1), excluding Outer (0)"));
        var fin=table("FIN","FINVariables");
        for(var r:fin.rows)if(r.getFirst().startsWith("budgetAllocation") || Set.of("operationsTransit","eurosTotal","eurosMaxScore","eurosMinScore","networkLengthCar","networkLengthBike","networkLengthTransit").contains(r.getFirst()))r.set(1,num(Double.parseDouble(r.get(1))*people/profilePopulation));
        provenance.put("financePolicy","Regional budgets, network-length and operation baselines transferred per capita from profile population; not municipal accounts or measured physical network lengths");
        for(var e:csv.entrySet()) e.getValue().write(out.resolve(e.getKey()+".csv"));
        ShapefileWriter.writePolygons(out.resolve("districts"),districts.stream().map(d->d.id).toList(),districts.stream().map(d->d.geometry).toList(),"districtID");
        // Reference measures contain foreign node/link IDs. Export an empty baseline schedule only.
        Path governance=Files.createDirectories(out.resolve("GovernanceConfig"));
        var schedule=new MobileCityCsv();
        schedule.tables.put("governanceSchedule",new MobileCityCsv.Table(
                new ArrayList<>(List.of("governanceID","StartDateYear","StartDateMonth","Level")),new ArrayList<>()));
        schedule.write(governance.resolve("MeasureSchedule.csv"));
        provenance.put("governancePolicy","No measure definitions, geographic modifications or scheduled interventions exported; measures require regional modelling");
        Path census=artifacts.resolve("population/census-structure.json");
        if(Files.isRegularFile(census)) {
            Files.copy(census,out.resolve("census-structure.json"));
            provenance.put("populationSourceReport","census-structure.json");
            provenance.put("populationSourceSha256",CensusStructureSource.sha256(census));
        }
        if(scenario.mobileCity.autoSurrounding && automaticSurrounding!=null){
            Path report=out.resolve("surrounding-provenance.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),automaticSurrounding.provenance());
            provenance.put("surroundingSourceReport",report.getFileName().toString());
            provenance.put("surroundingSourceSha256",CensusStructureSource.sha256(report));
            provenance.put("surroundingAgesFromCensus",surroundingAgesFromCensus);
        }
        Map<String,Object> inventory=MobileCityDataInventory.regional(scenario,zones,population,fleet,
                studyDistricts.size(),surroundingDistricts.size(),artifacts,runSources,String.valueOf(provenance.get("profileManifestSha256")),
                transitFeed,transitResult,chargingResult,automaticSurrounding,
                effectiveSurroundingPopulation,surroundingAgesFromCensus);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("data-inventory.json").toFile(),inventory);
        provenance.put("dataInventory","data-inventory.json");
        long[] ages=new long[3];for(var d:studyDistricts)for(int g=0;g<3;g++)ages[g]+=d.ages[g];
        long surroundingPeople=surroundingDistricts.stream().mapToLong(d->Arrays.stream(d.ages).sum()).sum();
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("mode","regional");summary.put("tables",csv.size());summary.put("referenceYear",start);summary.put("templateId",scenario.mobileCity.templateId);
        summary.put("updatedDistricts",districts.stream().map(d->d.id).toList());summary.put("validationStatus","pending-config-tool");
        summary.put("districts",Map.of("total",districts.size(),"studyArea",studyDistricts.size(),"surrounding",surroundingDistricts.size()));
        summary.put("inhabitants",Map.of("children",ages[0],"adults",ages[1],"seniors",ages[2],"total",Arrays.stream(ages).sum()));
        summary.put("surroundingInhabitants",surroundingPeople);
        summary.put("privateCars",studyDistricts.stream().mapToLong(d->d.cars).sum());summary.put("tripsPerDay",Map.of("children",Arrays.stream(rates[0]).sum(),"adults",Arrays.stream(rates[1]).sum(),"seniors",Arrays.stream(rates[2]).sum()));
        summary.put("dataInventory",inventory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(),summary);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("provenance.json").toFile(),provenance);
        List<Path> files;try(var walk=Files.walk(out)){files=walk.filter(Files::isRegularFile).sorted().toList();}
        try(var zip=new ZipOutputStream(Files.newOutputStream(out.resolve("mobilecity-import.zip")))){for(var f:files){var e=new ZipEntry("Import/"+out.relativize(f));e.setTime(0);zip.putNextEntry(e);Files.copy(f,zip);zip.closeEntry();}}
        return files.size()+1;
    }

    private Path loadProfile() throws IOException {
        String id=scenario.mobileCity.templateId;
        if(profiles==null || id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw new IOException("Ungültiges MobileCity-Modellprofil");
        Path root=profiles.resolve(id).toAbsolutePath().normalize();var manifest=JSON.readTree(root.resolve("template-manifest.json").toFile());
        if(manifest.path("schemaVersion").asInt()!=1 || !id.equals(manifest.path("id").asText()))throw new IOException("Unbekannte Modellprofil-Version");
        var files=manifest.path("files").fields();while(files.hasNext()){var e=files.next();Path f=root.resolve(e.getKey()).normalize();if(!f.startsWith(root)||!f.toRealPath().startsWith(root.toRealPath())||!CensusStructureSource.sha256(f).equals(e.getValue().asText()))throw new IOException("Modellprofil verändert: "+e.getKey());}
        var contract=JSON.readTree(getClass().getResourceAsStream("/mobilecity-contract.json"));
        var names=contract.fields();while(names.hasNext()){var f=names.next();if(!manifest.path("files").has(f.getKey()+".csv"))throw new IOException("Modellprofil-Datei nicht versioniert");var data=MobileCityCsv.read(root.resolve(f.getKey()+".csv"));for(var k:f.getValue())data.table(k.asText());csv.put(f.getKey(),data);}
        provenance.put("modelProfile",id);provenance.put("profileManifestSha256",CensusStructureSource.sha256(root.resolve("template-manifest.json")));
        return root;
    }
    private void validateSurroundingConfiguration() throws IOException {
        Integer population=scenario.mobileCity.surroundingPopulation;
        boolean requested=scenario.mobileCity.autoSurrounding || (population!=null && population>0);
        if(!requested)return;
        if(population!=null && population<0)throw new IOException("Umlandbevölkerung darf nicht negativ sein");
        if(scenario.mobileCity.autoSurrounding && population!=null && population==0)
            throw new IOException("Umlandüberschreibung muss positiv sein oder leer bleiben");
        if(scenario.mobileCity.autoSurrounding){
            if(!Double.isFinite(scenario.mobileCity.surroundingBufferKm)
                    || scenario.mobileCity.surroundingBufferKm<1 || scenario.mobileCity.surroundingBufferKm>100)
                throw new IOException("Umlandpuffer muss zwischen 1 und 100 km liegen");
            if(automaticSurrounding==null)
                throw new IOException("Automatisches Umland wurde nicht aufgelöst");
            if(!automaticSurrounding.populationAvailable() && (population==null || population<=0))
                throw new IOException("Zensus-Umlandbevölkerung nicht verfügbar; manuelle Gesamtsumme als Fallback angeben");
        }
        String[] labels={"Bildung","Arbeit","Einkauf","Freizeit"};
        Double[] shares={scenario.mobileCity.surroundingEducationTripShare,
                scenario.mobileCity.surroundingWorkTripShare,
                scenario.mobileCity.surroundingShoppingTripShare,
                scenario.mobileCity.surroundingLeisureTripShare};
        for(int i=0;i<labels.length;i++){
            Double share=shares[i];
            if(share==null || !Double.isFinite(share) || share<0 || share>1)
                throw new IOException("Umlandanteil für "+labels[i]+" muss zwischen 0 und 1 liegen");
        }
    }
    private void aggregate(MobileCityPopulation population,MobileCityFleetSource.Result fleet) throws IOException {
        List<Zone> inhabited=zones.stream().filter(z->!z.external && Arrays.stream(population.ages[z.index]).sum()>0).toList();
        if(inhabited.isEmpty())throw new IOException("Regionalexport benötigt eine positive Wohnbevölkerung");
        List<Zone> seeds=new ArrayList<>();seeds.add(inhabited.getFirst());
        while(seeds.size()<Math.min(scenario.mobileCity.maxDistricts,inhabited.size())) {
            Zone next=null;double furthest=-1;
            for(var z:inhabited)if(!seeds.contains(z)){double nearest=seeds.stream().mapToDouble(s->GridZoning.distanceKm(z,s)).min().orElseThrow();if(nearest>furthest){furthest=nearest;next=z;}}
            seeds.add(next);
        }
        seeds.sort(Comparator.comparingInt(z->z.index));for(var seed:seeds){var d=new District(seed,studyDistricts.size(),false);districts.add(d);studyDistricts.add(d);}
        for(var z:zones)if(!z.external){var d=studyDistricts.stream().min(Comparator.comparingDouble(a->GridZoning.distanceKm(z,a.representative))).orElseThrow();d.members.add(z);d.cars+=population.cars[z.index];for(int g=0;g<3;g++)d.ages[g]+=population.ages[z.index][g];
            var share=fleet.shares().get(z.index);if(population.cars[z.index]>0 && share==null)throw new IOException("BEV-Anteil fehlt für Zone "+z.id+". Regionaldaten prüfen oder explizite BEV-Annahme setzen.");if(share!=null)d.electricCars+=share.bev()*population.cars[z.index];}
        for(var d:studyDistricts) {
            d.geometry=UnaryUnionOp.union(d.members.stream().map(MobileCityGeometry::zone).toList());
            long people=Arrays.stream(d.ages).sum();
            // CAL6010_SOC uses integer cars/population, then divides by (previous ratio - 1).
            if(d.cars/people==1)throw new IOException("Natives MobileCity-Modell erzeugt ab dem dritten Jahr ungültige Werte bei 1 bis unter 2 Pkw je Einwohner: "+d.id);
        }
        if(studyDistricts.stream().mapToLong(d->d.cars).sum()==0)throw new IOException("Das native MobileCity-Modell benötigt einen positiven Pkw-Bestand für seine Pro-Fahrzeug-Kennzahlen");
        addSurroundingDistricts();
    }

    private void addSurroundingDistricts() throws IOException {
        int requested=scenario.mobileCity.surroundingPopulation==null?0:scenario.mobileCity.surroundingPopulation;
        if(scenario.mobileCity.autoSurrounding){addAutomaticSurroundingDistricts(requested);return;}
        if(requested<=0)return;
        List<Zone> availableAnchors=zones.stream().filter(z->z.external).sorted(Comparator.comparingInt(z->z.index)).toList();
        if(availableAnchors.isEmpty())throw new IOException("Umlandbevölkerung benötigt externe Zielanker; Außenbeziehungen aktivieren");
        List<Zone> anchors=availableAnchors.subList(0,Math.min(availableAnchors.size(),requested));
        long[] studyAges=new long[3];
        for(var d:studyDistricts)for(int g=0;g<3;g++)studyAges[g]+=d.ages[g];
        long studyPeople=Arrays.stream(studyAges).sum();
        long studyCars=studyDistricts.stream().mapToLong(d->d.cars).sum();
        double studyElectric=studyDistricts.stream().mapToDouble(d->d.electricCars).sum();
        for(int i=0;i<anchors.size();i++){
            Zone anchor=anchors.get(i);
            int people=requested/anchors.size()+(i<requested%anchors.size()?1:0);
            District d=new District(anchor,i,true);d.members.add(anchor);d.geometry=MobileCityGeometry.zone(anchor);
            allocateAges(d.ages,people,studyAges,studyPeople);
            d.cars=Math.round(people*studyCars/(double)studyPeople);
            d.electricCars=studyCars==0?0:d.cars*studyElectric/studyCars;
            districts.add(d);surroundingDistricts.add(d);
        }
        effectiveSurroundingPopulation=requested;
    }

    private void addAutomaticSurroundingDistricts(int manualTotal) throws IOException {
        if(automaticSurrounding==null)return;
        List<Zone> anchors=zones.stream().filter(z->z.external)
                .sorted(Comparator.comparingInt(z->z.index)).toList();
        if(anchors.size()<automaticSurrounding.sectors().size())
            throw new IOException("Automatisches Umland benötigt acht externe Richtungsanker; Außenbeziehungen aktivieren");
        double[] weights=automaticSurrounding.sectors().stream()
                .mapToDouble(MobileCitySurroundingSource.Sector::population).toArray();
        long observed=Math.round(Arrays.stream(weights).sum());
        long target=manualTotal>0?manualTotal:observed;
        if(target<=0)throw new IOException("Automatisches Umland enthält keine positive Wohnbevölkerung");
        if(observed<=0)Arrays.fill(weights,1);
        long[] allocated=allocateTotal(target,weights);
        long[] studyAges=new long[3];
        for(var d:studyDistricts)for(int g=0;g<3;g++)studyAges[g]+=d.ages[g];
        long studyPeople=Arrays.stream(studyAges).sum();
        long studyCars=studyDistricts.stream().mapToLong(d->d.cars).sum();
        double studyElectric=studyDistricts.stream().mapToDouble(d->d.electricCars).sum();
        double[] ringAges=new double[3];
        for(var sector:automaticSurrounding.sectors()){
            double[] values=sector.ageCounts();for(int g=0;g<3;g++)ringAges[g]+=values[g];
        }
        surroundingAgesFromCensus=Arrays.stream(ringAges).sum()>0;
        for(int i=0;i<allocated.length;i++){
            long people=allocated[i];if(people<=0)continue;
            var sector=automaticSurrounding.sectors().get(i);
            District d=new District(anchors.get(sector.directionIndex()),sector.directionIndex(),true);
            d.members.add(d.representative);d.geometry=sector.geometry();
            double[] localAges=sector.ageCounts();
            if(Arrays.stream(localAges).sum()>0)allocateAges(d.ages,people,localAges,Arrays.stream(localAges).sum());
            else if(surroundingAgesFromCensus)allocateAges(d.ages,people,ringAges,Arrays.stream(ringAges).sum());
            else allocateAges(d.ages,people,studyAges,studyPeople);
            d.cars=Math.round(people*studyCars/(double)studyPeople);
            d.electricCars=studyCars==0?0:d.cars*studyElectric/studyCars;
            districts.add(d);surroundingDistricts.add(d);
        }
        effectiveSurroundingPopulation=target;
    }

    private static void allocateAges(long[] target,long people,long[] source,long sourceTotal){
        target[0]=Math.round(people*source[0]/(double)sourceTotal);
        target[2]=Math.round(people*source[2]/(double)sourceTotal);
        target[1]=people-target[0]-target[2];
        if(target[1]<0){target[1]=0;long excess=target[0]+target[2]-people;if(target[2]>=excess)target[2]-=excess;else{excess-=target[2];target[2]=0;target[0]-=excess;}}
    }

    private static void allocateAges(long[] target,long people,double[] source,double sourceTotal){
        target[0]=Math.round(people*source[0]/sourceTotal);
        target[2]=Math.round(people*source[2]/sourceTotal);
        target[1]=people-target[0]-target[2];
        if(target[1]<0){target[1]=0;long excess=target[0]+target[2]-people;if(target[2]>=excess)target[2]-=excess;else{excess-=target[2];target[2]=0;target[0]-=excess;}}
    }

    static long[] allocateTotal(long total,double[] weights){
        if(total<0 || weights.length==0 || Arrays.stream(weights).anyMatch(v->!Double.isFinite(v)||v<0))
            throw new IllegalArgumentException("Ungültige Umlandverteilung");
        double sum=Arrays.stream(weights).sum();if(sum<=0)throw new IllegalArgumentException("Umlandgewichte sind null");
        long[] result=new long[weights.length];double[] fractions=new double[weights.length];long assigned=0;
        for(int i=0;i<weights.length;i++){double exact=total*weights[i]/sum;result[i]=(long)Math.floor(exact);fractions[i]=exact-result[i];assigned+=result[i];}
        List<Integer> order=new ArrayList<>();for(int i=0;i<weights.length;i++)order.add(i);
        order.sort(Comparator.<Integer>comparingDouble(i->fractions[i]).reversed().thenComparingInt(i->i));
        for(long remaining=total-assigned;remaining>0;remaining--)result[order.get((int)((total-assigned-remaining)%order.size()))]++;
        return result;
    }

    private void prepareTransit(Path out) throws IOException {
        transitResult=null;
        if(transitFeed==null){
            if(!scenario.mobileCity.allowSyntheticTransitFallback)throw new IOException("Regionalexport benötigt GTFS oder einen ausdrücklich aktivierten ÖV-Fallback");
            return;
        }
        List<MobileCityGtfsAdapter.District> refs=districts.stream().map(d->{
            var access=d.geometry.getInteriorPoint();
            return new MobileCityGtfsAdapter.District(d.id,access.getY(),access.getX(),d.geometry);
        }).toList();
        try {
            transitResult=new MobileCityGtfsAdapter().convert(transitFeed,refs);
        } catch(IOException e) {
            if("upload".equals(transitFeed.source()) || !scenario.mobileCity.allowSyntheticTransitFallback)
                throw new IOException("GTFS-Feed kann nicht als MobileCity-Transitnetz verwendet werden",e);
            transitResult=null;
            runSources.put("transitSource","modelled-fallback");
            runSources.put("transitFallbackReason",e.getMessage());
            provenance.put("transitFallbackReason",e.getMessage());
            return;
        }
        Files.copy(transitFeed.path(),out.resolve("gtfs-feed.zip"),StandardCopyOption.REPLACE_EXISTING);
        Map<String,Object> report=transitResult.provenance(transitFeed);
        report.put("demandReferenceYear",scenario.referenceYear);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("transit-provenance.json").toFile(),report);
        provenance.put("transitSourceReport","transit-provenance.json");
        provenance.put("transitFeedSha256",transitResult.feedSha256());
    }

    private void prepareCharging(Path out) throws IOException {
        List<MobileCityChargingSource.District> refs=districts.stream().map(d->
                new MobileCityChargingSource.District(d.id,d.geometry,Arrays.stream(d.ages).sum())).toList();
        chargingResult=new MobileCityChargingSource().resolve(scenario,refs,
                years.stream().map(Integer::parseInt).toList());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("charging-provenance.json").toFile(),
                chargingResult.provenance());
        provenance.put("chargingSourceReport","charging-provenance.json");
        if(chargingResult.regionalSnapshot()!=null)
            Files.write(out.resolve("charging-points.csv"),chargingResult.regionalSnapshot());
    }

    private void networks(Map<String,double[][]> m) throws IOException {
        for(String mode:List.of("car","bike","transit")) {
            List<String> nh=new ArrayList<>(List.of("nodeID","lat","lon","districtID","refNodeTrue","nodeActive"));if(mode.equals("transit"))nh.add("nodeType");
            List<List<String>> nodes=new ArrayList<>();
            if(mode.equals("transit") && transitResult!=null) for(var n:transitResult.nodes())nodes.add(row(n.id(),n.lat(),n.lon(),n.districtId(),n.refNodeTrue(),n.nodeActive(),n.nodeType()));
            else for(var d:districts){var r=row("N-"+d.id,d.representative.centroidLat,d.representative.centroidLon,d.id,1,1);if(mode.equals("transit"))r.add("0");nodes.add(r);}
            replace("INF"+mode+"Nodes",mode+"Nodes",nh,nodes);
            List<String> lh=new ArrayList<>(List.of("fromNodeID","toNodeID","distance","time","avSpeed","active"));lh.addAll(switch(mode){case "car"->List.of("maxAllowedSpeed","combustionToll","electricToll");case "bike"->List.of("infrastructureType");default->List.of("infrastructureType","mode","freq","name");});
            List<List<String>> links=new ArrayList<>();
            if(mode.equals("transit") && transitResult!=null) for(var l:transitResult.links())links.add(row(l.fromNodeId(),l.toNodeId(),l.distanceKm(),l.timeMinutes(),l.averageSpeedKmh(),l.active(),l.infrastructureType(),l.mode(),l.frequency(),l.name()));
            else for(int a=0;a<districts.size();a++)for(int b=a+1;b<districts.size();b++){
                var from=districts.get(a);var to=districts.get(b);int i=from.representative.index,j=to.representative.index;
                double[][] distance=m.get(mode.equals("bike")?"BIKE_DIS":"CAR_DIS"),time=m.get(mode.equals("bike")?"BIKE_TTC":mode.equals("car")?"CAR_TTC":"PUT_TTC");
                double km=(distance[i][j]+distance[j][i])/2000,minutes=(time[i][j]+time[j][i])/2,speed=km/minutes*60;
                if(km<=0 || minutes<=0)throw new IOException("Regionalnetz enthält nicht positive Verbindung");
                var r=row("N-"+from.id,"N-"+to.id,km,minutes,speed,1);r.addAll(switch(mode){case "car"->row((int)Math.ceil(speed),0,0);case "bike"->row(3);default->row(4,4,4,"Modelled transit");});links.add(r);
            }
            replace("INF"+mode+"Links",mode+"Links",lh,links);
        }
    }
    static double[][] readMatrix(Path file,List<Zone> zones) throws IOException {
        List<String> tokens=new ArrayList<>();
        try(var reader=new BufferedReader(new InputStreamReader(new BZip2CompressorInputStream(Files.newInputStream(file)),java.nio.charset.StandardCharsets.UTF_8))){
            if(!"$V;D3".equals(reader.readLine()))throw new IOException("Unerwartetes Matrixformat: "+file);
            String line;while((line=reader.readLine())!=null){if(line.startsWith("$NAMES"))break;if(line.startsWith("*")||line.isBlank())continue;tokens.addAll(Arrays.asList(line.trim().split("\\s+")));}
        }
        try {
            int n=Integer.parseInt(tokens.get(3));if(n!=zones.size()||tokens.size()!=4+n+n*n||Double.parseDouble(tokens.get(2))!=1)throw new IOException("Matrixdimension/Faktor ungültig");
            for(int i=0;i<n;i++)if(Long.parseLong(tokens.get(4+i))!=zones.get(i).id)throw new IOException("Matrix-Zonenreihenfolge stimmt nicht");
            double[][] result=new double[n][n];for(int i=0;i<n;i++)for(int j=0;j<n;j++){double value=Double.parseDouble(tokens.get(4+n+i*n+j));if(!Double.isFinite(value)||value<0)throw new IOException("Negative/ungültige Reiseimpedanz");result[i][j]=value;}return result;
        } catch(IndexOutOfBoundsException|NumberFormatException e){throw new IOException("Ungültige Matrix: "+file,e);}
    }
    private MobileCityCsv.Table table(String f,String k)throws IOException{return csv.get(f).table(k);}
    private void replace(String f,String k,List<String> h,List<List<String>> r){csv.get(f).tables.put(k,new MobileCityCsv.Table(new ArrayList<>(h),new ArrayList<>(r)));}
    private List<String> yearHeader(String first){List<String> h=row(first);h.addAll(years);return h;}
    private List<String> districtHeader(String first){return districtHeader(first,districts);}
    private static List<String> districtHeader(String first,List<District> selected){List<String> h=row(first);selected.forEach(d->h.add(d.id));return h;}
    private List<String> constant(String key,double value){List<String> r=row(key);for(String y:years)r.add(num(value));return r;}
    private void series(String f,String k,IntToDoubleFunction value){List<List<String>> rows=new ArrayList<>();for(int i=0;i<districts.size();i++)rows.add(constant(districts.get(i).id,value.applyAsDouble(i)));replace(f,k,yearHeader("districtID"),rows);}
    private static List<String> row(Object... values){List<String> row=new ArrayList<>();for(Object v:values)row.add(v instanceof Double d?num(d):String.valueOf(v));return row;}
    private static String num(double value){return MobileCityCsv.number(value);}
    private static double value(Double value){return value==null?0:value;}
    private static double score(Zone z,int purpose){return switch(purpose){case 0->sum(z,"Education_Primary","Education_Secondary","Education_Tertiary");case 1->z.attractivities.getOrDefault("Work",0.0);case 2->sum(z,"Shopping_Daily","Shopping_Other");default->sum(z,"Leisure_Indoor","Leisure_Outdoor");};}
    private static double sum(Zone z,String... keys){return Arrays.stream(keys).mapToDouble(k->z.attractivities.getOrDefault(k,0.0)).sum();}
    private static double area(District d){return MobileCityGeometry.project(d.geometry).getArea()/1_000_000;}
}
