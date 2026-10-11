#!/usr/bin/env python3
"""Independent real-HTTP contract checks on an isolated task-owned fixture."""
from pathlib import Path
from hashlib import sha256
from datetime import datetime, timezone
import argparse
import http.client
import json
import signal
import socket
import subprocess
import sys
import time

PROJECT = Path(__file__).resolve().parents[1]
PORT = 8877
MARKERS = ['private-order-review-marker', 'private-person-review-marker@example.invalid']


def request(method, path, body=None, raw=None):
    connection = http.client.HTTPConnection('127.0.0.1', PORT, timeout=7)
    try:
        payload = raw if raw is not None else None if body is None else json.dumps(body).encode()
        connection.request(method, path, payload, {'Content-Type': 'application/json'})
        response = connection.getresponse()
        data = response.read()
        return response.status, json.loads(data)
    finally:
        connection.close()


def raw_request(headers, body=b''):
    with socket.create_connection(('127.0.0.1', PORT), timeout=7) as stream:
        wire = b'POST /telemetry HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n' + headers + b'\r\n' + body
        stream.sendall(wire)
        stream.shutdown(socket.SHUT_WR)
        result = b''
        while True:
            part = stream.recv(4096)
            if not part:
                break
            result += part
        status = int(result.split(b' ', 2)[1])
        return status, result


def require(value, message):
    if not value:
        raise AssertionError(message)


