#!/usr/bin/env python3
"""Run the offline MobileCity geography matrix and validate every package natively.

Requires the prepared local tramigo-v2 model profile, .NET 8 runtime and ConfigTool.
The inputs deliberately use synthetic populations, a manual BEV share and the
explicit synthetic transit fallback; this checks geographic portability, not
regional calibration or automatic source coverage.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

CASES = (
    'city-berlin',
    'rural-uckermark',
    'border-kehl',
    'clipped-rastatt',
    'outside-germany-basel',
)
ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def invoke(command, cwd, log):
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=cwd, stdout=stream,
                                stderr=subprocess.STDOUT, check=False)
    return result.returncode


def main(args):
    output = args.work_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {
        'status': 'failed',
        'scope': 'synthetic geography regression; not regional calibration',
        'assumptions': ['synthetic population', 'manual 3% BEV share',
                        'synthetic transit fallback', 'local tramigo-v2 model profile'],
        'yearsRequested': args.years,
        'cases': [],
        'errors': [],
    }
    report_path = output / 'matrix-report.json'
    try:
        profile = args.profile_dir.resolve()
        tool = args.tool_source.resolve()
        dotnet = args.dotnet.resolve()
        for required in (profile / 'tramigo-v2' / 'template-manifest.json',
                         tool / 'MobileCityConfigurationTool.csproj', dotnet):
            if not required.is_file():
                raise FileNotFoundError(f'Missing local prerequisite: {required}')
        command = ['mvn', '-o', '-q',
                   '-Dtest=MobileCityRegionalExporterTest#regionalMatrixExportsCompletePackagesAcrossGeographies',
                   f'-Dsynthesis.mobilecity.matrixOutput={output}',
                   f'-Dsynthesis.mobilecity.matrixProfile={profile}', 'test']
        if invoke(command, ROOT / 'backend', output / 'export-test.log'):
            raise RuntimeError('Geography export test failed; see export-test.log')
        for name in CASES:
            mobile = output / name / 'mobilecity'
            item = {'name': name, 'exportZipSha256': digest(mobile / 'mobilecity-import.zip')}
            summary = json.loads((mobile / 'summary.json').read_text())
            item['studyPopulation'] = summary['inhabitants']['total']
            item['privateCars'] = summary['privateCars']
            item['studyDistricts'] = summary['districts']['studyArea']
            work = output / f'native-{name}'
            command = [sys.executable, str(ROOT / 'scripts' / 'validate-mobilecity.py'),
                       '--dotnet', str(dotnet), '--tool-source', str(tool),
                       '--import-dir', str(mobile), '--work-dir', str(work),
                       '--nuget-cache', str(args.nuget_cache.resolve()),
                       '--years', str(args.years)]
            print(f'Validating {name}...', flush=True)
            item['exitCode'] = invoke(command, ROOT, output / f'{name}.log')
            native_report = work / 'validation-report.json'
            if native_report.is_file():
                native = json.loads(native_report.read_text())
                checks = native.get('contributionChecks', [])
                item.update(status=native.get('status'),
                            simulationYears=native.get('years', []),
                            outputCsvCount=native.get('outputCsvCount', 0),
                            contributionChecks=len(checks),
                            failedContributionChecks=sum(check.get('passed') is not True for check in checks),
                            nonFiniteCells=len(native.get('nonFiniteCells', [])),
                            errors=native.get('errors', []),
                            binaries=list(native.get('binaries', {})))
            else:
                item.update(status='failed', errors=['No native validation report'])
            report['cases'].append(item)
            if item['exitCode'] or item['status'] != 'passed':
                report['errors'].append(f'{name}: native validation failed')
        if not report['errors']:
            report['status'] = 'passed'
    except Exception as error:
        report['errors'].append(str(error))
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'report': str(report_path), 'status': report['status'],
                      'cases': len(report['cases']), 'errors': report['errors']},
                     ensure_ascii=False))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', type=Path, required=True,
                        help='New directory for generated packages, native logs and report')
    parser.add_argument('--profile-dir', type=Path,
                        default=ROOT / 'backend' / 'data' / 'mobilecity')
    parser.add_argument('--tool-source', type=Path,
                        default=ROOT / 'validation' / 'mobilecity' / 'ConfigTool')
    parser.add_argument('--dotnet', type=Path,
                        default=ROOT / 'validation' / 'tools' / 'dotnet-8.0.425' / 'dotnet')
    parser.add_argument('--nuget-cache', type=Path, default=ROOT / '.runtime' / 'nuget')
    parser.add_argument('--years', type=int, default=11)
    options = parser.parse_args()
    if options.years < 3:
        parser.error('--years must be at least 3')
    sys.exit(main(options))
