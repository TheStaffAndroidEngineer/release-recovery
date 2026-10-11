#!/usr/bin/env python3
"""Root-executed C26 signed upgrade, crash, containment and repair acquisition.

A single explicit uninstall starts this task-owned fixture's legacy baseline.
Thereafter installs use -r; app data is never cleared. No global log clearing.
Screenshots remain acquisition evidence until an independent pixel review.
"""
from pathlib import Path
import argparse, hashlib, json, re, subprocess, time, urllib.request
import xml.etree.ElementTree as ET
from capture_appearance import ReleaseCapture, ROOT, APP, PORT
from capture_checkpoint_appearance import area, bounds, matches, panes, pane_key, find_pane, signature

sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
PERMISSION = 'android.permission.POST_NOTIFICATIONS'
WITNESS_PACKAGE = APP + '.witness'
WITNESS_RUNNER = WITNESS_PACKAGE + '/staff.android.release.witness.StateWitness'


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--out','--output',dest='output',type=Path,required=True)
    for name in ['legacy','candidate','repair']: parser.add_argument('--'+name+'-apk',type=Path,required=True)
    parser.add_argument('--test-apk',type=Path,required=True,help='Independent pure-Java release-witness APK; no AndroidX/Kotlin runtime dependency.')
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--expected-avd',default='StaffAndroid_API_36')
    parser.add_argument('--max-scrolls',type=int,default=18)
    parser.add_argument('--settle-timeout',type=float,default=45)
    args=parser.parse_args();args.package=APP
    if not 1<=args.max_scrolls<=24 or not 1<=args.settle_timeout<=120:parser.error('Use bounded scroll/settle limits.')
    apks={name:getattr(args,name+'_apk').resolve() for name in ['legacy','candidate','repair','test']}
    if not all(p.is_file() for p in apks.values()):parser.error('Every APK input must exist.')
    if args.output.exists():parser.error('Use a new evidence directory; retain earlier attempts.')
    args.output.mkdir(parents=True)
    helper=Path(__file__);(args.output/'capture-script.py').write_bytes(helper.read_bytes())
    dependencies=[helper.with_name('capture_appearance.py'),ROOT/'projects/commerce-app/tools/capture_checkpoint_appearance.py',ROOT/'projects/catalog-network/tools/fresh_android_xml.py']
    session=ReleaseCapture(args);original=original_reverse=original_fixture=None;failure=None;stage=None;sequence=0
    row={'config':'light','status':'STARTING','frames':[],'launches':[]}
    report={'status':'STARTING','package':APP,'serial':args.serial,'candidate':'signed-upgrade-recovery',
        'apk':str(apks['repair']),'apkSha256':sha(apks['repair']),
        'captureScriptSha256':sha(helper),'captureDependencies':{str(p.relative_to(ROOT)):sha(p) for p in dependencies},
        'rows':[row],'frameArtifacts':{},'fixtureActions':[],'phaseEvents':[],'installedStateWitnesses':[],
        'restorationErrors':[],'setupUninstall':None,
        'scope':'One task-owned fresh baseline followed by signed legacy2601 to candidate2602 to repair2603 updates without app-data clearing. Actual candidate process failure, same-APK feature containment, ordinary downgrade rejection, framework-only SQL preservation witnesses, and separately reviewed light pixels. Notification grant changes are deliberate test setup on this newly initialized fixture app.'}
    def save():(args.output/'result.json').write_text(json.dumps(report,indent=2)+'\n')
    def adb_result(*parts,timeout=90):
        run=subprocess.run([str(session.adb_path),'-s',args.serial,*map(str,parts)],capture_output=True,timeout=timeout)
        session.commands.append({'args':list(map(str,parts)),'exit':run.returncode})
        return run
    def admin(path,payload=None):
        request=urllib.request.Request('http://127.0.0.1:8876'+path,data=None if payload is None else json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=10) as response:value=json.load(response)
        report['fixtureActions'].append({'path':path,'payload':payload});return value
    def configure(**values):return admin('/admin/config',values)
    def events(name):
        nonlocal sequence
        value=admin('/admin/events');selected=[e for e in value['events'] if e['sequence']>sequence]
        item={'phase':name,'afterSequence':sequence,'control':value['control'],'events':selected}
        if selected:sequence=selected[-1]['sequence']
        path=args.output/(name+'-fixture-events.json');path.write_text(json.dumps(item,indent=2)+'\n')
        report['phaseEvents'].append({'phase':name,'path':path.name,'sha256':sha(path)});save()
    def framed(raw,labels):
        tree=ET.fromstring(raw);parents={child:parent for parent in tree.iter() for child in parent}
        for label in labels:
            found=False
            for node in tree.iter('node'):
                if node.get('package')!=APP or not matches(node,label):continue
                left,top,right,bottom=bounds(node)
                if not area((left,top,right,bottom)):continue
                valid=True;parent=node
                while parent in parents:
                    parent=parents[parent]
                    if parent.get('scrollable')=='true':
                        pl,pt,pr,pb=bounds(parent)
                        if left<pl or right>pr or top<pt+12 or bottom>pb-12:valid=False
                if valid:found=True;break
            if not found:return False
        return True
    def reveal(raw,labels):
        labels=[labels] if isinstance(labels,str) else labels
        if framed(raw,labels):return raw
        keys=[pane_key(p) for p in panes(ET.fromstring(raw),APP)]
        for key in keys:
            for direction in ['down','up']:
                for _ in range(args.max_scrolls):
                    pane=find_pane(ET.fromstring(raw),APP,key)
                    if pane is None:break
                    before=signature(pane);raw=session.swipe(pane,direction)
                    if framed(raw,labels):return raw
                    after=find_pane(ET.fromstring(raw),APP,key)
                    if after is None or signature(after)==before:break
        raise RuntimeError('Could not fully frame '+repr(labels))
    def tap(label,raw,expected=(),settle=True):
        raw=reveal(raw,label);raw=session.xml();target=session.target(ET.fromstring(raw),label)
        if target is None:raise RuntimeError('Fresh guarded enabled target unavailable: '+label)
        left,top,right,bottom=bounds(target)
        session.adb('shell','input','tap',(left+right)//2,(top+bottom)//2)
        return session.settled(exact=list(expected)) if settle else None
    def capture(name,raw,condition):
        frame=session.capture('light-'+name,raw)
        frame.update(condition=condition,surfacePackage=APP,artifactKey=stage,apkSha256=sha(apks[stage]))
        row['frames'].append(frame);save();return raw
    def installed_copy(name,filename):
        remote=session.adb('shell','pm','path',APP).decode().strip().removeprefix('package:')
        path=args.output/filename;session.adb('pull',remote,path)
        if sha(path)!=sha(apks[name]):raise RuntimeError('Installed APK mismatch for '+name)
        return path
    def install(name,version):
        nonlocal stage
        session.adb('install','-r',apks[name]);stage=name
        copied=installed_copy(name,'installed-'+name+'.apk')
        dump=session.adb('shell','dumpsys','package',APP);path=args.output/(name+'-package.txt');path.write_bytes(dump)
        if not re.search(rb'versionCode='+str(version).encode()+rb'\b',dump):raise RuntimeError('Unexpected installed version for '+name)
        report['frameArtifacts'][name]={'path':str(apks[name]),'sha256':sha(apks[name]),'installedReadback':copied.name,'versionCode':version,'packageDump':path.name,'packageDumpSha256':sha(path)};save()
    def launch(order=True):
        session.adb('shell','am','force-stop',APP)
        parts=['shell','am','start','-W','-n',APP+'/.MainActivity','-f','0x10008000']
        if order:parts+=['--ez','open_order','true']
        session.adb(*parts);row['launches'].append({'artifact':stage,'route':'order' if order else 'shop','appDataCleared':False})
        return session.settled(exact=['order_screen' if order else 'shop'])
    def receipt(name,raw,action):
        raw=reveal(raw,['order_total','saved_delivery',action])
        values=[n.get('text','') for n in ET.fromstring(raw).iter('node') if matches(n,'order_total')]
        if len(values)!=1 or re.sub(r'\D','',values[0])!='12999':raise RuntimeError('Unexpected saved total: '+repr(values))
        return capture(name,raw,'Complete saved ₹12,999 receipt, saved delivery label and adjacent navigation control for '+stage+'.')
    def preference(name,raw,expected_status=None):
        raw=reveal(raw,['delivery_updates_preference','notification_status'])
        toggle=next(n for n in ET.fromstring(raw).iter('node') if matches(n,'delivery_updates_preference'))
        if toggle.get('checked')!='true':raise RuntimeError('Non-default saved preference was not preserved.')
        if expected_status and not any(n.get('text')==expected_status for n in ET.fromstring(raw).iter('node')):raise RuntimeError('Unexpected permission explanation.')
        return capture(name,raw,'Saved preference remains enabled; the displayed notification status reflects actual device permission for '+stage+'.')
    def witness(name,version,schema):
        run=adb_result('shell','am','instrument','-w','-r',WITNESS_RUNNER)
        text=(run.stdout+run.stderr).decode(errors='replace');path=args.output/(name+'-installed-state.txt');path.write_text(text)
        matches_=re.findall(r'^INSTRUMENTATION_(?:STATUS|RESULT): installed_state=(.+)$',text,re.M)
        if run.returncode or len(matches_)!=1 or not re.search(r'^INSTRUMENTATION_CODE:\s*-1\s*$',text,re.M):raise RuntimeError('Installed-state instrumentation failed: '+name)
        value=json.loads(matches_[0]);assert value['versionCode']==version and value['schema']==schema,value
        assert value['order']['id']=='cg-1042' and value['order']['total_minor']==1299900 and value['order']['delivery_updates']==1,value
        report['installedStateWitnesses'].append({'phase':name,'artifact':stage,'raw':path.name,'sha256':sha(path),'state':value});save()
    def timeline(raw,tag):
        raw=session.settled(exact=['updates_screen']);return reveal(raw,[tag,'refresh_updates'])
    def pids():
        result=adb_result('shell','pidof',APP,timeout=45)
        if result.returncode not in [0,1]:raise RuntimeError('Unexpected pidof failure.')
        return result.stdout.decode().split()
    try:
        avd=session.adb('emu','avd','name').decode().strip()
        if args.expected_avd not in avd.splitlines():raise RuntimeError('Unexpected emulator: '+avd)
        report['avd']=avd;original=session.settings();report['originalSettings']=original
        original_reverse=session.adb('reverse','--list').decode();report['originalReverse']=original_reverse
        if len([p for p in map(str.split,original_reverse.splitlines()) if len(p)>=3 and p[-2]==PORT])>1:raise RuntimeError('Ambiguous original reverse mapping.')
        initial=admin('/admin/events');original_fixture=initial['control'];report['originalFixtureState']=original_fixture
        sequence=max([e['sequence'] for e in initial['events']],default=0)
        session.adb('reverse',PORT,PORT)
        for parts in [('shell','wm','size','reset'),('shell','wm','density','reset'),('shell','settings','put','system','font_scale','1.0'),('shell','cmd','uimode','night','no')]:session.adb(*parts)
        row['settings']=session.settings()
        # This is the only uninstall. It starts the explicitly authorized task-owned fixture baseline.
        removed=adb_result('uninstall',APP);removal=(removed.stdout+removed.stderr).decode(errors='replace')
        report['setupUninstall']={'count':1,'exit':removed.returncode,'output':removal,'reason':'Initialize the task-owned legacy fixture; no subsequent uninstall or clear is allowed.'}
        if removed.returncode and 'Unknown package' not in removal and 'DELETE_FAILED_INTERNAL_ERROR' not in removal:raise RuntimeError('Unexpected setup uninstall failure.')
        absent=adb_result('shell','pm','path',APP,timeout=45)
        if absent.stdout.strip():raise RuntimeError('Task-owned app remains installed after baseline removal.')
        report['setupUninstall']['absenceVerified']=True
        configure(timelineEnabled=True,ttlSeconds=30,status='in_transit',policyHttp=200,timelineHttp=200,delayMs=0)
        install('legacy',2601)
        session.adb('install','-r',apks['test'])
        test_remote=session.adb('shell','pm','path',WITNESS_PACKAGE).decode().strip().removeprefix('package:')
        session.adb('pull',test_remote,args.output/'installed-test.apk')
        assert sha(args.output/'installed-test.apk')==sha(apks['test'])
        report['testApk']={'path':str(apks['test']),'sha256':sha(apks['test']),'installedReadback':'installed-test.apk','package':WITNESS_PACKAGE,'runner':WITNESS_RUNNER,'scope':'Independent pure-Java framework instrumentation; no target app or AndroidX/Kotlin class linkage.'}
        session.adb('shell','pm','revoke',APP,PERMISSION)
        session.adb('shell','pm','set-permission-flags',APP,PERMISSION,'user-set','user-fixed')
        raw=launch(False);raw=capture('legacy-shop',raw,'Legacy baseline public Shop before the stored order is exercised.')
        raw=tap('open_order',raw,['order_screen']);raw=receipt('legacy-receipt',raw,'view_updates')
        raw=reveal(raw,'delivery_updates_preference')
        toggle=next(n for n in ET.fromstring(raw).iter('node') if matches(n,'delivery_updates_preference'))
        if toggle.get('checked')!='false':raise RuntimeError('Fresh legacy preference unexpectedly already enabled.')
        raw=tap('delivery_updates_preference',raw)
        raw=preference('legacy-permission-denied',raw,'Allow notifications to receive delivery updates.')
        session.adb('shell','pm','clear-permission-flags',APP,PERMISSION,'user-set','user-fixed')
        session.adb('shell','pm','grant',APP,PERMISSION)
        raw=launch();raw=preference('legacy-permission-granted',raw,'Delivery notifications are on.')
        witness('legacy',2601,1);events('legacy')
        install('candidate',2602);raw=launch();raw=preference('candidate-preserved-preference',raw,'Delivery notifications are on.')
        raw=tap('view_updates',raw);raw=timeline(raw,'delivery_status')
        raw=capture('candidate-known-timeline',raw,'Candidate recognizes in_transit before the server vocabulary changes.')
        raw=receipt('candidate-known-receipt',raw,'back_order');witness('candidate-before-crash',2602,2);events('candidate-known')
        # Instrumentation ends its process; return to the same installed candidate before the explicit failing action.
        raw=launch();raw=tap('view_updates',raw);raw=timeline(raw,'delivery_status')
        configure(status='awaiting_collection');before=pids()
        if len(before)!=1:raise RuntimeError('Expected exactly one candidate process before failing action: '+repr(before))
        pid=before[0];report['crash']={'pidBefore':pid,'artifact':'candidate','trigger':'Explicit Refresh updates after fixture status awaiting_collection'};save()
        tap('refresh_updates',raw,settle=False)
        deadline=time.monotonic()+20
        while pid in pids() and time.monotonic()<deadline:time.sleep(.2)
        after=pids()
        if pid in after:raise RuntimeError('Expected candidate PID did not exit.')
        crash=session.adb('logcat','-b','crash','-d','-v','raw','--pid',pid)
        (args.output/'candidate-crash-raw.txt').write_bytes(crash)
        if b'FATAL EXCEPTION' not in crash:raise RuntimeError('No retained fatal crash for the observed candidate PID.')
        report['crash'].update(pidsAfter=after,raw='candidate-crash-raw.txt',sha256=hashlib.sha256(crash).hexdigest());events('candidate-crash')
        configure(timelineEnabled=False);raw=launch();raw=tap('view_updates',raw);raw=timeline(raw,'updates_disabled')
        raw=capture('candidate-disabled-timeline',raw,'Same installed candidate avoids risky decoding after the feature policy is disabled.')
        raw=receipt('candidate-disabled-receipt',raw,'back_order');raw=tap('back_order',raw,['order_screen'])
        raw=preference('candidate-disabled-preference',raw,'Delivery notifications are on.')
        witness('candidate-contained',2602,2);events('candidate-contained')
        rejected=adb_result('install','-r',apks['legacy']);rejection=(rejected.stdout+rejected.stderr).decode(errors='replace')
        (args.output/'downgrade-rejection.txt').write_text(rejection)
        if rejected.returncode==0 or 'INSTALL_FAILED_VERSION_DOWNGRADE' not in rejection:raise RuntimeError('Ordinary legacy downgrade did not reject as expected.')
        unchanged=installed_copy('candidate','candidate-after-downgrade-rejection.apk')
        report['downgrade']={'exit':rejected.returncode,'raw':'downgrade-rejection.txt','sha256':sha(args.output/'downgrade-rejection.txt'),'unchangedInstalledApkSha256':sha(unchanged)};save()
        install('repair',2603);configure(timelineEnabled=True,status='awaiting_collection')
        raw=launch();raw=preference('repair-preserved-preference',raw,'Delivery notifications are on.')
        raw=tap('view_updates',raw);raw=timeline(raw,'delivery_status')
        raw=capture('repair-neutral-timeline',raw,'Forward repair accepts the unknown service status with neutral copy.')
        raw=receipt('repair-preserved-receipt',raw,'back_order');witness('repair',2603,2);events('repair')
        installed_copy('repair','installed.apk')
        row['status']='CAPTURED_NOT_REVIEWED';report['status']='CAPTURED_NOT_REVIEWED'
    except Exception as error:
        failure=error;report['status']='FAILED';report['error']=str(error)
        try:report['failureWitness']=session.failure_witness()
        except Exception as witness_error:report['failureWitness']={'error':str(witness_error)}
    finally:
        restore=[('shell','am','force-stop',APP)]
        if original:
            restore.append(('shell','settings','delete','system','font_scale') if original['font'] in ('null','') else ('shell','settings','put','system','font_scale',original['font']))
            restore.append(('shell','cmd','uimode','night',original['night'].split(':')[-1].strip()))
            for kind in ['size','density']:
                match=re.search(r'Override '+kind+r':\s*(\S+)',original[kind],re.I);restore.append(('shell','wm',kind,match.group(1) if match else 'reset'))
        for parts in restore:
            try:session.adb(*parts)
            except Exception as error:report['restorationErrors'].append(str(error))
        if original_reverse is not None:
            try:
                current=session.adb('reverse','--list').decode()
                if any(len(p)>=3 and p[-2]==PORT for p in map(str.split,current.splitlines())):session.adb('reverse','--remove',PORT)
                for parts in map(str.split,original_reverse.splitlines()):
                    if len(parts)>=3 and parts[-2]==PORT:session.adb('reverse',parts[-2],parts[-1])
                report['restoredReverse']=session.adb('reverse','--list').decode()
                if sorted(report['restoredReverse'].splitlines())!=sorted(original_reverse.splitlines()):report['restorationErrors'].append('Reverse mappings differ from original.')
            except Exception as error:report['restorationErrors'].append(str(error))
        if original_fixture is not None:
            try:report['restoredFixtureState']=configure(**original_fixture)
            except Exception as error:report['restorationErrors'].append(str(error))
        try:
            report['restoredSettings']=session.settings()
            if original and report['restoredSettings']!=original:report['restorationErrors'].append('Display settings differ from original.')
        except Exception as error:report['restorationErrors'].append(str(error))
        if report['restorationErrors'] and not failure:report['status']='CAPTURED_RESTORE_FAILED'
        save();(args.output/'commands.json').write_text(json.dumps(session.commands,indent=2)+'\n');(args.output/'tap-guards.json').write_text(json.dumps(session.tap_guards,indent=2)+'\n')
    if failure:raise failure
    if report['restorationErrors']:raise RuntimeError('Restoration errors; inspect result.json.')


if __name__=='__main__':main()
