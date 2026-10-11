#!/usr/bin/env python3
"""Loopback release fixture; all order data is synthetic and administration stays out of the app."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse, json, threading, time, re
DEFAULT = {'timelineEnabled': True, 'ttlSeconds': 30, 'status': 'in_transit', 'policyHttp': 200, 'timelineHttp': 200, 'delayMs': 0}
class State:
    def __init__(self, log): self.lock=threading.RLock(); self.control=DEFAULT.copy(); self.events=[]; self.log=log
    def event(self, kind, **fields):
        with self.lock:
            row={'sequence':len(self.events)+1,'kind':kind,**fields}; self.events.append(row)
            if self.log:
                with self.log.open('a') as f: f.write(json.dumps(row,sort_keys=True)+'\n')
    def view(self):
        with self.lock:return {'control':self.control.copy(),'events':list(self.events)}
class Server(ThreadingHTTPServer): daemon_threads=True; allow_reuse_address=True
class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def setup(self): super().setup(); self.connection.settimeout(5)
    def log_message(self,*args):pass
    def send(self, code, data):
        raw=json.dumps(data,separators=(',',':')).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.send_header('Connection','close');self.end_headers();self.close_connection=True
        try:self.wfile.write(raw)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_GET(self):
        s=self.server.state
        if self.path=='/admin/events':return self.send(200,s.view())
        if self.path not in ['/config','/v2/timeline']:return self.send(404,{'error':'not_found'})
        with s.lock:c=s.control.copy()
        if self.path=='/config':
            s.event('policy',enabled=c['timelineEnabled'],http=c['policyHttp'])
            return self.send(c['policyHttp'],{'timelineEnabled':c['timelineEnabled'],'ttlSeconds':c['ttlSeconds']} if c['policyHttp']==200 else {'error':'unavailable'})
        s.event('timeline_requested',vocabulary='known' if c['status'] in ['in_transit','delivered'] else 'new',http=c['timelineHttp'])
        time.sleep(c['delayMs']/1000)
        self.send(c['timelineHttp'],{'status':c['status']} if c['timelineHttp']==200 else {'error':'unavailable'})
        s.event('timeline_completed',http=c['timelineHttp'])
    def do_POST(self):
        s=self.server.state
        try:
            if self.headers.get('Transfer-Encoding') is not None:raise ValueError()
            lengths=self.headers.get_all('Content-Length',[])
            if len(lengths)!=1 or not re.fullmatch(r'[0-9]{1,4}',lengths[0]):raise ValueError()
            length=int(lengths[0]);
            if not 0<length<=4096:raise ValueError()
            raw=self.rfile.read(length)
            if len(raw)!=length:raise ValueError()
            body=json.loads(raw.decode('utf-8'))
            if not isinstance(body,dict):raise ValueError()
        except (ValueError,UnicodeDecodeError,TimeoutError):return self.send(400,{'error':'invalid_body'})
        if self.path=='/admin/reset':
            if body:return self.send(400,{'error':'unexpected_fields'})
            with s.lock:s.control=DEFAULT.copy();s.events=[]
            return self.send(200,{'ok':True})
        if self.path=='/admin/config':
            if not set(body)<=set(DEFAULT):return self.send(400,{'error':'unexpected_fields'})
            for k,v in body.items():
                valid = type(v) is bool if k=='timelineEnabled' else (type(v) is str and v in ['in_transit','delivered','awaiting_collection'] if k=='status' else type(v) is int and ((0<=v<=60) if k=='ttlSeconds' else (0<=v<=2800) if k=='delayMs' else v in [200,503]))
                if not valid:return self.send(400,{'error':'invalid_control'})
            with s.lock:s.control.update(body)
            return self.send(200,s.view()['control'])
        if self.path=='/telemetry':
            if set(body)!={'attempt','build','osApi','outcome'}:return self.send(400,{'error':'unexpected_fields'})
            if not isinstance(body['attempt'],str) or not re.fullmatch('[a-f0-9]{32}',body['attempt']):return self.send(400,{'error':'invalid_event'})
            if type(body['build']) is not int or body['build'] not in [2601,2602,2603] or type(body['osApi']) is not int or not 26<=body['osApi']<=99 or body['outcome'] not in ['started','shown','disabled','unavailable']:return self.send(400,{'error':'invalid_event'})
            s.event('journey',**body);return self.send(200,{'ok':True})
        return self.send(404,{'error':'not_found'})
def main():
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8876);p.add_argument('--log',type=Path);a=p.parse_args()
    if a.log:a.log.parent.mkdir(parents=True,exist_ok=True)
    server=Server(('127.0.0.1',a.port),Handler);server.state=State(a.log)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()
if __name__=='__main__':main()
