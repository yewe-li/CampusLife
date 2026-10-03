#!/usr/bin/env python3
"""Run isolated browser checks against a guarded, disposable test-data fixture.

Requires Java 21, Maven (or the wrapper), Node 20+, npm ci, and MySQL/Redis.
Only campuslife_test and Redis DB 1/campuslife:test:* are reset. Do not run in
parallel with Maven integration tests. The application and browser are temporary.
"""
import os
from pathlib import Path
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def configured_environment():
    environment = os.environ.copy()
    path = ROOT / '.env'
    if path.exists():
        for number, line in enumerate(path.read_text().splitlines(), 1):
            parts = shlex.split(line, comments=True)
            if not parts:
                continue
            if len(parts) != 1 or '=' not in parts[0]:
                raise ValueError(f'.env line {number}: use KEY=value assignments, not shell commands')
            key, value = parts[0].split('=', 1)
            if not key or not key.replace('_', '').isalnum() or key[0].isdigit():
                raise ValueError(f'.env line {number}: invalid variable name')
            # Explicit CI/shell configuration takes precedence over local defaults.
            environment.setdefault(key, value)
    return environment


def main():
    if os.name != 'posix':
        raise SystemExit('Run browser-tests.py on macOS/Linux or WSL; ordinary Java builds also support Windows.')
    node = os.environ.get('NODE_BIN') or shutil.which('node')
    if not node:
        raise SystemExit('Node.js 20+ is needed only for browser tests. Install Node, then run npm ci.')
    if not (ROOT / 'node_modules/playwright').is_dir():
        raise SystemExit('Browser dependency missing: run npm ci in the project root first.')
    environment = configured_environment()
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    environment['CAMPUSLIFE_BROWSER_PORT'] = str(port)
    environment['CAMPUSLIFE_BROWSER_URL'] = f'http://127.0.0.1:{port}'
    environment['CAMPUSLIFE_BROWSER_FIXTURE'] = 'dedicated-test'
    maven = shutil.which('mvn') or str(ROOT / 'mvnw')
    command = [maven, '-B', '-ntp', 'test-compile', 'spring-boot:test-run',
               '-Dspring-boot.run.main-class=com.campuslife.support.BrowserTestApplication']
    folder = ROOT / 'target/browser-tests'
    folder.mkdir(parents=True, exist_ok=True)
    log_path = folder / 'backend.log'
    print('Starting an isolated application; resetting campuslife_test and its Redis test prefix.', flush=True)
    with log_path.open('w') as log:
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = time.monotonic() + 150
            while 'CAMPUSLIFE_BROWSER_FIXTURE_READY' not in log_path.read_text(errors='replace'):
                if process.poll() is not None:
                    raise RuntimeError('Test application exited; inspect target/browser-tests/backend.log')
                if time.monotonic() > deadline:
                    raise RuntimeError('Test application did not become ready; inspect target/browser-tests/backend.log')
                time.sleep(0.2)
            result = subprocess.run([node, 'scripts/browser-smoke.cjs'], cwd=ROOT, env=environment)
            if result.returncode:
                raise RuntimeError('Browser checks failed; inspect target/browser-tests for diagnostics')
        finally:
            # Only stop the process group created above, never another developer's application.
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
