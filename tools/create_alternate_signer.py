#!/usr/bin/env python3
"""Create a disposable differently signed test APK; never read a production signing key."""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile,os
p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
jdk=Path(os.environ.get('JAVA_HOME',str(Path.home()/'Library/Java/JavaVirtualMachines/jdk-27.jdk/Contents/Home')))
signer=Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'build-tools/37.0.0/apksigner';commands=[]
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,env={**os.environ,'JAVA_HOME':str(jdk)},timeout=90);commands.append({'command':list(map(str,args)),'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr});assert r.returncode==0;return r.stdout
with tempfile.TemporaryDirectory(prefix='chapter26-lab-signing-') as temp:
 key=Path(temp)/'alternate.p12'
 run([jdk/'bin/keytool','-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Chapter 26 alternate lab signer','-keystore',key,'-storepass','android','-keypass','android'])
 apk=a.out/'alternate-repair.apk';run([signer,'sign','--ks',key,'--ks-key-alias','fixture','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,a.apk])
 certificate=run([signer,'verify','--verbose','--print-certs',apk]);(a.out/'certificate.txt').write_text(certificate)
 report={'status':'PREPARED_NOT_INSTALLED','source':str(a.apk),'sourceSha256':hashlib.sha256(a.apk.read_bytes()).hexdigest(),'alternate':str(apk),'alternateSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'commands':commands,'scope':'Synthetic disposable signer for local package-manager rejection. Temporary private key destroyed on exit; no personal or production signing material used.'}
(a.out/'prepared.json').write_text(json.dumps(report,indent=2)+'\n');print({'status':report['status'],'alternateSha256':report['alternateSha256']})
