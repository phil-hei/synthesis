import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Scenario } from '../../core/models/scenario.model';
import { emptyOperations, emptyVehicleConfiguration, OperationsPreview, VehicleEnergyLedger } from '../../core/models/vehicle-operations.model';
import { ApiService } from '../../core/services/api.service';
import { EnergyOperationsPanelComponent } from './energy-operations-panel.component';

describe('EnergyOperationsPanelComponent', () => {
  const api = { validateVehicleOperations: vi.fn(), artifactJson: vi.fn(), artifactUrl: vi.fn((_id, path) => `/artifacts/${path}`) };
  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({imports:[EnergyOperationsPanelComponent], providers:[{provide:ApiService, useValue:api}]}).compileComponents();
  });
  function scenario(id = 'one'): Scenario {
    return {id, targetModels:['sumo'], region:{minLon:8,maxLon:9,minLat:48,maxLat:49}, referenceYear:2026,
      energyInfrastructure:{enabled:true,useBnetza:true,useOsm:false,verifyNetworkAccess:false,maxSnapDistanceM:50},
      vehicleConfiguration:emptyVehicleConfiguration(), vehicleOperations:{...emptyOperations(), enabled:true}} as Scenario;
  }
  function preview(): OperationsPreview {
    return {valid:true,errors:[],vehicleConfiguration:emptyVehicleConfiguration(),vehicleOperations:{...emptyOperations(),enabled:true},vehicleConfigurationSha256:'vehicles',operationsSha256:'operations'};
  }
  function create() {
    const f = TestBed.createComponent(EnergyOperationsPanelComponent); f.componentRef.setInput('scenario', scenario()); f.componentRef.setInput('jobState','DONE');
    f.detectChanges(); return f;
  }
  it('shows unknown energy and explicit unassigned demand without inventing a zero state', () => {
    api.validateVehicleOperations.mockReturnValue(of(preview()));
    api.artifactJson.mockReturnValue(of({contract:'synthesis-vehicle-continuity-v1',status:'review-required',configurationSha256:'operations',vehicleConfigurationSha256:'vehicles',
      assignedSegments:2,selectedSegments:3,knownEnergySegments:0,issues:2,bindingsNotSelected:[],vehicles:[{vehicleId:'ev-1',unit:'kWh',balanceAfter:null,stateValid:false}]}));
    const f=create(); expect(f.nativeElement.textContent).toContain('2 von 3 Fahrtsegmenten'); expect(f.nativeElement.textContent).toContain('ev-1: Energie unbekannt');
    expect(f.nativeElement.textContent).toContain('prüfpflichtig'); expect(f.nativeElement.textContent).toContain('Lade-/Tankhalte und Energiegrenzen steuern den Verkehr nicht'); f.destroy();
  });
  it('ignores delayed scenario results and artifacts from a different vehicle configuration', () => {
    const response=new Subject<VehicleEnergyLedger>(); api.validateVehicleOperations.mockReturnValue(of(preview())); api.artifactJson.mockReturnValue(response);
    const f=create(); f.componentRef.setInput('scenario',scenario('other')); f.componentRef.setInput('jobState','RUNNING'); f.detectChanges();
    response.next({status:'tracked',configurationSha256:'operations',vehicleConfigurationSha256:'vehicles'} as VehicleEnergyLedger); response.complete();
    expect(f.componentInstance.ledger).toBeNull();
    api.artifactJson.mockReturnValue(of({status:'tracked',configurationSha256:'operations',vehicleConfigurationSha256:'stale'}));
    f.componentRef.setInput('jobState','DONE'); f.detectChanges(); expect(f.componentInstance.ledger).toBeNull(); f.destroy();
  });
  it('preserves the current draft after invalid imports and cancels a file read on scenario switch', async () => {
    api.validateVehicleOperations.mockReturnValue(of(preview())); api.artifactJson.mockReturnValue(of(null));
    const f=create(), c=f.componentInstance, original=c.scenario.vehicleOperations;
    api.validateVehicleOperations.mockReturnValue(of({...preview(),valid:false,errors:[{path:'bindings',message:'Doppelte Fahrt'}]}));
    await c.upload({target:{files:[{size:10,text:async()=>'{"example":true}'}],value:'file.json'}} as unknown as Event);
    expect(c.message).toContain('Doppelte Fahrt'); expect(c.scenario.vehicleOperations).toBe(original); expect(c.working).toBe(false);
    let resolve!: (value:string)=>void; const content=new Promise<string>(done=>resolve=done);
    const pending=c.upload({target:{files:[{size:10,text:()=>content}],value:'file.json'}} as unknown as Event);
    f.componentRef.setInput('scenario',scenario('other')); f.componentRef.setInput('jobState','RUNNING'); f.detectChanges(); resolve('{}'); await pending;
    expect(c.working).toBe(false); expect(c.message).toBe(''); f.destroy();
  });
  it('shows native chains separately from the energy ledger and hides stale chain results', () => {
    api.validateVehicleOperations.mockReturnValue(of(preview()));
    const report={contract:'synthesis-native-vehicle-chains-v1',status:'complete',phase:'executed',configurationSha256:'operations',vehicleConfigurationSha256:'vehicles',
      assignedSegments:3,physicalVehicles:1,completedSegments:3,unfinishedSegments:0,notDepartedSegments:0,unassignedSegments:1,
      vehicles:[{vehicleId:'van-1',nativeVehicleId:'physical.van',nativeCompleted:true,teleports:0,segments:[{},{},{}]}]};
    api.artifactJson.mockImplementation((_id, path) => of(path === 'vehicles/native-chains.json' ? report : null));
    const f=create(), input=scenario(); input.vehicleOperations!.chainTrips=true;
    f.componentRef.setInput('scenario',input); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('3 Fahrtsegmente mit 1 nativen Fahrzeugen');
    expect(f.nativeElement.textContent).toContain('van-1: 3 Etappen'); expect(f.nativeElement.textContent).not.toContain('Fahrzeugbilanz des letzten Laufs');
    api.artifactJson.mockImplementation((_id, path) => of(path === 'vehicles/native-chains.json' ? {...report,vehicleConfigurationSha256:'stale'} : null));
    f.componentRef.setInput('scenario',{...input}); f.detectChanges(); expect(f.componentInstance.chains).toBeNull();
    f.componentInstance.scenario.vehicleOperations!.enabled=false; f.componentInstance.operationsChanged();
    expect(f.componentInstance.scenario.vehicleOperations!.chainTrips).toBe(false); f.destroy();
  });
});
