#!/usr/bin/env python3
"""Manage the user's shared local MySQL/Redis instance, without login/startup services.

Credentials are generated once in ~/.config/campuslife-dev (mode 0700/0600).
This never deletes a database or overwrites an existing unrelated server.
"""
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
CONFIG = Path.home() / '.config/campuslife-dev'
REGISTRY = CONFIG / 'services.json'
LOGS = Path.home() / 'Library/Logs/CampusLife'
MYSQL_DATA = Path.home() / 'Library/Application Support/MySQL/8.4-data'
REDIS_DATA = Path.home() / 'Library/Application Support/Redis/data'
MYSQL_CONFIG = CONFIG / 'mysql.cnf'
MYSQL_ADMIN = CONFIG / 'mysql-admin.cnf'
REDIS_CONFIG = CONFIG / 'redis.conf'
MYSQL_SOCKET = CONFIG / 'mysql.sock'
BIN = Path.home() / '.local/bin'

def private_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as out: out.write(text)
    path.chmod(0o600)

def run(args, *, sql=None, check=True, env=None, timeout=40):
    result = subprocess.run(args, input=sql, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False, timeout=timeout, env=env)
    if check and result.returncode:
        # args never contain passwords; SQL input is deliberately not logged.
        raise RuntimeError(f'Command failed: {args[0]}\n{result.stderr}')
    return result

def port_open(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.4): return True
    except OSError: return False

def mysql_ping():
    result = run([str(BIN/'mysql'), f'--defaults-file={MYSQL_ADMIN}', '--batch', '--skip-column-names'],
                 sql='SELECT 1;', check=False)
    return result.returncode == 0 and result.stdout.strip() == '1'

def redis_env(config):
    env = os.environ.copy(); env['REDISCLI_AUTH'] = config['redis_password']; return env

def redis_ping(config):
    result = run([str(BIN/'redis-cli'), '-h', '127.0.0.1', '-p', '6379', 'PING'],
                 check=False, env=redis_env(config))
    return result.returncode == 0 and result.stdout.strip() == 'PONG'

def wait_for(predicate, description):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if predicate(): return
        time.sleep(0.25)
    raise RuntimeError(f'Timed out waiting for {description}; check {LOGS}')

def configure():
    if REGISTRY.exists(): return json.loads(REGISTRY.read_text())
    for command in ('mysqld', 'mysql', 'mysqladmin', 'redis-server', 'redis-cli'):
        if not (BIN/command).exists(): raise RuntimeError(f'Install {command} first')
    if MYSQL_DATA.exists() and any(MYSQL_DATA.iterdir()):
        raise RuntimeError('Existing MySQL data without this setup registry; will not overwrite it')
    if REDIS_DATA.exists() and any(REDIS_DATA.iterdir()):
        raise RuntimeError('Existing Redis data without this setup registry; will not overwrite it')
    CONFIG.mkdir(parents=True, exist_ok=True); CONFIG.chmod(0o700)
    LOGS.mkdir(parents=True, exist_ok=True)
    MYSQL_DATA.mkdir(parents=True, exist_ok=True)
    REDIS_DATA.mkdir(parents=True, exist_ok=True)
    config = {'mysql_basedir': str((BIN/'mysqld').resolve().parents[1]),
              'mysql_root_password': secrets.token_hex(24),
              'db_user': 'campuslife', 'db_password': secrets.token_hex(24),
              'redis_password': secrets.token_hex(24)}
    private_write(REGISTRY, json.dumps(config, indent=2)+'\n')
    private_write(MYSQL_CONFIG, f'''[mysqld]
basedir="{config['mysql_basedir']}"
datadir="{MYSQL_DATA}"
socket="{MYSQL_SOCKET}"
pid-file="{CONFIG / 'mysqld.pid'}"
log-error="{LOGS / 'mysql.log'}"
bind-address=127.0.0.1
port=3306
mysqlx=0
skip-log-bin
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
default-time-zone='+00:00'
innodb-buffer-pool-size=128M
max-connections=120
''')
    private_write(MYSQL_ADMIN, f'''[client]
user=root
password={config['mysql_root_password']}
protocol=socket
socket="{MYSQL_SOCKET}"
''')
    private_write(REDIS_CONFIG, f'''bind 127.0.0.1
port 6379
protected-mode yes
requirepass {config['redis_password']}
dir "{REDIS_DATA}"
logfile "{LOGS / 'redis.log'}"
pidfile "{CONFIG / 'redis.pid'}"
appendonly yes
appendfsync everysec
maxmemory 128mb
maxmemory-policy noeviction
''')
    return config

