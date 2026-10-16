"""Private owner bridge shapes. Deliberately absent from the model's V5 operation allowlist."""
import base64
import re

CHUNK = 256 * 1024
MAX_SIZE = 2 * 1024**3
OPS = frozenset({'safe.list', 'safe.get', 'safe.put', 'safe.delete'})


def coordinates(user, seq, offset=0):
    return (type(user) is int and 0 <= user <= 99999 and type(seq) is int and 1 <= seq < 2**63
            and type(offset) is int and 0 <= offset <= MAX_SIZE and offset % CHUNK == 0)


def validate_response(op, value, args):
    if not isinstance(value, dict):
        raise ValueError('SAFE_RESPONSE')
    if op == 'safe.list':
        if (set(value) != {'phoneId', 'snapshots', 'deleteRequest'} or not isinstance(value['phoneId'], str)
                or not re.fullmatch(r'[A-Za-z0-9_-]{1,120}', value['phoneId'])
                or not isinstance(value['snapshots'], list) or len(value['snapshots']) > 300000):
            raise ValueError('SAFE_RESPONSE')
        seen = set()
        for row in value['snapshots']:
            if (not isinstance(row, dict) or set(row) != {'user', 'seq', 'bytes', 'sha256', 'createdAt'}
                    or not coordinates(row['user'], row['seq']) or type(row['bytes']) is not int
                    or not 44 <= row['bytes'] <= MAX_SIZE or type(row['createdAt']) is not int or row['createdAt'] <= 0
                    or not isinstance(row['sha256'], str) or not re.fullmatch(r'[a-f0-9]{64}', row['sha256'])
                    or (row['user'], row['seq']) in seen):
                raise ValueError('SAFE_RESPONSE')
            seen.add((row['user'], row['seq']))
        request = value['deleteRequest']
        if request is not None and (not isinstance(request, dict) or set(request) != {'token', 'signature'}
                                    or not isinstance(request['token'], dict) or not isinstance(request['signature'], str)
                                    or len(request['signature']) > 128):
            raise ValueError('SAFE_RESPONSE')
    elif op == 'safe.get':
        if (set(value) != {'offset', 'data'} or type(value['offset']) is not int or value['offset'] != args['offset']
                or not isinstance(value['data'], str) or len(value['data']) > (CHUNK + 2) // 3 * 4):
            raise ValueError('SAFE_RESPONSE')
        if len(base64.b64decode(value['data'], validate=True)) > CHUNK:
            raise ValueError('SAFE_RESPONSE')
    elif op == 'safe.put':
        if 'receipt' in args or 'keyEnvelope' in args:
            if set(value) != {'recorded'} or value['recorded'] is not True:
                raise ValueError('SAFE_RESPONSE')
            return
        if (set(value) != {'received', 'complete'} or type(value['received']) is not int or type(value['complete']) is not bool
                or not 0 <= value['received'] <= args['size'] or value['complete'] != (value['received'] == args['size'])):
            raise ValueError('SAFE_RESPONSE')
    elif op == 'safe.delete':
        if set(value) != {'recorded'} or value['recorded'] is not True:
            raise ValueError('SAFE_RESPONSE')
    else:
        raise ValueError('SAFE_OPERATION')
