#!/usr/bin/env python3
"""Guarded C26 appearance acquisition. Root alone executes device operations.

Installs the supplied repaired APK with -r, never clears application data, and
never changes the stored notification preference or permission. Actual upgrade,
crash and rollout evidence belongs to the separate runtime investigation.
"""
from pathlib import Path
import argparse, hashlib, json, re, sys, time, urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'projects/commerce-app/tools'))
from capture_checkpoint_appearance import CaptureSession, area, bounds, matches, panes, pane_key, find_pane, signature
from fresh_android_xml import read_ui_xml

APP = 'staff.android.release'
PORT = 'tcp:8876'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()


class ReleaseCapture(CaptureSession):
    def swipe(self, pane, direction):
        left, top, right, bottom = bounds(pane)
        if not area((left, top, right, bottom)):
            raise RuntimeError('Fresh pane is empty.')
        high, low = top + (bottom-top)*35//100, top + (bottom-top)*65//100
        start, end = (low, high) if direction == 'down' else (high, low)
        self.adb('shell', 'input', 'swipe', (left+right)//2, start, (left+right)//2, end, 180)
        return self.settled()

    def failure_witness(self):
        result = {'scope': 'Unsettled failure viewport before restoration; not acceptance.', 'errors': {}}
        for name, producer in [
            ('xml', lambda: read_ui_xml(self.adb, '/sdcard/common-goods-failure.xml', {APP, 'android', 'com.android.systemui'}, attempts=1).encode()),
            ('png', lambda: self.adb('exec-out', 'screencap', '-p')),
            ('window', lambda: self.adb('shell', 'dumpsys', 'window', 'windows')),
        ]:
            try:
                data = producer(); path = self.args.output / ('failure-viewport.' + ('txt' if name == 'window' else name))
                path.write_bytes(data); result[name] = path.name; result[name+'Sha256'] = sha(path)
            except Exception as error:
                result['errors'][name] = str(error)
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', '--output', dest='output', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--expected-avd', default='StaffAndroid_API_36')
    parser.add_argument('--candidate', default='repaired-release')
    parser.add_argument('--expected-version', type=int, default=2603)
    parser.add_argument('--expected-preference', choices=['true', 'false'], default='true')
    parser.add_argument('--configs', nargs='+', choices=['light', 'dark', 'large', 'wide'], default=['light', 'dark', 'large', 'wide'])
    parser.add_argument('--max-scrolls', type=int, default=18)
    parser.add_argument('--settle-timeout', type=float, default=45)
    args = parser.parse_args(); args.package = APP
    if not 1 <= args.max_scrolls <= 24 or not 1 <= args.settle_timeout <= 120:
        parser.error('Use bounded scroll/settle limits.')
    apk = args.apk.resolve()
    if not apk.is_file(): parser.error('APK does not exist.')
    if args.output.exists(): parser.error('Use a new evidence directory; retain earlier captures.')
    args.output.mkdir(parents=True)
    source = Path(__file__).read_bytes(); (args.output/'capture-script.py').write_bytes(source)
    dependencies = [ROOT/'projects/commerce-app/tools/capture_checkpoint_appearance.py', ROOT/'projects/catalog-network/tools/fresh_android_xml.py']
    report = {'status': 'STARTING', 'package': APP, 'serial': args.serial, 'candidate': args.candidate,
        'apk': str(apk), 'apkSha256': sha(apk), 'expectedVersionCode': args.expected_version,
        'captureScriptSha256': hashlib.sha256(source).hexdigest(),
        'captureDependencies': {str(path.relative_to(ROOT)): sha(path) for path in dependencies},
        'rows': [], 'fixtureActions': [], 'restorationErrors': [],
        'scope': 'Repaired Common Goods Shop, saved Camera order, notification preference, known/unknown delivery status, disabled timeline, unavailable/retry and transient loading in four display configurations. No app data clearing or preference/permission changes. Pixels require independent review; no representative timing or full accessibility claim.'}
    session = ReleaseCapture(args); original = original_reverse = original_fixture = None; failure = None

    def save(): (args.output/'result.json').write_text(json.dumps(report, indent=2)+'\n')
    def admin(path, payload=None):
        request = urllib.request.Request('http://127.0.0.1:8876'+path, data=None if payload is None else json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response: result = json.load(response)
        report['fixtureActions'].append({'path': path, 'payload': payload})
        return result
    def configure(**values): return admin('/admin/config', values)
    def has(raw, label): return any(matches(node, label) for node in ET.fromstring(raw).iter('node'))
    def framed(raw, labels):
        tree = ET.fromstring(raw); parents = {child: parent for parent in tree.iter() for child in parent}
        for label in labels:
            found = False
            for node in tree.iter('node'):
                if node.get('package') != APP or not matches(node, label): continue
                left, top, right, bottom = bounds(node)
                if not area((left, top, right, bottom)): continue
                valid = True; parent = node
                while parent in parents:
                    parent = parents[parent]
                    if parent.get('scrollable') == 'true':
                        pl, pt, pr, pb = bounds(parent)
                        if left < pl or right > pr or top < pt+12 or bottom > pb-12: valid = False
                if valid: found = True; break
            if not found: return False
        return True
    def reveal(raw, labels):
        labels = [labels] if isinstance(labels, str) else labels
        if framed(raw, labels): return raw
        keys = [pane_key(p) for p in panes(ET.fromstring(raw), APP)]
        for key in keys:
            for direction in ['down', 'up']:
                for _ in range(args.max_scrolls):
                    pane = find_pane(ET.fromstring(raw), APP, key)
                    if pane is None: break
                    before = signature(pane); raw = session.swipe(pane, direction)
                    if framed(raw, labels): return raw
                    after = find_pane(ET.fromstring(raw), APP, key)
                    if after is None or signature(after) == before: break
        raise RuntimeError('Could not fully frame expected labels: '+repr(labels))
    def tap(label, raw, exact=(), settle=True):
        raw = reveal(raw, label); raw = session.xml()
        target = session.target(ET.fromstring(raw), label)
        if target is None: raise RuntimeError('Fresh guarded enabled target unavailable: '+label)
        left, top, right, bottom = bounds(target)
        session.adb('shell', 'input', 'tap', (left+right)//2, (top+bottom)//2)
        return session.settled(exact=list(exact)) if settle else session.xml()
    def capture(row, name, raw, condition):
        frame = session.capture(row['config']+'-'+name, raw)
        frame.update(condition=condition, surfacePackage=APP); row['frames'].append(frame); save(); return raw
    def idle(expected):
        # Navigation can preserve the LazyColumn's lower scroll position.
        # Do not require an uncomposed upper status tag before revealing it.
        raw = session.settled(exact=['updates_screen'])
        return reveal(raw, [expected, 'refresh_updates'])
    def total(raw, action):
        # Android font scaling is nonlinear. Use visible ancestor insets plus
        # the next control, then inspect actual glyph pixels separately.
        raw = reveal(raw, ['order_total', 'saved_delivery', action])
        values = [n.get('text', '') for n in ET.fromstring(raw).iter('node') if matches(n, 'order_total')]
        if len(values) != 1 or re.sub(r'\D', '', values[0]) != '12999':
            raise RuntimeError('Unexpected saved receipt total: '+repr(values))
        return raw
    def state_capture(row, name, raw, status_tag, condition):
        raw = reveal(raw, [status_tag, 'refresh_updates'])
        return capture(row, name, raw, condition)

    try:
        avd = session.adb('emu', 'avd', 'name').decode().strip()
        if args.expected_avd not in avd.splitlines(): raise RuntimeError('Unexpected emulator: '+avd)
        report['avd'] = avd; original = session.settings(); report['originalSettings'] = original
        original_reverse = session.adb('reverse', '--list').decode(); report['originalReverse'] = original_reverse
        mappings = [p for p in map(str.split, original_reverse.splitlines()) if len(p) >= 3 and p[-2] == PORT]
        if len(mappings) > 1: raise RuntimeError('Ambiguous original chapter reverse mapping.')
        original_fixture = admin('/admin/events')['control']; report['originalFixtureState'] = original_fixture
        session.adb('reverse', PORT, PORT)
        session.adb('install', '-r', apk)
        installed = session.adb('shell', 'pm', 'path', APP).decode().strip().removeprefix('package:')
        session.adb('pull', installed, args.output/'installed.apk')
        if sha(args.output/'installed.apk') != report['apkSha256']: raise RuntimeError('Installed APK identity mismatch.')
        package_dump = session.adb('shell', 'dumpsys', 'package', APP)
        (args.output/'installed-package.txt').write_bytes(package_dump)
        report['installedPackageSha256'] = hashlib.sha256(package_dump).hexdigest()
        if not re.search(rb'versionCode='+str(args.expected_version).encode()+rb'\b', package_dump):
            raise RuntimeError('Unexpected installed versionCode.')
        for config in args.configs:
            session.adb('shell', 'am', 'force-stop', APP)
            session.adb('shell', 'wm', 'size', '1920x1200' if config == 'wide' else 'reset')
            session.adb('shell', 'wm', 'density', '240' if config == 'wide' else 'reset')
            session.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0' if config == 'large' else '1.0')
            session.adb('shell', 'cmd', 'uimode', 'night', 'yes' if config == 'dark' else 'no')
            row = {'config': config, 'settings': session.settings(), 'status': 'STARTING', 'frames': [], 'launches': []}; report['rows'].append(row)
            assert float(row['settings']['font']) == (2 if config == 'large' else 1)
            assert 'Night mode: '+('yes' if config == 'dark' else 'no') in row['settings']['night']
            if config == 'wide': assert 'Override size: 1920x1200' in row['settings']['size'] and 'Override density: 240' in row['settings']['density']
            else: assert 'Override' not in row['settings']['size'] and 'Override' not in row['settings']['density']
            configure(timelineEnabled=True, ttlSeconds=30, status='in_transit', policyHttp=200, timelineHttp=200, delayMs=0)
            session.adb('shell', 'am', 'start', '-W', '-n', APP+'/.MainActivity', '-f', '0x10008000')
            row['launches'].append({'route': 'Shop', 'appDataCleared': False})
            raw = session.settled(exact=['shop']); raw = capture(row, 'shop-top', raw, 'Common Goods catalog and order entry on the repaired installed app.')
            raw = tap('open_order', raw, ['order_screen'])
            raw = capture(row, 'order-top', raw, 'Saved Camera order upper area and shopping navigation.')
            raw = total(raw, 'view_updates'); raw = capture(row, 'order-full', raw, 'Complete saved ₹12,999, saved delivery label and View delivery updates control.')
            raw = reveal(raw, ['delivery_updates_preference', 'notification_status'])
            preference = next(n for n in ET.fromstring(raw).iter('node') if matches(n, 'delivery_updates_preference'))
            if preference.get('checked') != args.expected_preference: raise RuntimeError('Saved delivery preference differs from the expected upgraded value.')
            row['observedPreference'] = preference.get('checked')
            raw = capture(row, 'order-preference', raw, 'Preserved delivery-update preference and actual device notification status; no permission result inferred from preference.')
            raw = tap('view_updates', raw); raw = idle('delivery_status')
            raw = state_capture(row, 'known-timeline', raw, 'delivery_status', 'Recognized in-transit delivery message and complete Refresh updates control.')
            raw = total(raw, 'back_order'); raw = capture(row, 'known-receipt', raw, 'Same saved receipt price and Back to your order control beneath the timeline.')
            configure(status='awaiting_collection')
            raw = tap('refresh_updates', raw); raw = idle('delivery_status')
            raw = state_capture(row, 'unknown-timeline', raw, 'delivery_status', 'Unfamiliar service status receives the repaired neutral explanation rather than a false Delivered claim.')
            raw = total(raw, 'back_order'); raw = capture(row, 'unknown-receipt', raw, 'Receipt remains fully framed beneath the repaired unknown-status explanation.')
            configure(timelineEnabled=False)
            raw = tap('refresh_updates', raw); raw = idle('updates_disabled')
            raw = state_capture(row, 'disabled-timeline', raw, 'updates_disabled', 'Feature policy disables detailed updates with a designed basic-order explanation.')
            raw = total(raw, 'back_order'); raw = capture(row, 'disabled-receipt', raw, 'Same saved order price and Back control remain available while the timeline is disabled.')
            configure(timelineEnabled=True, timelineHttp=503)
            raw = tap('refresh_updates', raw); raw = idle('updates_unavailable')
            raw = state_capture(row, 'unavailable-timeline', raw, 'updates_unavailable', 'Unavailable service preserves the order and offers an actionable Refresh updates control.')
            configure(timelineHttp=200, status='delivered')
            raw = tap('refresh_updates', raw); raw = idle('delivery_status')
            raw = state_capture(row, 'recovered-timeline', raw, 'delivery_status', 'A successful retry shows the service-provided Delivered status.')
            configure(delayMs=2200, status='in_transit')
            raw = tap('refresh_updates', raw, settle=False)
            if not has(raw, 'updates_loading'): raise RuntimeError('Transient loading ended before capture; retain this acquisition failure instead of inventing a loading observation.')
            raw = capture(row, 'timeline-loading', raw, 'Actual in-flight update with progress indicator; fixture delay is acquisition control, not a timing measurement.')
            raw = idle('delivery_status'); configure(delayMs=0)
            (args.output/(config+'-fixture-events.json')).write_text(json.dumps(admin('/admin/events'), indent=2)+'\n')
            row['status'] = 'CAPTURED_NOT_REVIEWED'; save(); print(config+' captured', flush=True)
        report['status'] = 'CAPTURED_NOT_REVIEWED'
    except Exception as error:
        failure = error; report['status'] = 'FAILED'; report['error'] = str(error)
        try: report['failureWitness'] = session.failure_witness()
        except Exception as witness_error: report['failureWitness'] = {'error': str(witness_error)}
    finally:
        restore = [('shell', 'am', 'force-stop', APP)]
        if original:
            restore.append(('shell', 'settings', 'delete', 'system', 'font_scale') if original['font'] in ('null', '') else ('shell', 'settings', 'put', 'system', 'font_scale', original['font']))
            restore.append(('shell', 'cmd', 'uimode', 'night', original['night'].split(':')[-1].strip()))
            for kind in ['size', 'density']:
                match = re.search(r'Override '+kind+r':\s*(\S+)', original[kind], re.I)
                restore.append(('shell', 'wm', kind, match.group(1) if match else 'reset'))
        for command in restore:
            try: session.adb(*command)
            except Exception as error: report['restorationErrors'].append(str(error))
        if original_reverse is not None:
            try:
                current = session.adb('reverse', '--list').decode()
                if any(len(p) >= 3 and p[-2] == PORT for p in map(str.split, current.splitlines())): session.adb('reverse', '--remove', PORT)
                for parts in map(str.split, original_reverse.splitlines()):
                    if len(parts) >= 3 and parts[-2] == PORT: session.adb('reverse', parts[-2], parts[-1])
                report['restoredReverse'] = session.adb('reverse', '--list').decode()
                if sorted(report['restoredReverse'].splitlines()) != sorted(original_reverse.splitlines()): report['restorationErrors'].append('Reverse mappings differ from original.')
            except Exception as error: report['restorationErrors'].append(str(error))
        if original_fixture is not None:
            try: report['restoredFixtureState'] = configure(**original_fixture)
            except Exception as error: report['restorationErrors'].append(str(error))
        try:
            report['restoredSettings'] = session.settings()
            if original and report['restoredSettings'] != original: report['restorationErrors'].append('Display settings differ from original.')
        except Exception as error: report['restorationErrors'].append(str(error))
        if report['restorationErrors'] and not failure: report['status'] = 'CAPTURED_RESTORE_FAILED'
        save()
        (args.output/'commands.json').write_text(json.dumps(session.commands, indent=2)+'\n')
        (args.output/'tap-guards.json').write_text(json.dumps(session.tap_guards, indent=2)+'\n')
    if failure: raise failure
    if report['restorationErrors']: raise RuntimeError('Restoration errors; inspect result.json.')


if __name__ == '__main__': main()
