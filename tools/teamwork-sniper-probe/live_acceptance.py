from __future__ import annotations
import argparse, json, re, subprocess, time
from datetime import datetime, timezone
from pathlib import Path
from probe import aggregate_xml_texts
from notification_parser import parse_notification_dump
PACKAGE='tech.picnic.workapp'; ACTIVITY='tech.picnic.workapp/.MainActivity'

def run(adb,serial,*args,timeout=30,check=True):
    cmd=[adb,'-s',serial,*args]; t=time.perf_counter(); p=subprocess.run(cmd,capture_output=True,text=True,timeout=timeout); ms=(time.perf_counter()-t)*1000
    if check and p.returncode!=0: raise RuntimeError(f"command failed: {' '.join(cmd)}\n{p.stderr.strip()}")
    return p.stdout.strip(),p.stderr.strip(),round(ms,1)

def package_version(text):
    def g(p):
        m=re.search(p,text); return m.group(1) if m else None
    return {'versionName':g(r'versionName=([^\s]+)'), 'versionCode':g(r'versionCode=(\d+)')}

def scrollables(xml):
    import xml.etree.ElementTree as ET
    root=ET.fromstring(xml); out=[]
    for n in root.iter():
        if n.attrib.get('scrollable')=='true':
            out.append({k:n.attrib.get(k) for k in ('class','resource-id','content-desc','text','scrollable') if n.attrib.get(k)})
    return out

def main()->int:
    ap=argparse.ArgumentParser(); ap.add_argument('--serial',required=True); ap.add_argument('--adb',default='adb'); ap.add_argument('--output',type=Path,default=Path(__file__).resolve().parent/'output'/'live_acceptance.json'); ap.add_argument('--max-scroll-dumps',type=int,default=1); a=ap.parse_args()
    report={'evidenceLevel':'LIVE_CONFIRMED','serial':a.serial,'startedAt':datetime.now(timezone.utc).isoformat(),'latenciesMs':{},'notes':[]}
    try:
        devices=subprocess.run([a.adb,'devices'],capture_output=True,text=True,timeout=10)
    except FileNotFoundError:
        print('ADB executable not found. Install platform-tools or pass --adb.'); return 3
    if not re.search(rf'^{re.escape(a.serial)}\s+device$',devices.stdout,re.M):
        print(f'{a.serial} is not an online adb device.'); return 4
    android,_,ms=run(a.adb,a.serial,'shell','getprop','ro.build.version.release'); report['androidVersion']=android; report['latenciesMs']['androidVersion']=ms
    pkg,_,ms=run(a.adb,a.serial,'shell','dumpsys','package',PACKAGE); report['teamwork']=package_version(pkg); report['latenciesMs']['packageDump']=ms
    launch,_,ms=run(a.adb,a.serial,'shell','am','start','-W','-n',ACTIVITY); report['launchOutput']=launch; report['latenciesMs']['launch']=ms
    dump_remote='/sdcard/teamwork-live.xml'; _,_,ms=run(a.adb,a.serial,'shell','uiautomator','dump',dump_remote,timeout=40); report['latenciesMs']['uiautomatorDump']=ms
    tmp=a.output.parent/'_teamwork_live.xml'; tmp.parent.mkdir(parents=True,exist_ok=True); _,_,ms=run(a.adb,a.serial,'pull',dump_remote,str(tmp)); report['latenciesMs']['pull']=ms
    xml=tmp.read_text(encoding='utf-8',errors='replace'); report['scrollableCandidates']=scrollables(xml)
    report['hierarchy']=aggregate_xml_texts([('initial',xml)],evidence_level='LIVE_CONFIRMED')
    notif,_,ms=run(a.adb,a.serial,'shell','dumpsys','notification'); report['latenciesMs']['notificationDump']=ms; report['notifications']=parse_notification_dump(notif)
    if a.max_scroll_dumps>1:
        report['notes'].append('Automatic semantic scrolling is not attempted without a live-confirmed selector/action. Inspect scrollableCandidates, then add only a validated semantic action.')
    report['finishedAt']=datetime.now(timezone.utc).isoformat(); a.output.parent.mkdir(parents=True,exist_ok=True); a.output.write_text(json.dumps(report,indent=2),encoding='utf-8')
    try: tmp.unlink()
    except OSError: pass
    print(json.dumps(report,indent=2)); print(f'Wrote {a.output}')
    if not report['scrollableCandidates']:
        print('Manual next step: navigate Teamwork semantically to the calendar, then rerun this command.'); return 5
    return 0
if __name__=='__main__': raise SystemExit(main())
