from pathlib import Path
import importlib.util,sys
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('notification_parser',ROOT/'notification_parser.py'); m=importlib.util.module_from_spec(spec); sys.modules[spec.name]=m; assert spec.loader; spec.loader.exec_module(m)

def test_isolates_teamwork_and_content_intent():
    text='''NotificationRecord(pkg=other.app id=1)
  android.title=Other
NotificationRecord(pkg=tech.picnic.workapp id=2)
  channelId=shift_alerts
  android.title=Shift available
  android.text=Open shift
  postTime=2026-08-28T12:00:00Z
  contentIntent=PendingIntent{abc}
NotificationRecord(pkg=tech.picnic.workapp id=3)
  channel=general
  title=Hello
  text=World
  contentIntent=null
'''
    out=m.parse_notification_dump(text)
    assert len(out)==2
    assert out[0]['channel']=='shift_alerts' and out[0]['contentIntentPresent'] is True
    assert out[1]['contentIntentPresent'] is False