def bootstrap_mysql(config):
    if (CONFIG/'mysql-setup-complete').exists(): return
    if port_open(3306): raise RuntimeError('Port 3306 is in use; no existing server was modified')
    if not (MYSQL_DATA/'mysql').exists():
        print('Initializing owned MySQL data directory (no network access during bootstrap).', flush=True)
        run([str(BIN/'mysqld'), '--no-defaults', '--initialize-insecure',
             f"--basedir={config['mysql_basedir']}", f'--datadir={MYSQL_DATA}',
             f'--log-error={LOGS / "mysql-initialize.log"}'], timeout=120)
    run([str(BIN/'mysqld'), f'--defaults-file={MYSQL_CONFIG}', '--skip-networking', '--daemonize'])
    wait_for(lambda: MYSQL_SOCKET.exists(), 'MySQL bootstrap socket')
    root_command = [str(BIN/'mysql'), f'--defaults-file={MYSQL_ADMIN}']
    if run(root_command, sql='SELECT 1;', check=False).returncode:
        root_command = [str(BIN/'mysql'), '--no-defaults', '--protocol=socket',
                        f'--socket={MYSQL_SOCKET}', '-uroot']
    # These secrets are random hex, never interpolated from user input.
    sql = f'''ALTER USER 'root'@'localhost' IDENTIFIED BY '{config['mysql_root_password']}';
CREATE DATABASE IF NOT EXISTS campuslife CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS campuslife_test CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'campuslife'@'localhost' IDENTIFIED BY '{config['db_password']}';
CREATE USER IF NOT EXISTS 'campuslife'@'127.0.0.1' IDENTIFIED BY '{config['db_password']}';
GRANT ALL PRIVILEGES ON campuslife.* TO 'campuslife'@'localhost';
GRANT ALL PRIVILEGES ON campuslife_test.* TO 'campuslife'@'localhost';
GRANT ALL PRIVILEGES ON campuslife.* TO 'campuslife'@'127.0.0.1';
GRANT ALL PRIVILEGES ON campuslife_test.* TO 'campuslife'@'127.0.0.1';
'''
    run(root_command, sql=sql)
    run([str(BIN/'mysqladmin'), f'--defaults-file={MYSQL_ADMIN}', 'shutdown'])
    wait_for(lambda: not MYSQL_SOCKET.exists(), 'bootstrap shutdown')
    private_write(CONFIG/'mysql-setup-complete', 'Owned setup completed. Do not delete MySQL data.\n')

def write_project_env(config):
    destination = ROOT/'.env'
    if destination.exists(): return
    private_write(destination, f"DB_USER=campuslife\nDB_PASSWORD={config['db_password']}\nREDIS_PASSWORD={config['redis_password']}\n")
    print(f'Project credentials written to {destination} (0600, gitignored).', flush=True)

def start(config):
    bootstrap_mysql(config)
    if not mysql_ping():
        if port_open(3306): raise RuntimeError('Another service owns port 3306')
        run([str(BIN/'mysqld'), f'--defaults-file={MYSQL_CONFIG}', '--daemonize'])
        wait_for(mysql_ping, 'MySQL')
    if not redis_ping(config):
        if port_open(6379): raise RuntimeError('Another service owns port 6379')
        run([str(BIN/'redis-server'), str(REDIS_CONFIG), '--daemonize', 'yes'])
        wait_for(lambda: redis_ping(config), 'Redis')
    write_project_env(config)
    print('MySQL ready: 127.0.0.1:3306; Redis ready: 127.0.0.1:6379. No startup services installed.')

action = sys.argv[1] if len(sys.argv)>1 else 'status'
if action == 'start':
    start(configure())
elif action == 'status':
    if not REGISTRY.exists(): raise SystemExit('Not configured. Run: python3 scripts/local-services.py start')
    config = json.loads(REGISTRY.read_text())
    print(json.dumps({'mysql_ready': mysql_ping(), 'redis_ready': redis_ping(config),
                      'mysql_data': str(MYSQL_DATA), 'redis_data': str(REDIS_DATA), 'logs': str(LOGS)}, indent=2))
elif action == 'stop':
    config = json.loads(REGISTRY.read_text())
    if mysql_ping(): run([str(BIN/'mysqladmin'), f'--defaults-file={MYSQL_ADMIN}', 'shutdown'])
    if redis_ping(config): run([str(BIN/'redis-cli'), '-h', '127.0.0.1', '-p', '6379', 'SHUTDOWN'], env=redis_env(config))
    print('Stopped the configured shared local services. Data preserved.')
else:
    raise SystemExit('Usage: local-services.py start|status|stop')
