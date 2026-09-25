#!/usr/bin/env python3
"""Offline Rastatt source acceptance: Zensus, pinned KBA/BKG, BNetzA and KVV GTFS.

Needs the locally prepared tramigo-v2 profile, source archives, .NET 8 and ConfigTool.
The BKG GeoJSON is a pinned response fixture; this does not test today's live WFS.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

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
    report = {'status': 'failed', 'scope': 'Rastatt one-zone offline source acceptance',
              'sources': {}, 'checks': {}, 'errors': []}
    report_path = output / 'rastatt-source-report.json'
    try:
        sources = {
            'zensusPopulation': args.population_cache / CENSUS_VERSION / 'Zensus2022_Bevoelkerungszahl.zip',
            'zensusAges': args.structure_source / AGE,
            'zensusHouseholds': args.structure_source / HOUSEHOLDS,
            'kvvGtfs': args.gtfs,
            'bkgBoundaries': ROOT / 'backend/src/test/resources/mobilecity/bkg-rastatt.geojson',
            'kbaStock': ROOT / 'backend/src/main/resources/reference/kba-private-fleet-2026-07.csv',
            'bnetzaCharging': ROOT / 'backend/src/main/resources/reference/bnetza-public-charging-2026-09-01.csv.gz',
        }
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
        report['sources']['bkgBoundaries']['note'] = 'Pinned response fixture, not a live WFS check'

        profile = output / 'profiles'
        shutil.copytree(args.profile_dir / 'tramigo-v2', profile / 'tramigo-v2')
        structure_cache = output / 'structure-cache'
        structure_cache.mkdir()
        for name in (AGE, HOUSEHOLDS):
            (structure_cache / name).symlink_to((args.structure_source / name).resolve())
        package = output / 'run'
        command = ['mvn', '-o', '-q', '-Dtest=MobileCityRastattSourcesTest',
                   f'-Dsynthesis.mobilecity.rastatt.output={package}',
                   f'-Dsynthesis.mobilecity.rastatt.profile={profile}',
                   f'-Dsynthesis.mobilecity.rastatt.gtfs={args.gtfs.resolve()}',
                   f'-Dsynthesis.mobilecity.rastatt.populationCache={args.population_cache.resolve()}',
                   f'-Dsynthesis.mobilecity.rastatt.structureCache={structure_cache}', 'test']
        print('Exporting Rastatt from local observed sources...', flush=True)
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
                                          'errors': native.get('errors', []),
                                          'nonFiniteCells': native.get('nonFiniteCells', [])}
        if native_exit or not native_report.is_file() or report['checks']['native']['status'] != 'passed':
            raise RuntimeError('Native import or simulation failed; see native.log and native/validation-report.json')
        if len(report['checks']['native']['years']) != args.years:
            raise RuntimeError('Native simulation did not cover every requested year')
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
