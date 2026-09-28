#!/usr/bin/env python3
"""Rastatt source acceptance with pinned or live BKG municipality boundaries.

Needs the locally prepared tramigo-v2 profile, source archives, .NET 8 and ConfigTool.
The default one-zone case uses a pinned BKG response; --case cross-municipality
fetches today's BKG WFS and retains its exact response for review.
"""
import argparse
import csv
import io
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
AGE = 'age5-88965e23852b901c2eeb27502688777934ef2d1a4ed20f0e6544b773b35005c5.zip'
HOUSEHOLDS = 'household-size-fdb709378848ad67f20f71e2db33dc1a23c09f23034e507ebc549990f4a8dc58.zip'
CENSUS_VERSION = 'zensus-2022-2024-10-08'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def invoke(command, cwd, log):
    with log.open('w', encoding='utf-8') as output:
        return subprocess.run(command, cwd=cwd, stdout=output,
                              stderr=subprocess.STDOUT, check=False).returncode


def main(args):
    output = args.work_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    live = args.case == 'cross-municipality'
    report = {'status': 'failed', 'scope': ('Rastatt cross-municipality live BKG source acceptance'
                                           if live else 'Rastatt one-zone offline source acceptance'),
              'case': args.case,
              'sources': {}, 'checks': {}, 'errors': []}
    report_path = output / 'rastatt-source-report.json'
    try:
        sources = {
            'zensusPopulation': args.population_cache / CENSUS_VERSION / 'Zensus2022_Bevoelkerungszahl.zip',
            'zensusAges': args.structure_source / AGE,
            'zensusHouseholds': args.structure_source / HOUSEHOLDS,
            'kvvGtfs': args.gtfs,
            'kbaStock': ROOT / 'backend/src/main/resources/reference/kba-private-fleet-2026-07.csv',
            'bnetzaCharging': ROOT / 'backend/src/main/resources/reference/bnetza-public-charging-2026-09-01.csv.gz',
        }
        if not live:
            sources['bkgBoundaries'] = ROOT / 'backend/src/test/resources/mobilecity/bkg-rastatt.geojson'
        for name, path in sources.items():
            path = path.resolve()
            if not path.is_file():
                raise FileNotFoundError(f'{name} source missing: {path}')
            report['sources'][name] = {'path': str(path), 'sha256': sha256(path)}
        for path in (args.profile_dir / 'tramigo-v2/template-manifest.json',
                     args.tool_source / 'MobileCityConfigurationTool.csproj', args.dotnet):
            if not path.is_file():
                raise FileNotFoundError(f'Local prerequisite missing: {path}')
        report['sources']['zensusPopulation']['referenceDate'] = '2022-05-15'
        report['sources']['zensusAges']['referenceDate'] = '2022-05-15'
        report['sources']['zensusHouseholds']['referenceDate'] = '2022-05-15'
        report['sources']['kbaStock']['referenceDate'] = '2026-07-01'
        report['sources']['bnetzaCharging']['snapshotDate'] = '2026-09-01'
        if not live:
            report['sources']['bkgBoundaries']['note'] = 'Pinned response fixture, not a live WFS check'

        profile = output / 'profiles'
        shutil.copytree(args.profile_dir / 'tramigo-v2', profile / 'tramigo-v2')
        structure_cache = output / 'structure-cache'
        structure_cache.mkdir()
        for name in (AGE, HOUSEHOLDS):
            (structure_cache / name).symlink_to((args.structure_source / name).resolve())
        package = output / 'run'
        test_class = ('MobileCityCrossMunicipalitySourcesTest' if live
                      else 'MobileCityRastattSourcesTest')
        command = ['mvn', '-o', '-q', f'-Dtest={test_class}',
                   f'-Dsynthesis.mobilecity.rastatt.output={package}',
                   f'-Dsynthesis.mobilecity.rastatt.profile={profile}',
                   f'-Dsynthesis.mobilecity.rastatt.gtfs={args.gtfs.resolve()}',
                   f'-Dsynthesis.mobilecity.rastatt.populationCache={args.population_cache.resolve()}',
                   f'-Dsynthesis.mobilecity.rastatt.structureCache={structure_cache}', 'test']
        print(('Exporting cross-municipality Rastatt with live BKG WFS...' if live
               else 'Exporting Rastatt from local observed sources...'), flush=True)
        if invoke(command, ROOT / 'backend', output / 'export-test.log'):
            raise RuntimeError('Rastatt source export failed; see export-test.log')
        report['checks']['sourceIntegration'] = json.loads((package / 'source-check.json').read_text())
        mobile = package / 'mobilecity'
        report['checks']['exportZipSha256'] = sha256(mobile / 'mobilecity-import.zip')
        for key, filename in (('fleet', 'fleet-provenance.json'),
                              ('transit', 'transit-provenance.json'),
                              ('charging', 'charging-provenance.json')):
            report['checks'][key] = json.loads((mobile / filename).read_text())
        report['checks']['inventory'] = json.loads((mobile / 'data-inventory.json').read_text())
        if live:
            boundary = mobile / 'fleet-boundaries.geojson'
            if not boundary.is_file():
                raise FileNotFoundError('Live BKG boundary response not retained in export')
            fleet = report['checks']['fleet']
            boundary_sha = sha256(boundary)
            if boundary_sha != fleet.get('boundarySha256'):
                raise RuntimeError('Retained BKG response hash differs from fleet provenance')
            if not str(fleet.get('boundarySource', '')).startswith('https://sgx.geodatenzentrum.de/wfs_vg250?'):
                raise RuntimeError('Fleet source is not the live BKG WFS')
            report['sources']['bkgBoundaries'] = {
                'url': fleet['boundarySource'], 'sha256': boundary_sha,
                'snapshotPath': str(boundary),
                'responseTime': fleet.get('boundarySnapshotTime'),
                'note': 'Live BKG WFS response retained; may change on later runs',
            }
            integration = report['checks']['sourceIntegration']
            zones = integration.get('zones', [])
            if len(zones) < 2 or any(zone.get('bkgCoverage', 0) < .999
                                     or zone.get('gtfsAccessNodes') != 1 for zone in zones):
                raise RuntimeError('One or more zones lack full KBA/BKG or GTFS access evidence')
            with zipfile.ZipFile(mobile / 'gtfs-feed.zip') as archive:
                stop_file = next((name for name in archive.namelist()
                                  if name.endswith('stops.txt')), None)
                if stop_file is None:
                    raise RuntimeError('Cropped GTFS snapshot lacks stops.txt')
                for zone in zones:
                    zone['kvvStopsInZone'] = 0
                with archive.open(stop_file) as raw:
                    for stop in csv.DictReader(io.TextIOWrapper(raw, encoding='utf-8-sig')):
                        lat, lon = float(stop['stop_lat']), float(stop['stop_lon'])
                        for zone in zones:
                            b = zone['bounds']
                            if b['minLat'] <= lat < b['maxLat'] and b['minLon'] <= lon < b['maxLon']:
                                zone['kvvStopsInZone'] += 1
            if any(zone['kvvStopsInZone'] == 0 for zone in zones):
                raise RuntimeError('A study zone has no local KVV stop in the cropped feed')
            if sum(zone['kvvStopsInZone'] for zone in zones) != integration['kvvStopsInRegion']:
                raise RuntimeError('Per-zone KVV stop counts differ from the crop report')
        native_dir = output / 'native'
        command = [sys.executable, str(ROOT / 'scripts/validate-mobilecity.py'),
                   '--dotnet', str(args.dotnet.resolve()),
                   '--tool-source', str(args.tool_source.resolve()),
                   '--import-dir', str(mobile), '--work-dir', str(native_dir),
                   '--nuget-cache', str(args.nuget_cache.resolve()), '--years', str(args.years)]
        print('Validating native import and simulation...', flush=True)
        native_exit = invoke(command, ROOT, output / 'native.log')
        native_report = native_dir / 'validation-report.json'
        if native_report.is_file():
            native = json.loads(native_report.read_text())
            report['checks']['native'] = {'status': native.get('status'),
                                          'years': native.get('years', []),
                                          'outputCsvCount': native.get('outputCsvCount', 0),
                                          'contributionChecks': len(native.get('contributionChecks', [])),
                                          'failedContributionChecks': sum(
                                              check.get('passed') is not True
                                              for check in native.get('contributionChecks', [])),
                                          'errors': native.get('errors', []),
                                          'nonFiniteCells': native.get('nonFiniteCells', [])}
        if native_exit or not native_report.is_file() or report['checks']['native']['status'] != 'passed':
            raise RuntimeError('Native import or simulation failed; see native.log and native/validation-report.json')
        if len(report['checks']['native']['years']) != args.years:
            raise RuntimeError('Native simulation did not cover every requested year')
        if report['checks']['native']['failedContributionChecks']:
            raise RuntimeError('Native model did not preserve every checked contribution')
        report['status'] = 'passed'
    except Exception as error:
        report['errors'].append(str(error))
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'status': report['status'], 'report': str(report_path),
                      'errors': report['errors']}, ensure_ascii=False))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', type=Path, required=True, help='New output directory')
    parser.add_argument('--case', choices=('offline', 'cross-municipality'), default='offline',
                        help='Pinned one-zone fixture or live BKG multi-zone acceptance')
    parser.add_argument('--population-cache', type=Path,
                        default=ROOT / 'backend/data/census-cache')
    parser.add_argument('--structure-source', type=Path,
                        default=ROOT / 'validation/mobilecity/census-structure-spike/java/cache')
    parser.add_argument('--gtfs', type=Path, default=ROOT / 'validation/kvv-gtfs.zip')
    parser.add_argument('--profile-dir', type=Path, default=ROOT / 'backend/data/mobilecity')
    parser.add_argument('--tool-source', type=Path,
                        default=ROOT / 'validation/mobilecity/ConfigTool')
    parser.add_argument('--dotnet', type=Path,
                        default=ROOT / 'validation/tools/dotnet-8.0.425/dotnet')
    parser.add_argument('--nuget-cache', type=Path, default=ROOT / '.runtime/nuget')
    parser.add_argument('--years', type=int, default=11)
    options = parser.parse_args()
    if options.years < 3:
        parser.error('--years must be at least 3')
    sys.exit(main(options))
