#!/usr/bin/env python3
"""Explicit hosted native component recipe; never a private-state qualification.

No account/model/provider/broker probe or old attempt inspection. Only sanitized
summary JSON is retained for publication. Source authoring is not execution consent.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import selectors
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'build/ci-evidence/darwin-private-state.json'
MAX_OUTPUT = 1_048_576
POLICY = 'darwin-private-state-components-v1'


class Failure(Exception):
    pass


def run(argv: list[str], timeout: int, env: dict[str, str]) -> bytes:
    """Bound one directly-owned fixed subprocess tree, retaining no transcript."""
    child = subprocess.Popen(argv, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             start_new_session=True)
    deadline = time.monotonic() + timeout
    chunks: list[bytes] = []
    count = 0
    try:
        assert child.stdout is not None
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise Failure('owned_command_timeout')
                for key, _ in selector.select(min(1.0, remaining)):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    count += len(chunk)
                    if count > MAX_OUTPUT:
                        raise Failure('owned_command_output_bound')
                    chunks.append(chunk)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise Failure('owned_command_timeout')
        if child.wait(timeout=remaining) != 0:
            raise Failure('owned_command_failed')
        return b''.join(chunks)
    except BaseException:
        # This group ID came directly from the fresh start_new_session child;
        # never discover or signal an unrelated/previous owner/model attempt.
        if child.returncode is None:
            # Do not reap/poll first: the direct PID remains owned (possibly a
            # zombie), preventing PID/group-ID reuse before this signal+wait.
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                raise Failure('owned_command_cleanup_uncertain') from None
        raise
    finally:
        if child.stdout is not None:
            child.stdout.close()


def source_digest(env: dict[str, str]) -> str:
    names = run(['git', 'ls-files', '-z', '--', 'bridges/mcp', 'bridges/reference-host',
                 'scripts/ci/run_darwin_private_state.py',
                 '.github/workflows/darwin-private-state-validation.yml'], 15, env).split(b'\0')
    digest = hashlib.sha256()
    for name in sorted(filter(None, names)):
        path = ROOT / os.fsdecode(name)
        data = path.read_bytes()
        if len(data) > 8_388_608:
            raise Failure('source_file_bound')
        digest.update(name + b'\0' + hashlib.sha256(data).digest())
    return digest.hexdigest()


def fetch_archive(path: Path, filename: str, expected: str) -> None:
    url = 'https://nodejs.org/dist/v24.20.0/' + filename
    start = time.monotonic()
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(url, timeout=30) as response, path.open('xb') as output:
        if response.status != 200 or response.url != url:
            raise Failure('official_archive_response_unqualified')
        while True:
            if time.monotonic() - start > 120:
                raise Failure('official_archive_deadline')
            data = response.read(65536)
            if not data:
                break
            total += len(data)
            if total > 134_217_728:
                raise Failure('official_archive_bound')
            digest.update(data)
            output.write(data)
    path.chmod(0o600)
    if digest.hexdigest() != expected:
        raise Failure('official_archive_digest_mismatch')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--policy', required=True, choices=[POLICY])
    parser.add_argument('--expected-commit', required=True)
    parser.add_argument('--sdk-version', required=True)
    args = parser.parse_args()
    evidence: dict[str, object] = {
        'version': 1, 'policy': POLICY, 'qualification': 'experimental-unqualified',
        'defaultDarwinEnabled': False, 'nativeControlsAttempted': False, 'nativeControlsExecuted': False, 'verified': False,
        'expectedCases': {'nativeComponents': 14, 'defaultGuard': 1, 'nativeFaultInjection': 1, 'total': 16},
        'remainingQualification': [
            'transient-open observer with positive disk-temp control',
            'large statement/savepoint/sorter and actual OOM behavior',
            'mount transitions/ownership-disabled filesystems and adversarial firmlink backing paths',
            'config-read race and complete unknown/malformed ACL flag/permission controls',
            'worker termination and supported native target matrix',
        ],
    }
    scratch: Path | None = None
    summary = None
    result = 2
    env = dict(os.environ)
    try:
        if platform.system() != 'Darwin' or os.getuid() == 0 or os.getuid() != os.geteuid():
            raise Failure('ordinary_user_darwin_required')
        if env.get('KTC_DARWIN_NATIVE_COMPONENT_CONSENT') != '1' or env.get('GITHUB_EVENT_NAME') != 'workflow_dispatch':
            raise Failure('fresh_manual_consent_required')
        if (not re.fullmatch(r'[0-9a-f]{40}', args.expected_commit) or
                args.expected_commit != env.get('GITHUB_SHA') or
                args.expected_commit != env.get('KTC_DARWIN_WORKFLOW_SHA') or env.get('GITHUB_RUN_ATTEMPT') != '1'):
            raise Failure('fresh_exact_commit_dispatch_required')
        if not re.fullmatch(r'26\.\d+(?:\.\d+)?', args.sdk_version):
            raise Failure('sdk_version_required')
        if (not re.fullmatch(r'\d+', env.get('GITHUB_RUN_ID', '')) or
                not env.get('GITHUB_OUTPUT') or not Path(env['GITHUB_OUTPUT']).is_absolute()):
            raise Failure('hosted_evidence_channel_required')
        # Reserve a NEW evidence file. Existing attempts are neither read nor
        # overwritten, and the upload step is enabled only after this new write.
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        summary = os.fdopen(os.open(OUTPUT, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'w')
        evidence['githubRunId'] = env['GITHUB_RUN_ID']
        evidence['githubRunAttempt'] = 1
        evidence['workflowCommit'] = env['KTC_DARWIN_WORKFLOW_SHA']
        commit = run(['git', 'rev-parse', 'HEAD'], 15, env).decode().strip()
        if commit != args.expected_commit:
            raise Failure('checkout_commit_mismatch')
        run(['git', 'diff', '--quiet', 'HEAD', '--'], 15, env)
        evidence['commit'] = commit
        evidence['sourceBefore'] = source_digest(env)
        arch = {'arm64': 'arm64', 'x86_64': 'x64'}.get(platform.machine())
        if arch is None:
            raise Failure('native_arch_unqualified')
        native = ROOT / 'bridges/mcp/native'
        pins = json.loads((native / 'node-release-pins.json').read_text())
        selected = pins['archives'][arch]
        scratch = Path(tempfile.mkdtemp(prefix='.ktc-darwin-ci-', dir=Path.home()))
        scratch.chmod(0o700)
        archive = scratch / selected['file']
        fetch_archive(archive, selected['file'], selected['sha256'])
        node = shutil.which('node')
        npm = shutil.which('npm')
        if node is None or npm is None:
            raise Failure('selected_node_tooling_missing')
        # Lock-selected JS dependencies only. No implicit native install script.
        for package in ['bridges/mcp', 'bridges/reference-host']:
            run([npm, '--prefix', package, 'ci', '--ignore-scripts', '--no-audit', '--no-fund'], 180, env)
        run([npm, '--prefix', 'bridges/reference-host', 'run', 'build'], 180, env)
        env['KTC_DARWIN_PRIVATE_STATE_BUILD'] = '1'
        run([node, str(native / 'build-darwin.mjs'), '--node-archive', str(archive),
             '--arch', arch, '--sdk-version', args.sdk_version, '--with-test-faults'], 300, env)
        receipt = json.loads((native / f'darwin-{arch}/manifest.json').read_text())
        evidence['build'] = {key: receipt[key] for key in ['arch', 'nodeVersion', 'nodeSha256',
            'nodeReleaseSha256', 'nativeSha256', 'sourceSha256', 'sdkVersion', 'macosVersion', 'compilerSha256']}
        env['KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL'] = '1'
        env['KTC_DARWIN_NATIVE_PRIVILEGED_FIXTURES'] = '1'
        evidence['nativeControlsAttempted'] = True
        evidence['nativeControlsExecuted'] = None  # Spawn/module/failure outcome alone proves no component count.
        tap = run([node, '--test', '--test-reporter=tap', '--test-concurrency=1',
                   str(native / 'test/darwin-private-state.test.mjs')], 180, env).decode('utf8', errors='strict')
        counts = {}
        for field in ['tests', 'pass', 'fail', 'cancelled', 'skipped', 'todo']:
            values = re.findall(r'^# ' + field + r' (\d+)\s*$', tap, re.MULTILINE)
            if len(values) != 1:
                raise Failure('native_test_summary_unqualified')
            counts[field] = int(values[0])
        evidence['testCounts'] = counts
        if counts != {'tests': 16, 'pass': 16, 'fail': 0, 'cancelled': 0, 'skipped': 0, 'todo': 0}:
            raise Failure('native_test_denominator_mismatch')
        run(['git', 'diff', '--quiet', 'HEAD', '--'], 15, env)
        evidence['sourceAfter'] = source_digest(env)
        if evidence['sourceAfter'] != evidence['sourceBefore']:
            raise Failure('source_changed')
        evidence['nativeControlsExecuted'] = True  # Exact TAP denominator and unchanged source both confirmed.
        evidence['verified'] = True  # These 16 component controls ONLY, not qualification.
        result = 0
    except Exception as error:
        evidence['error'] = str(error) if isinstance(error, Failure) else 'bounded_recipe_failure'
    finally:
        # Only a successful fresh download scratch is removed. Uncertain/failed
        # work is retained, never rediscovered or reused by this recipe.
        if scratch is not None and result == 0:
            try:
                shutil.rmtree(scratch)
            except OSError:
                evidence['verified'] = False
                evidence['error'] = 'fresh_scratch_cleanup_uncertain'
                result = 2
        evidence['freshDownloadScratchRetained'] = scratch is not None and result != 0
        if summary is not None:
            try:
                with summary:
                    json.dump(evidence, summary, indent=2)
                    summary.write('\n')
                    summary.flush()
                    os.fsync(summary.fileno())
                with open(env['GITHUB_OUTPUT'], 'a') as channel:
                    channel.write('summary_created=true\n')
            except OSError:
                evidence['verified'] = False
                evidence['error'] = 'new_evidence_write_failed'
                result = 2
        print(json.dumps({'policy': POLICY, 'verified': evidence['verified'],
                          'qualification': evidence['qualification'], 'error': evidence.get('error')}))
    return result


if __name__ == '__main__':
    sys.exit(main())
