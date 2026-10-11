#!/usr/bin/env python3
"""Inspect actual native-library ELF load alignment and APK zip alignment."""
import os
from pathlib import Path
import argparse,hashlib,json,struct,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('--apk',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--zipalign',type=Path,default=Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'build-tools/37.0.0/zipalign');a=p.parse_args();assert not a.out.exists()
r={'apk':str(a.apk),'sha256':hashlib.sha256(a.apk.read_bytes()).hexdigest(),'scope':'Packaged native-library ELF PT_LOAD and ZIP alignment; runtime execution remains a separate API37/16KB emulator campaign.','libraries':[]}
proc=subprocess.run([str(a.zipalign),'-c','-P','16','4',str(a.apk)],capture_output=True,text=True);r['zipalign']={'command':[str(a.zipalign),'-c','-P','16','4',str(a.apk)],'exit':proc.returncode,'stdout':proc.stdout,'stderr':proc.stderr}
with zipfile.ZipFile(a.apk) as z:
 for name in z.namelist():
  if not name.startswith('lib/') or not name.endswith('.so'):continue
  data=z.read(name);assert data[:4]==b'\x7fELF' and data[5]==1
  segments=[]
  if data[4]==2:
   off=struct.unpack_from('<Q',data,32)[0];size,num=struct.unpack_from('<HH',data,54)
   for i in range(num):
    fields=struct.unpack_from('<IIQQQQQQ',data,off+i*size)
    if fields[0]==1:segments.append({'offset':fields[2],'virtualAddress':fields[3],'alignment':fields[7]})
  elif data[4]==1:
   off=struct.unpack_from('<I',data,28)[0];size,num=struct.unpack_from('<HH',data,42)
   for i in range(num):
    fields=struct.unpack_from('<IIIIIIII',data,off+i*size)
    if fields[0]==1:segments.append({'offset':fields[1],'virtualAddress':fields[2],'alignment':fields[7]})
  else:raise ValueError('Unsupported ELF class')
  # The runtime requirement assessed here is the packaged 64-bit ABIs; 32-bit loaders are separate.
  required=name.split('/')[1] in ['arm64-v8a','x86_64']
  passed=all(s['alignment']>=16384 and s['offset']%16384==s['virtualAddress']%16384 for s in segments)
  r['libraries'].append({'path':name,'sha256':hashlib.sha256(data).hexdigest(),'loadSegments':segments,'required64BitAlignment':required,'passes16K':passed})
r['status']='PASS' if proc.returncode==0 and all(x['passes16K'] for x in r['libraries'] if x['required64BitAlignment']) else 'FAILED'
a.out.write_text(json.dumps(r,indent=2)+'\n');print({'status':r['status'],'libraries':len(r['libraries'])});assert r['status']=='PASS'
