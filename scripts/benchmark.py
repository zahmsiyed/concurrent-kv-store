#!/usr/bin/env python3
"""Fresh server/data directory per scenario. Requires Python 3 and Java 21, no pip packages."""
import argparse
import json
import pathlib
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operations', type=int, default=10000)
    parser.add_argument('--warmup', type=int, default=1000)
    parser.add_argument('--keys', type=int, default=1000)
    parser.add_argument('--value-bytes', type=int, default=256)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--hardware', required=True, help='CPU, memory, disk/filesystem, and relevant environment details')
    parser.add_argument('--output', type=pathlib.Path, default=ROOT / 'benchmarks/runs')
    args = parser.parse_args()
    if args.operations < 64:
        parser.error('operations must be at least 64 for the concurrency matrix')
    args.output.mkdir(parents=True, exist_ok=True)
    for name, writes, compact in [('read-only', 0, False), ('read95-write5', 5, False),
                                  ('read50-write50', 50, False), ('write-only', 100, False),
                                  ('compaction-50-50', 50, True)]:
        for concurrency in (1, 4, 16, 64):
            label = f'{name}-c{concurrency}'
            with tempfile.TemporaryDirectory(prefix='kv-bench-') as tmp:
                log = pathlib.Path(tmp) / 'server.log'
                with log.open('w') as stream:
                    server = subprocess.Popen(['java', '-jar', str(ROOT / 'target/kv-store.jar'),
                                               'server', '--port', '0', '--data', str(pathlib.Path(tmp) / 'data')],
                                              stdout=stream, stderr=subprocess.STDOUT, cwd=ROOT)
                try:
                    deadline = time.monotonic() + 20
                    while 'READY port=' not in log.read_text():
                        if server.poll() is not None or time.monotonic() > deadline:
                            raise RuntimeError(f'server did not start: {log.read_text()}')
                        time.sleep(.02)
                    port = log.read_text().split('READY port=')[1].split()[0]
                    result = args.output / f'{label}.json'
                    subprocess.run(['java', '-jar', str(ROOT / 'target/kv-store.jar'), 'load',
                                    '--url', f'http://127.0.0.1:{port}', '--concurrency', str(concurrency),
                                    '--operations', str(args.operations), '--warmup', str(args.warmup),
                                    '--keys', str(args.keys), '--value-bytes', str(args.value_bytes),
                                    '--writes', str(writes), '--seed', str(args.seed),
                                    '--compact', str(compact).lower(), '--hardware', args.hardware,
                                    '--output', str(result)], check=True, stdout=subprocess.DEVNULL, cwd=ROOT)
                    report = json.loads(result.read_text())
                    assert report['errors'] == 0 and report['compaction']['errors'] == 0
                    assert report['successfulOperations'] == args.operations
                    print(f'{label}: {report["throughputOpsPerSecond"]:.1f} ops/s; p99 {report["latencyMicros"]["p99"]:.1f} us', flush=True)
                finally:
                    server.terminate()
                    try:
                        server.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait()


if __name__ == '__main__':
    main()
