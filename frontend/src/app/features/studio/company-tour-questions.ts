import { CompanyTourContext, CompanyTourImport, CompanyTourPoint } from '../../core/models/company-tour.model';

export interface TourContextQuestion { id: string; text: string; hint: string; }

export function hasTourPoint(point: CompanyTourPoint | undefined): boolean {
  return typeof point?.lon === 'number' && Number.isFinite(point.lon) && Math.abs(point.lon) <= 180 &&
    typeof point.lat === 'number' && Number.isFinite(point.lat) && Math.abs(point.lat) <= 90;
}

/** Questions follow the editable context, so answering one never hides other prerequisites. */
export function tourContextQuestions(view: CompanyTourImport, context: CompanyTourContext): TourContextQuestion[] {
  const questions: TourContextQuestion[] = [];
  const ask = (id: string, text: string, hint: string) => questions.push({ id, text, hint });
  if (!context.companyId?.trim()) ask('company', 'Zu welchem Unternehmen gehören diese Touren?', 'Unternehmenskennung unten ergänzen.');
  const tourCount = (n: number) => `${n} ${n === 1 ? 'Tour' : 'Touren'}`;
  const selected = context.tours.filter(t => t.included);
  if (!selected.length) { ask('selection', 'Welche Touren sollen zusätzlich berücksichtigt werden?', 'Mindestens eine Tour auswählen oder den Import bewusst ungenutzt lassen.'); return questions; }
  const missingDepots = selected.filter(t => {
    const address = context.depotAddresses?.[t.tourId];
    return (!address?.start.trim() && !hasTourPoint(t.start)) || (!address?.end.trim() && !hasTourPoint(t.end));
  }).length;
  if (missingDepots) ask('depots', `Wo beginnen und enden die Touren? Für ${tourCount(missingDepots)} fehlen Depotangaben.`,
    'Ein gemeinsames Start- und Enddepot unten einmal übernehmen oder unterschiedliche Adressen je Tour ergänzen.');
  const missingDepotPoints = selected.filter(t => !hasTourPoint(t.start) || !hasTourPoint(t.end)).length;
  if (missingDepotPoints) ask('depot-points', `Welche bestätigten Koordinaten haben die Depots? Für ${tourCount(missingDepotPoints)} ist die Ortszuordnung offen.`,
    'Gespeicherte Adressen bleiben erhalten. Koordinaten ergänzen; aus einer Adresse werden keine Positionen erfunden.');
  const missingVehicles = selected.filter(t => !view.vehicles?.some(v => v.vehicleId === t.vehicleId)).length;
  if (missingVehicles) ask('vehicles', `Welche konkreten Fahrzeuge fahren die Touren? Für ${tourCount(missingVehicles)} fehlt die Zuordnung.`,
    view.vehicles?.length ? 'In den Tourdetails ein gespeichertes Fahrzeug auswählen. Tourkennungen gelten nicht als Fahrzeugnachweis.' : 'Fahrzeugkatalog im Szenario ergänzen, speichern und die Importliste neu laden.');
  const missingTimes = selected.filter(t => !t.startTime || !t.returnTime).length;
  if (missingTimes) ask('times', `Wann verlassen die Fahrzeuge das Depot und wann kehren sie zurück? Für ${tourCount(missingTimes)} fehlen Zeiten.`,
    'Tourbeginn und Tourende je Tour ergänzen. Die Kundenankunft ersetzt keine Depotabfahrt.');
  const missingStops = [...new Set(selected.flatMap(t => t.stopOrder))].filter(id => !hasTourPoint(context.locations[id])).length;
  if (missingStops) ask('stop-points', `Wie sollen die ${missingStops} noch nicht verorteten Halte zugeordnet werden?`, 'Lokale Adress-/Koordinatentabelle hochladen oder die Punkte in den Tourdetails ergänzen.');
  const sourceDates = view.draft.tours.filter(t => selected.some(s => s.tourId === t.tourId)).map(t => t.serviceDate);
  if (!context.simulationDate) ask('date', 'Für welches Datum gelten die Touren?', 'Bezugsdatum ergänzen.');
  else if (sourceDates.some(d => d !== context.simulationDate) && !context.dateShiftConfirmed)
    ask('date', 'Sollen die Touren bewusst auf das abweichende Bezugsdatum verschoben werden?', 'Quelldatum beibehalten oder die Datumsverschiebung ausdrücklich bestätigen.');
  if (!context.sequenceConfirmed) ask('sequence', 'Entsprechen Gruppierung, Haltreihenfolge und Planzeiten der tatsächlichen Planung?', 'Touren prüfen und die Reihenfolge ausdrücklich bestätigen.');
  if (!context.windowsConfirmed) ask('windows', 'Was bedeuten die Kundenfenster und wie sollen Abweichungen behandelt werden?', 'Fensterregel wählen und bekannte Abweichungen prüfen. Unterschiedliche Fenster je Auftrag bleiben erhalten.');
  if (!context.locationsConfirmed) ask('locations-review', 'Sind die Halt- und Depotorte fachlich geprüft?', 'Nach der Ortszuordnung die Bestätigung setzen. Die Netzprüfung folgt im Zielmodell.');
  return questions;
}
