#!/usr/bin/env python3
"""Build an isolated Config Tool copy, import, reload binaries and simulate at least three years.

The original tool and import package are read-only. A failed report retains all evidence.
Exit 0 means all checks passed; exit 1 means reference, import or simulation errors.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def patch_once(root, suffix, before, after, label, patches):
    paths = list(root.rglob(suffix))
    if len(paths) != 1:
        raise ValueError(f'{label}: expected one source file')
    path = paths[0]
    text = path.read_text(encoding='utf-8-sig')
    original = sha(path)
    if after not in text:
        if text.count(before) != 1:
            raise ValueError(f'{label}: source differs; review patch before use')
        path.write_text(text.replace(before, after), encoding='utf-8')
    patches.append({'patch': label, 'file': str(path.relative_to(root)), 'beforeSha256': original, 'afterSha256': sha(path)})


def run(args):
    work = args.work_dir.resolve()
    if work.exists():
        raise ValueError('Work directory already exists; use a new run directory')
    work.mkdir(parents=True)
    report = {'status': 'failed', 'patches': [], 'errors': [], 'nonFiniteCells': [], 'binaries': {}, 'contributionChecks': []}
    report_path = work / 'validation-report.json'
    try:
        source, imports = args.tool_source.resolve(), args.import_dir.resolve()
        report['inputHashes'] = {str(p.relative_to(imports)): sha(p) for p in sorted(imports.rglob('*')) if p.is_file() and p.suffix != '.zip'}
        report['toolSourceHashes'] = {str(p.relative_to(source)): sha(p) for p in sorted(source.rglob('*.cs')) if not {'bin', 'obj'} & set(p.relative_to(source).parts)}
        tool = work / 'ConfigTool'
        shutil.copytree(source, tool, ignore=shutil.ignore_patterns('.git', 'bin', 'obj', '*.meta'))
        patch_once(tool, 'ST1020_MessagePackFileLoader.cs', 'byte[] serialized = null;',
                   'if (!File.Exists(_filePath)) return default; // SyntheSis bootstrap: fresh study area\n            byte[] serialized = null;',
                   'Allow first import without pre-existing binaries', report['patches'])
        patch_once(tool, 'ST21213_CityConfigINFNetworkConfigsReader.cs',
                   'matrix = car.solve(graph, matrix, car.isElectric());',
                   'matrix = car.solve(graph, matrix, false);\n                    matrix = car.solve(graph, matrix, true); // SyntheSis: solve both matrices before scanning',
                   'Calculate both car matrices before validating both', report['patches'])
        patch_once(tool, 'ST21213_CityConfigINFNetworkConfigsReader.cs',
                   'var scenario = ST1010_StorageManager.Scenario;',
                   'var scenario = ST1010_StorageManager.Scenario;\n                if (scenario == null || ST1010_StorageManager.CityConfig == null) { Console.WriteLine("Routing probe deferred until initialized"); return; }',
                   'Defer routing probe during bootstrap; rerun after loading generated binaries', report['patches'])
        patch_once(tool, 'CAL5020_TECCarFleet.cs',
                   '\t\t\tif (scenarioDistrictYearlyData.numberOfChargingStations > scenarioDistrictYearlyData.maximumChargingStations)\n\t\t\t{\n\t\t\t\tscenarioDistrictYearlyData.maximumChargingStations = scenarioDistrictYearlyData.numberOfChargingStations;\n\t\t\t}\n\t\t\t\t\n\t\t}',
                   '\t\t\tif (scenarioDistrictYearlyData.numberOfChargingStations > scenarioDistrictYearlyData.maximumChargingStations)\n\t\t\t{\n\t\t\t\tscenarioDistrictYearlyData.maximumChargingStations = scenarioDistrictYearlyData.numberOfChargingStations;\n\t\t\t}\n\t\t\tConsole.WriteLine($"SYNTHESIS_CHARGERS;{_districtId};{year};{scenarioDistrictYearlyData.NumberOfChargingStations}");\n\t\t\t\t\n\t\t}',
                   'Expose consumed charger values to the isolated validation log', report['patches'])
        patch_once(tool, 'CAL4010_MODTrips.cs',
                   '\t\t\tscenario.carsharingMember[year] = carsharingMember;',
                   '\t\t\tscenario.carsharingMember[year] = carsharingMember;\n\t\t\tConsole.WriteLine($"SYNTHESIS_SHARING_MEMBERS;{year};{carsharingMember}");',
                   'Expose consumed carsharing membership to the isolated validation log', report['patches'])
        patch_once(tool, 'CAL4010_MODTrips.cs',
                   '\t\t\tseasonTicketRateOrigin = scenario.GetSeasonTicketRate(_originId);',
                   '\t\t\tConsole.WriteLine($"SYNTHESIS_SHARING_FLEET;{_originId};{year};{districtOriginData.carsharingStock.total.GetValueOrDefault()}");\n\n\t\t\tseasonTicketRateOrigin = scenario.GetSeasonTicketRate(_originId);',
                   'Expose consumed carsharing fleet to the isolated validation log', report['patches'])
        patch_once(tool, 'CAL4010_MODTrips.cs',
                   '\t\t\t\tsurroundingAreaTravelDemand[(originId,destinationId)][ageGroup, tripPurpose] = result;',
                   '\t\t\t\tsurroundingAreaTravelDemand[(originId,destinationId)][ageGroup, tripPurpose] = result;\n\t\t\t\tConsole.WriteLine(System.FormattableString.Invariant($"SYNTHESIS_SURROUNDING_DEMAND;{originId};{destinationId};{scenario.year};{ageGroup};{tripPurpose};{result}"));',
                   'Expose native surrounding demand to the isolated validation log', report['patches'])
        patch_once(tool, 'CAL6010_SOC.cs',
                   '            scenario.soz.seasonTicketRate[_districtId] = Math.Clamp(',
                   '            Console.WriteLine(System.FormattableString.Invariant($"SYNTHESIS_SEASON_TICKET_FACTORS;{_districtId};{year};{seasonTicketFactorVehiclesF};{qualityBike};{qualityTransit};{singleTicketPrice};{seasonTicketPrice}"));\n            scenario.soz.seasonTicketRate[_districtId] = Math.Clamp(',
                   'Expose native season-ticket factors to the isolated validation log', report['patches'])
        patch_once(tool, 'CAL6010_SOC.cs',
                   '            singleTicketPrice = \n                singleTicketPriceIndex[_districtId] / scenario.soz.singleTicketPriceIndex[_districtId] *\n                elasticitySeasonTicket.singleTicket;',
                   '            var previousSingleTicketPriceIndex = scenario.soz.singleTicketPriceIndex[_districtId];\n            singleTicketPrice = previousSingleTicketPriceIndex == 0\n                ? 0\n                : singleTicketPriceIndex[_districtId] / previousSingleTicketPriceIndex\n                  * elasticitySeasonTicket.singleTicket;',
                   'Treat unchanged zero transit-ticket index as zero elasticity contribution', report['patches'])
        env = dict(os.environ, DOTNET_CLI_HOME=str(work / 'dotnet-home'), DOTNET_CLI_TELEMETRY_OPTOUT='1')
        if args.nuget_cache:
            env['NUGET_PACKAGES'] = str(args.nuget_cache.resolve())
        dotnet = str(args.dotnet.resolve())
        def command(argv, logfile, required=True):
            with (work / logfile).open('w') as log:
                result = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, env=env,
                                        timeout=args.timeout, check=False)
            if result.returncode:
                if required:
                    raise RuntimeError(f'{logfile}: process exit {result.returncode}')
                report['errors'].append(f'{logfile}: process exit {result.returncode}')
        command([dotnet, 'build', str(tool / 'MobileCityConfigurationTool.csproj'), '-c', 'Release',
                 '--nologo', '-p:UseSharedCompilation=false', '-p:NuGetAudit=false'], 'build.log')
        build = tool / 'bin/Release/net8.0'
        area = build / 'StudyAreas/SynthesisValidation'
        shutil.copytree(imports, area / 'Import', ignore=shutil.ignore_patterns('*.zip'))
        (area / 'Config').mkdir()
        # Use the same block parser as template preparation; no year guessed from filenames.
        import importlib.util
        spec = importlib.util.spec_from_file_location('prepare_mobilecity', Path(__file__).with_name('prepare-mobilecity.py'))
        prep = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(prep)
        rows = prep.read_blocks(imports / 'StudyAreaConfig.csv')['studyAreaParametersList']
        year = int(dict((r[0], r[1]) for r in rows[1:])['referenceYear'])
        summary = json.loads((imports / 'summary.json').read_text()) if (imports / 'summary.json').is_file() else {}
        year = int(summary.get('referenceYear', year))
        if args.years < 3:
            raise ValueError('At least three years required: native season-ticket update skips the first follow-up year')
        end_year = int(dict((r[0], r[1]) for r in rows[1:])['endYear'])
        if year + args.years - 1 > end_year:
            raise ValueError('Requested validation horizon exceeds configured endYear')
        report['years'] = list(range(year, year + args.years))
        dll = str(build / 'MobileCityConfigurationTool.dll')
        command([dotnet, dll, '--init', 'SynthesisValidation'], 'import.log', required=False)
        command([dotnet, dll, '--studyArea', 'SynthesisValidation', '--validate-networks'], 'network-validation.log', required=False)
        # Separate process forces loading the generated CityConfig/Governance/StartScenario binaries.
        command([dotnet, dll, '--studyArea', 'SynthesisValidation', '--simulate', ','.join(map(str, report['years']))], 'simulation.log', required=False)
        for name in ['CityConfig.bin', 'GovernanceConfig.bin', 'StartScenario.bin']:
            path = area / 'Config' / name
            if not path.is_file() or path.stat().st_size == 0:
                report['errors'].append(f'Missing/empty binary: {name}')
            else:
                report['binaries'][name] = {'sha256': sha(path), 'bytes': path.stat().st_size}
        for logname in ['import.log', 'network-validation.log', 'simulation.log']:
            for line in (work / logname).read_text().splitlines():
                if re.search(r'(^Error:|Unhandled exception|Validation stopped|could not be initialized)', line):
                    report['errors'].append(line)
        simulation_log = (work / 'simulation.log').read_text()
        for y in report['years']:
            if f'Simulate year {y}' not in simulation_log:
                report['errors'].append(f'Simulation year {y} not reached')
        charger_values = {}
        for district, charger_year, value in re.findall(
                r'^SYNTHESIS_CHARGERS;([^;]+);(\d+);(\d+)$', simulation_log, re.MULTILINE):
            charger_values[(district, int(charger_year))] = int(value)
        report['nativeChargingPoints'] = [
            {'district': district, 'year': charger_year, 'value': value}
            for (district, charger_year), value in sorted(charger_values.items())
        ]
        sharing_members = {int(member_year): int(value) for member_year, value in re.findall(
            r'^SYNTHESIS_SHARING_MEMBERS;(\d+);(\d+)$', simulation_log, re.MULTILINE)}
        sharing_fleet = {}
        for district, fleet_year, value in re.findall(
                r'^SYNTHESIS_SHARING_FLEET;([^;]+);(\d+);(\d+)$', simulation_log, re.MULTILINE):
            sharing_fleet[(district, int(fleet_year))] = int(value)
        report['nativeCarsharingMembers'] = [
            {'year': member_year, 'value': value}
            for member_year, value in sorted(sharing_members.items())
        ]
        report['nativeCarsharingFleet'] = [
            {'district': district, 'year': fleet_year, 'value': value}
            for (district, fleet_year), value in sorted(sharing_fleet.items())
        ]
        surrounding_demand = {}
        for origin, destination, demand_year, age_group, purpose, value in re.findall(
                r'^SYNTHESIS_SURROUNDING_DEMAND;([^;]+);([^;]+);(\d+);(\d+);(\d+);([^;\r\n]+)$',
                simulation_log, re.MULTILINE):
            key = (origin, int(demand_year), int(purpose))
            surrounding_demand[key] = surrounding_demand.get(key, 0) + float(value)
        report['nativeSurroundingDemand'] = [
            {'origin': origin, 'year': demand_year, 'tripPurpose': purpose, 'value': value}
            for (origin, demand_year, purpose), value in sorted(surrounding_demand.items())
        ]
        output_files = list((area / 'Logs').rglob('*.csv'))
        report['outputCsvCount'] = len(output_files)
        if not output_files:
            report['errors'].append('No simulation CSV outputs')
        for path in output_files:
            with path.open(encoding='utf-8-sig') as stream:
                for line, row in enumerate(csv.reader(stream), 1):
                    for column, value in enumerate(row, 1):
                        if value.strip().lower() in {'nan', 'infinity', '-infinity', 'inf', '-inf'}:
                            report['nonFiniteCells'].append({'file': str(path.relative_to(area)), 'row': line, 'column': column, 'value': value})
        # Successful deserialization is insufficient: the native model must consume the contribution.
        if summary.get('updatedDistricts'):
            pop = prep.read_blocks(imports / 'POPinhabitants.csv')
            fleet = prep.read_blocks(imports / 'TECCarFleet.csv')
            chargers = prep.read_blocks(imports / 'REGchargers.csv')['numberOfChargingStations']
            sharing = prep.read_blocks(imports / 'TECCarsharing.csv')
            def input_value(table, district):
                column = table[0].index(str(year))
                return float(next(row[column] for row in table[1:] if row[0] == district))
            def input_optional_value(table, district):
                column = table[0].index(str(year))
                raw = next(row[column] for row in table[1:] if row[0] == district)
                return None if not raw.strip() else float(raw)
            for suffix, metric in [('_population.csv', 'population'), ('_carStock.Total.csv', 'privateCars'), ('_carStock.ShareElectric.csv', 'bevShare')]:
                matches = [p for p in output_files if p.name.endswith(suffix)]
                if len(matches) != 1:
                    report['errors'].append(f'Expected one {metric} output, found {len(matches)}')
                    continue
                with matches[0].open(encoding='utf-8-sig') as stream:
                    rows = list(csv.reader(stream))
                column = rows[1].index(str(year))
                actual = {r[0]: float(r[column]) for r in rows[2:] if r and len(r) > column and r[column]}
                for district in summary['updatedDistricts']:
                    expected = (sum(input_value(pop[f'population[AgeGroups.{g}]'], district) for g in ('Children', 'Adults', 'Seniors'))
                                if metric == 'population' else input_value(fleet['stock.shareElectric' if metric == 'bevShare' else 'stock.total'], district))
                    passed = district in actual and abs(actual[district] - expected) < (1e-5 if metric == 'bevShare' else .5)
                    report['contributionChecks'].append({'district': district, 'year': year, 'metric': metric,
                                                         'expected': expected, 'actual': actual.get(district), 'passed': passed})
                    if not passed:
                        report['errors'].append(f'Native {metric} differs from exported contribution for {district}')
            for district in summary['updatedDistricts']:
                configured = input_optional_value(chargers, district)
                if configured is None:
                    continue
                expected = int(configured)
                actual = charger_values.get((district, year))
                passed = actual == expected
                report['contributionChecks'].append({'district': district, 'year': year,
                                                     'metric': 'publicChargingPoints',
                                                     'expected': expected, 'actual': actual, 'passed': passed})
                if not passed:
                    report['errors'].append(f'Native publicChargingPoints differs from exported contribution for {district}')
            expected_members = int(input_value(sharing['carsharing.member'], 'default'))
            actual_members = sharing_members.get(year)
            passed = actual_members == expected_members
            report['contributionChecks'].append({'district': 'studyArea', 'year': year,
                                                 'metric': 'carsharingMembers',
                                                 'expected': expected_members,
                                                 'actual': actual_members, 'passed': passed})
            if not passed:
                report['errors'].append('Native carsharingMembers differs from exported contribution')
            for district in summary['updatedDistricts']:
                expected = int(input_value(sharing['carsharing.stock.total'], district))
                actual = sharing_fleet.get((district, year))
                passed = actual == expected
                report['contributionChecks'].append({'district': district, 'year': year,
                                                     'metric': 'carsharingFleet',
                                                     'expected': expected, 'actual': actual,
                                                     'passed': passed})
                if not passed:
                    report['errors'].append(f'Native carsharingFleet differs from exported contribution for {district}')
            districts = prep.read_blocks(imports / 'REGdistricts.csv')['districts']
            district_type = districts[0].index('districtType')
            surrounding_ids = [row[0] for row in districts[1:] if int(row[district_type]) == 0]
            if surrounding_ids:
                inbound = prep.read_blocks(imports / 'POPtripsSurrounding.csv')['tripShareToStudyArea']
                purpose_rows = {row[0]: row for row in inbound[1:]}
                purpose_names = ['Education', 'Work', 'Shopping', 'Leisure']
                age_names = ['Children', 'Adults', 'Seniors']
                trips = prep.read_blocks(imports / 'POPtrips.csv')
                for district in surrounding_ids:
                    share_column = inbound[0].index(district)
                    for purpose_index, purpose in enumerate(purpose_names):
                        share = float(purpose_rows[purpose][share_column])
                        expected = 0
                        for age in age_names:
                            population_value = input_value(pop[f'population[AgeGroups.{age}]'], district)
                            trip_rate = input_value(trips[f'averageDailyTrips[AgeGroups.{age}]'], purpose)
                            expected += population_value * trip_rate * share
                        actual = surrounding_demand.get((district, year, purpose_index))
                        passed = actual is not None and abs(actual - expected) <= max(1e-3, abs(expected) * 1e-5)
                        report['contributionChecks'].append({'district': district, 'year': year,
                                                             'metric': f'surroundingDemand.{purpose}',
                                                             'expected': expected, 'actual': actual,
                                                             'passed': passed})
                        if not passed:
                            report['errors'].append(f'Native surrounding demand differs from exported contribution for {district}/{purpose}')
        if not report['errors'] and not report['nonFiniteCells']:
            report['status'] = 'passed'
        report['configDirectory'] = str((area / 'Config').relative_to(work))
    except Exception as error:
        report['errors'].append(str(error))
    report['errors'] = list(dict.fromkeys(report['errors']))
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'report': str(report_path), 'status': report['status'],
                      'errors': len(report['errors']), 'nonFiniteCells': len(report['nonFiniteCells'])}))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dotnet', type=Path, required=True)
    parser.add_argument('--tool-source', type=Path, required=True)
    parser.add_argument('--import-dir', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--nuget-cache', type=Path)
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--years', type=int, default=3, help='Consecutive simulation years, minimum 3')
    sys.exit(run(parser.parse_args()))
