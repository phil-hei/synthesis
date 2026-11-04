import type { FeatureCollection, Point } from 'geojson';
import type { Region } from './scenario.model';
export type EnergyCarrier = 'electricity' | 'cng' | 'lng' | 'lpg' | 'hydrogen';
export interface EnergyConnector { type: string; sourceType: string; powerKw: number | null; count: number | null; }
export interface EnergyPoint { id: string; evseId: string | null; connectors: EnergyConnector[]; powerAssociationKnown: boolean; }
export interface EnergyOffer { carrier: EnergyCarrier; points: EnergyPoint[]; pressureBars: number[]; connectors: EnergyConnector[]; }
export interface EnergyStation {
  id: string; name: string; longitude: number; latitude: number;
  source: 'bnetza' | 'osm'; access: 'public' | 'restricted' | 'unknown';
  coordinateKind: string; hgvAccess: string; observedOperatingStatus: string;
  chargingPoints: number | null; facilityNominalPowerKw: number | null;
  offers: EnergyOffer[]; qualityFlags: string[]; possibleDuplicateIds: string[];
  referenceDate?: string; sourceTimestamp?: string | null;
  openingHours?: { kind: string; weekdays?: string; times?: string };
}
export interface EnergyGeoJson extends FeatureCollection<Point, EnergyStation> {
  contract: string; region: Region; referenceYear: number;
  configuration: { enabled: boolean; useBnetza: boolean; useOsm: boolean };
}
export interface EnergySource {
  id: string; status: string; sha256?: string; referenceDate?: string;
  retrievedAt?: string; license?: string; attribution?: string; message?: string;
  metadata?: { referenceDate: string; sourceSha256: string; license: string; attribution: string };
}
export interface EnergyQuality {
  contract: string; status: 'inventory-only' | 'failed'; stationRecords: number;
  recordsByCarrier: Partial<Record<EnergyCarrier, number>>;
  recordsByAccess: Partial<Record<EnergyStation['access'], number>>;
  observedRegisterChargingPoints: number | null;
  sources: EnergySource[]; qualityFlagCounts: Partial<Record<string, number>>;
  excluded: Record<string, number>; errors: string[];
  networkAccessVerified: false; vehicleCompatibilityVerified: false; energyStateSimulated: false;
}
export const ENERGY_LABELS: Record<EnergyCarrier, string> = {
  electricity: 'Strom', cng: 'CNG', lng: 'LNG', lpg: 'LPG', hydrogen: 'Wasserstoff'
};
/** No source text enters an HTML parser. In particular, station names are untrusted data. */
export function energyPopup(station: EnergyStation): HTMLElement {
  const box = document.createElement('div');
  box.style.overflowWrap = 'anywhere';
  const title = document.createElement('strong'); title.textContent = station.name; box.append(title);
  const line = (text: string): void => { const row = document.createElement('div'); row.textContent = text; box.append(row); };
  line(station.offers.map(offer => ENERGY_LABELS[offer.carrier]).join(', '));
  line(station.access === 'public' ? 'Öffentlicher Zugang laut Quelle' : station.access === 'restricted' ? 'Zugang beschränkt' : 'Öffentlicher Zugang ungeklärt');
  line(station.source === 'bnetza' ? `BNetzA · Stand ${station.referenceDate ?? 'unbekannt'}` : `OpenStreetMap · Objektstand ${station.sourceTimestamp ?? 'unbekannt'}`);
  if (station.chargingPoints != null) line(`${station.chargingPoints} Ladepunkte laut Quelle`);
  for (const offer of station.offers) {
    if (offer.pressureBars.length) line(`Druckstufen: ${offer.pressureBars.join(' / ')} bar · Fahrzeugeignung ungeprüft`);
    for (const point of offer.points) line(`${point.evseId ?? point.id}: ${point.connectors.map(c => `${c.sourceType}${c.powerKw != null ? ` (${c.powerKw} kW)` : ''}`).join(', ')}`);
    if (offer.connectors.length) line(offer.connectors.map(c => `${c.type}${c.count != null ? ` × ${c.count}` : ''}${c.powerKw != null ? ` (${c.powerKw} kW)` : ''}`).join(', '));
  }
  if (station.possibleDuplicateIds.length) line('Mögliche weitere Quellenmeldung desselben Standorts');
  if (station.coordinateKind === 'bounding-box-center') line('Flächenmittelpunkt; Zufahrt nicht bekannt');
  line('Live-Verfügbarkeit, Straßenzugang und Lkw-Eignung ungeprüft');
  return box;
}
