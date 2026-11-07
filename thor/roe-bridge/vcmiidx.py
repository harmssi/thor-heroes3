"""Read VCMI's own config JSON (comments, trailing commas) to get entity ids by legacy index."""
import json, re, glob, os
SRC = os.environ.get('VCMI_CONFIG', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'config'))

def load(p):
    t, out, i, n = open(p, encoding='utf-8').read(), [], 0, None
    n = len(t)
    while i < n:
        c = t[i]
        if c == '"':
            j = i + 1
            while t[j] != '"':
                j += 2 if t[j] == '\\' else 1
            out.append(t[i:j + 1]); i = j + 1
        elif t.startswith('//', i):
            i = t.find('\n', i); i = n if i < 0 else i
        elif t.startswith('/*', i):
            i = t.find('*/', i) + 2
        else:
            out.append(c); i += 1
    return json.loads(re.sub(r',(\s*[}\]])', r'\1', ''.join(out)))

def index(globpat):
    out = {}
    for f in glob.glob(os.path.join(SRC, globpat)):
        for k, v in load(f).items():
            if isinstance(v, dict) and 'index' in v:
                out[v['index']] = k
    return out
