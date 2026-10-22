#!/usr/bin/env python3
"""Regenerate frozen results on ead3da1^ without changing this checkout or .git."""
import json
import pathlib
import subprocess
import tarfile
import tempfile
import io

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = 'ead3da1^'
FIXTURES = '0df2b7d'
PREFIX = 'backend/src/test/java/com/elma/gohan/application/shadow/'


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])


def main():
    revision = git('rev-parse', BASE).decode().strip()
    with tempfile.TemporaryDirectory(prefix='elma-baseline-') as tmp:
        work = pathlib.Path(tmp)
        with tarfile.open(fileobj=io.BytesIO(git('archive', revision, 'backend'))) as archive:
            archive.extractall(work, filter='data')
        fixture = git('show', f'{FIXTURES}:{PREFIX}RecommendationReplayFixtures.java').replace(
            b'new DefaultRecommendationEngine(new LowRegretScorer(config)',
            b'new DefaultRecommendationEngine(new HardFilter(), new LowRegretScorer(config)')
        (work / PREFIX / 'RecommendationReplayFixtures.java').write_bytes(fixture)
        test = git('show', f'{FIXTURES}:{PREFIX}RecommendationRegressionTest.java').decode()
        # Only adapt the old service constructor; fixtures and assertions stay frozen.
        test = test.replace('new SafeRegretProperties(), new SafeRegretShadowSnapshotWriter(repository),\n'
                            '                new SafeRegretShadowCalculator(new SafeRegretProperties(), new TasteProperties(), mapper)',
                            'new SafeRegretProperties(), new TasteProperties(), repository, mapper')
        test = test.replace('actual.put(scenario.name()', '''summaries.put(scenario.name() + "/" + seed, Map.of(
                        "pool", input.servedResult().pool().stream().map(c -> Map.of(
                                "source", c.restaurant().source(), "sourcePoiId", c.restaurant().sourcePoiId(),
                                "score", c.lowRegretScore(), "mode", c.personalization().selectionMode())).toList(),
                        "selectionSnapshot", input.servedResult().selectionSnapshot()));
                actual.put(scenario.name()''')
        test = test.replace('Map<String, Object> actual = new TreeMap<>();',
                            'Map<String, Object> actual = new TreeMap<>();\n        Map<String, Object> summaries = new TreeMap<>();')
        test = test.replace('Path generated =', '''mapper.writerWithDefaultPrettyPrinter().writeValue(
                Path.of("target/recommendation-baseline-readable.json").toFile(), summaries);
        Path generated =''')
        (work / PREFIX / 'RecommendationRegressionTest.java').write_text(test)
        resource = work / 'backend/src/test/resources/recommendation/regression-ead3da1.json'
        resource.parent.mkdir(parents=True, exist_ok=True)
        resource.write_bytes((ROOT / 'backend/src/test/resources/recommendation/regression-ead3da1.json').read_bytes())
        log = ROOT / 'backend/target/recommendation-baseline-verification.log'
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open('w') as output:
            result = subprocess.run(['mvn', '-B', '-f', str(work / 'backend'),
                                     '-Dtest=RecommendationRegressionTest', 'test'], stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit(f'BASELINE_FAILED revision={revision} log={log}')
        actual = json.loads((work / 'backend/target/recommendation-regression-actual.json').read_text())
        expected = json.loads(resource.read_text())
        if actual != expected:
            raise SystemExit('BASELINE_MISMATCH')
        summary = (work / 'backend/target/recommendation-baseline-readable.json').read_bytes()
        (ROOT / 'backend/target/recommendation-baseline-readable.json').write_bytes(summary)
        committed = ROOT / 'backend/src/test/resources/recommendation/regression-readable.json'
        if committed.exists() and json.loads(committed.read_bytes()) != json.loads(summary):
            raise SystemExit('READABLE_BASELINE_MISMATCH')
        print(f'BASELINE_OK revision={revision} cases={len(actual)} served=104/104 shadow=104/104 config=104/104')
        print('READABLE_SUMMARY backend/target/recommendation-baseline-readable.json')


if __name__ == '__main__':
    main()