def view():
    code, body = request('GET', '/admin/events')
    require(code == 200, 'administrative observation unavailable')
    return body


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    # Never attach this audit to an unrelated process already using the port.
    with socket.socket() as guard:
        guard.bind(('127.0.0.1', PORT))
    log = output / 'server-events.jsonl'
    process = subprocess.Popen([sys.executable, str(PROJECT / 'server/fixture.py'), '--port', str(PORT), '--log', str(log)], cwd=PROJECT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    cases = []
    failure = None
    events = []
    observed_controls = {}
    try:
        deadline = time.monotonic() + 8
        while True:
            require(process.poll() is None, 'fixture exited before accepting connections')
            try:
                view()
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise AssertionError('fixture startup deadline')
                time.sleep(0.05)

        code, policy = request('GET', '/config')
        require(code == 200 and policy == {'timelineEnabled': True, 'ttlSeconds': 30}, 'default policy differs')
        cases.append({'case': 'default_policy', 'status': 'PASS', 'enabled': True, 'ttlSeconds': 30})

        for status in ['in_transit', 'delivered', 'awaiting_collection']:
            require(request('POST', '/admin/config', {'status': status})[0] == 200, 'known fixture control rejected')
            code, reply = request('GET', '/v2/timeline')
            require(code == 200 and reply == {'status': status}, 'timeline response differs')
        cases.append({'case': 'service_vocabulary', 'status': 'PASS', 'known_values': 2, 'new_value': 1})

        for ttl in [0, 60]:
            require(request('POST', '/admin/config', {'timelineEnabled': False, 'ttlSeconds': ttl})[0] == 200, 'valid policy bound rejected')
            require(request('GET', '/config') == (200, {'timelineEnabled': False, 'ttlSeconds': ttl}), 'policy boundary not returned')
        controls = view()['control']
        invalid_controls = [
            {'timelineEnabled': 1}, {'ttlSeconds': True}, {'ttlSeconds': -1}, {'ttlSeconds': 61},
            {'delayMs': -1}, {'delayMs': 2801}, {'policyHttp': 401}, {'timelineHttp': '503'},
            {'status': 'DELIVERED'}, {'unexpected': MARKERS[0]}, {'timelineEnabled': True, 'ttlSeconds': 61},
        ]
        for body in invalid_controls:
            code, reply = request('POST', '/admin/config', body)
            require(code == 400 and set(reply) == {'error'}, 'invalid control accepted or echoed')
            require(view()['control'] == controls, 'rejected control partially changed state')
        cases.append({'case': 'typed_control_bounds_and_atomic_rejection', 'status': 'PASS', 'invalid_cases': len(invalid_controls)})

        require(request('POST', '/admin/config', {'policyHttp': 503, 'timelineHttp': 503})[0] == 200, 'failure controls rejected')
        require(request('GET', '/config') == (503, {'error': 'unavailable'}), 'policy503 contract')
        require(request('GET', '/v2/timeline') == (503, {'error': 'unavailable'}), 'timeline503 contract')
        require(request('POST', '/admin/config', {'policyHttp': 200, 'timelineHttp': 200})[0] == 200, 'recovery control rejected')
        require(request('GET', '/config')[0] == 200 and request('GET', '/v2/timeline')[0] == 200, 'service did not recover')
        cases.append({'case': 'independent_failure_and_recovery_controls', 'status': 'PASS'})

        for index, outcome in enumerate(['started', 'shown', 'disabled', 'unavailable']):
            event = {'attempt': f'{index + 1:032x}', 'build': 2602, 'osApi': 36, 'outcome': outcome}
            require(request('POST', '/telemetry', event) == (200, {'ok': True}), 'closed event rejected')
        before = view()['events']
        valid = {'attempt': 'a' * 32, 'build': 2602, 'osApi': 36, 'outcome': 'started'}
        invalid_events = [
            dict(valid, orderId=MARKERS[0]), dict(valid, email=MARKERS[1]), dict(valid, rawStatus='awaiting_collection'),
            dict(valid, attempt=MARKERS[0]), dict(valid, attempt='A' * 32), dict(valid, attempt='a' * 31),
            dict(valid, build=True), dict(valid, build=2604), dict(valid, osApi=25), dict(valid, osApi=100),
            dict(valid, outcome='delivered'), dict(valid, outcome={'detail': MARKERS[0]}),
            {k: v for k, v in valid.items() if k != 'outcome'},
        ]
        for body in invalid_events:
            code, reply = request('POST', '/telemetry', body)
            require(code == 400 and set(reply) == {'error'}, 'invalid diagnostic payload accepted or echoed')
        require(view()['events'] == before, 'rejected telemetry entered event ledger')
        cases.append({'case': 'closed_telemetry_fields_and_types', 'status': 'PASS', 'accepted_outcomes': 4, 'rejected_payloads': len(invalid_events)})

        framing = [
            (b'Content-Length: 2\r\nContent-Length: 2\r\n', b'{}'),
            (b'Content-Length: -1\r\n', b''), (b'Content-Length: 4097\r\n', b''),
            (b'Transfer-Encoding: chunked\r\nContent-Length: 2\r\n', b'{}'),
            (b'Content-Length: 4\r\n', b'{}'), (b'Content-Length: 2\r\n', b'\xff\xff'),
            (b'Content-Length: 2\r\n', b'[]'),
        ]
        for headers, body in framing:
            code, reply = raw_request(headers, body)
            require(code == 400, 'malformed framing was not rejected')
            require(all(marker.encode() not in reply for marker in MARKERS), 'response leaked a prohibited marker')
        require(view()['events'] == before, 'malformed request entered event ledger')
        cases.append({'case': 'malformed_framing_utf8_and_object_shape', 'status': 'PASS', 'rejected_requests': len(framing)})

        require(request('GET', '/unknown')[0] == 404, 'unknown route not rejected')
        require(request('POST', '/admin/reset', {'unexpected': 1})[0] == 400, 'reset must reject unintended fields')
        require(view()['events'] == before, 'invalid reset erased evidence')
        cases.append({'case': 'route_and_reset_boundaries', 'status': 'PASS'})

        observed = view()
        events, observed_controls = observed['events'], observed['control']
        shapes = {'policy': {'sequence', 'kind', 'enabled', 'http'}, 'timeline_requested': {'sequence', 'kind', 'vocabulary', 'http'}, 'timeline_completed': {'sequence', 'kind', 'http'}, 'journey': {'sequence', 'kind', 'attempt', 'build', 'osApi', 'outcome'}}
        for sequence, event in enumerate(events, 1):
            require(event['sequence'] == sequence and set(event) == shapes[event['kind']], 'event allowlist or ordering differs')
        serialized = json.dumps(events)
        require(all(marker not in serialized for marker in MARKERS), 'retained events contain a prohibited marker')
        require('awaiting_collection' not in serialized, 'raw service vocabulary leaked into diagnostics')
        require(request('POST', '/admin/reset', {}) == (200, {'ok': True}), 'valid reset failed')
        require(view()['events'] == [] and view()['control']['ttlSeconds'] == 30, 'valid reset did not restore fixture')
        cases.append({'case': 'allowlisted_events_and_reset', 'status': 'PASS', 'typed_events': len(events), 'prohibited_markers_checked': len(MARKERS)})
    except Exception as error:
        failure = {'type': type(error).__name__, 'message': str(error)}
    finally:
        if process.poll() is None:
            process.send_signal(signal.SIGINT)
        stdout, stderr = process.communicate(timeout=10)
    raw_log = log.read_bytes() if log.exists() else b''
    if process.returncode != 0 or stdout or stderr or any(marker.encode() in raw_log for marker in MARKERS):
        failure = failure or {'type': 'ProcessOrRedactionFailure', 'message': 'Unexpected fixture output, exit or retained marker'}
    report = {'status': 'PASS' if failure is None else 'FAIL', 'executed_at': datetime.now(timezone.utc).isoformat(), 'scope': 'Isolated real loopback HTTP and fixture contracts; not production authorization, TLS, customer telemetry or security certification.', 'port': PORT, 'cases': cases, 'failure': failure, 'typed_events': len(events), 'process_exit': process.returncode, 'stdout_bytes': len(stdout), 'stderr_bytes': len(stderr), 'source_sha256': sha256((PROJECT / 'server/fixture.py').read_bytes()).hexdigest(), 'test_sha256': sha256(Path(__file__).read_bytes()).hexdigest(), 'log_sha256': sha256(raw_log).hexdigest(), 'final_controls_before_reset': observed_controls}
    (output / 'events.json').write_text(json.dumps(events, indent=2) + '\n')
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'cases': len(cases), 'events': len(events), 'failure': failure}, indent=2))
    return int(failure is not None)


if __name__ == '__main__':
    raise SystemExit(main())
