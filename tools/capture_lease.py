#!/usr/bin/env python3
"""Acquire C26 installed-release lease/restart evidence; root executes device calls.

No install, uninstall, data reset, preference change or permission change occurs.
The policy endpoint is unavailable while the timeline endpoint stays reachable.
"""
from pathlib import Path
import argparse, hashlib, json, re, time, urllib.request
import xml.etree.ElementTree as ET
from capture_appearance import ReleaseCapture, APP, PORT, ROOT, sha, matches, bounds, area, panes, pane_key, find_pane, signature


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', '--output', dest='output', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True, help='Expected already-installed repaired release APK; never installed by this helper.')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--expected-avd', default='StaffAndroid_API_36')
    parser.add_argument('--max-scrolls', type=int, default=18)
    parser.add_argument('--settle-timeout', type=float, default=45)
    args = parser.parse_args(); args.package = APP
    if not 1 <= args.max_scrolls <= 24 or not 1 <= args.settle_timeout <= 120: parser.error('Use bounded limits.')
    apk = args.apk.resolve()
    if not apk.is_file(): parser.error('Expected APK does not exist.')
    if args.output.exists(): parser.error('Use a new evidence directory.')
    args.output.mkdir(parents=True)
    source = Path(__file__).read_bytes(); (args.output/'capture-script.py').write_bytes(source)
    dependencies = [ROOT/'projects/release-recovery/tools/capture_appearance.py', ROOT/'projects/commerce-app/tools/capture_checkpoint_appearance.py', ROOT/'projects/catalog-network/tools/fresh_android_xml.py']
    report = {'status':'STARTING','package':APP,'serial':args.serial,'candidate':'repaired-release-lease',
        'apk':str(apk),'apkSha256':sha(apk),'expectedVersionCode':2603,
        'captureScriptSha256':hashlib.sha256(source).hexdigest(),
        'captureDependencies':{str(p.relative_to(ROOT)):sha(p) for p in dependencies},
        'rows':[],'fixtureActions':[],'phases':[],'restorationErrors':[],
        'scope':'Five installed optimized-release lease/restart states. Policy HTTP 503 with reachable timeline HTTP 200, not total network-offline. No installation or data/preference/permission reset. Actual pixels need independent review.'}
    session = ReleaseCapture(args); original = original_reverse = original_fixture = None; failure = None
    cursor = 0
    def save(): (args.output/'result.json').write_text(json.dumps(report,indent=2)+'\n')
    def admin(path,payload=None):
        request=urllib.request.Request('http://127.0.0.1:8876'+path,data=None if payload is None else json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=10) as response: result=json.load(response)
        report['fixtureActions'].append({'path':path,'payload':payload,'hostMonotonic':time.monotonic()})
        return result
    def configure(**values): return admin('/admin/config',values)
    def pid():
        value=session.adb('shell','pidof',APP).decode().strip()
        if len(value.split()) != 1: raise RuntimeError('Expected one app PID: '+value)
        return int(value)
    def phase(name,expect_timeline,expected_http):
        nonlocal cursor
        view=admin('/admin/events'); events=[e for e in view['events'] if e['sequence']>cursor]
        policy=[e for e in events if e['kind']=='policy']; timeline=[e for e in events if e['kind']=='timeline_requested']
        if not policy or any(e['http']!=expected_http for e in policy): raise RuntimeError('Unexpected policy observations in '+name)
        if bool(timeline)!=expect_timeline: raise RuntimeError('Unexpected timeline request presence in '+name)
        if timeline and any(e['http']!=200 for e in timeline): raise RuntimeError('Timeline endpoint not available in '+name)
        row={'phase':name,'afterSequence':cursor,'pid':pid(),'control':view['control'],'events':events,'hostMonotonic':time.monotonic()}
        cursor=max([e['sequence'] for e in view['events']],default=cursor)
        path=args.output/(name+'-fixture-events.json'); path.write_text(json.dumps(row,indent=2)+'\n')
        report['phases'].append({'name':name,'path':path.name,'sha256':sha(path),'pid':row['pid'],'timelineRequests':len(timeline),'policyHttp':expected_http});save()
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
        avd=session.adb('emu','avd','name').decode().strip()
        if args.expected_avd not in avd.splitlines(): raise RuntimeError('Unexpected emulator: '+avd)
        report['avd']=avd; original=session.settings();report['originalSettings']=original
        original_reverse=session.adb('reverse','--list').decode();report['originalReverse']=original_reverse
        mappings=[p for p in map(str.split,original_reverse.splitlines()) if len(p)>=3 and p[-2]==PORT]
        if len(mappings)>1: raise RuntimeError('Ambiguous original reverse mapping.')
        view=admin('/admin/events'); original_fixture=view['control'];report['originalFixtureState']=original_fixture
        cursor=max([e['sequence'] for e in view['events']],default=0)
        session.adb('reverse',PORT,PORT)
        installed=session.adb('shell','pm','path',APP).decode().strip().removeprefix('package:')
        if not installed or '\n' in installed: raise RuntimeError('Expected one installed APK.')
        session.adb('pull',installed,args.output/'installed.apk')
        if sha(args.output/'installed.apk')!=report['apkSha256']: raise RuntimeError('Installed APK differs from expected repair.')
        package_dump=session.adb('shell','dumpsys','package',APP)
        (args.output/'installed-package.txt').write_bytes(package_dump);report['installedPackageSha256']=hashlib.sha256(package_dump).hexdigest()
        if not re.search(rb'versionCode=2603\b',package_dump): raise RuntimeError('Expected installed repair version2603.')
        session.adb('shell','am','force-stop',APP)
        session.adb('shell','wm','size','reset');session.adb('shell','wm','density','reset')
        session.adb('shell','settings','put','system','font_scale','1.0');session.adb('shell','cmd','uimode','night','no')
        row={'config':'light','settings':session.settings(),'status':'STARTING','frames':[]};report['rows'].append(row)
        def launch():
            session.adb('shell','am','start','-W','-n',APP+'/.MainActivity','-f','0x10008000','--ez','open_order','true')
            return session.settled(exact=['order_screen'])
        configure(timelineEnabled=True,ttlSeconds=30,status='in_transit',policyHttp=200,timelineHttp=200,delayMs=0)
        raw=launch(); first_pid=pid(); raw=reveal(raw,['delivery_updates_preference','notification_status'])
        pref=next(n for n in ET.fromstring(raw).iter('node') if matches(n,'delivery_updates_preference')).get('checked')
        if pref!='true': raise RuntimeError('Upgraded saved preference must remain true.')
        report['observedPreference']=pref
        warm_start=time.monotonic();raw=tap('view_updates',raw);raw=idle('delivery_status')
        raw=state_capture(row,'warm-30-second-lease',raw,'delivery_status','Known delivery state after positive policy with a 30-second process-local lease.')
        phase('warm30',True,200)
        configure(policyHttp=503)
        if time.monotonic()-warm_start>=25: raise RuntimeError('Warm capture consumed too much of the 30-second lease; do not infer cached admission.')
        raw=tap('refresh_updates',raw);raw=idle('delivery_status')
        if pid()!=first_pid: raise RuntimeError('Cached positive lease phase changed process.')
        raw=state_capture(row,'cached-positive-lease',raw,'delivery_status','Policy HTTP 503 while a still-valid process-local positive lease permits the reachable timeline request.')
        phase('cached-positive',True,503)
        session.adb('shell','am','force-stop',APP);raw=launch();second_pid=pid()
        if second_pid==first_pid: raise RuntimeError('Process restart did not yield a new PID.')
        report['restart']={'beforePid':first_pid,'afterPid':second_pid,'appDataCleared':False}
        raw=tap('view_updates',raw);raw=idle('updates_disabled')
        raw=state_capture(row,'new-process-disabled',raw,'updates_disabled','New process has no positive lease: policy HTTP 503 leads to basic-order fallback with no timeline request.')
        phase('new-process-disabled',False,503)
        configure(policyHttp=200,ttlSeconds=5)
        raw=tap('refresh_updates',raw);raw=idle('delivery_status')
        raw=state_capture(row,'warm-5-second-lease',raw,'delivery_status','Same restarted process acquires a five-second positive policy and shows the known timeline.')
        phase('warm5',True,200)
        configure(policyHttp=503);wait_start=time.monotonic();time.sleep(6)
        report['expiryWaitSeconds']=time.monotonic()-wait_start
        raw=tap('refresh_updates',raw);raw=idle('updates_disabled')
        if pid()!=second_pid: raise RuntimeError('Expiry phase changed process.')
        raw=state_capture(row,'expired-lease-disabled',raw,'updates_disabled','After the positive lease expires, policy HTTP 503 prevents a timeline request in the same process.')
        phase('expired-disabled',False,503)
        row['status']='CAPTURED_NOT_REVIEWED';report['status']='CAPTURED_NOT_REVIEWED'
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
