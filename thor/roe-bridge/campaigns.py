"""RoE 1.0 campaigns (H3C format version 1, as shipped in the HD Edition LODs) -> VCMI campaign files (.vcmp).

VCMI reads H3C only from version 4 (RoE as re-released with SoD). Version 1 is: u32 version, u8 region set,
name, description, then per scenario (map name, u32 packed size, u8 precondition bitmask), then the gzip maps.
We repack the owner's own maps + texts (CAMPTEXT region names, CAMPDIAG briefings) into VCMI's json format.
RoE 1.0 has no start bonuses or hero carry-over in the file -> plain scenario chain.
"""
import gzip, io, json, os, struct, zipfile
from legacy import lod_read
from vcmiidx import SRC, load

REGION_SETS = ['good1', 'good2', 'good3', 'evil1', 'evil2', 'neutral1', 'secret1']  # v1 region id 1..7
FILES = ['GOOD1', 'GOOD2', 'GOOD3', 'EVIL1', 'EVIL2', 'NEUTRAL1', 'SECRET1']


def parse_v1(d):
    o = 0

    def s():
        nonlocal o
        n, = struct.unpack_from('<I', d, o)
        o += 4
        r = d[o:o + n]
        o += n
        return r
    ver, = struct.unpack_from('<I', d, 0)
    assert ver == 1, ver
    region = d[4]
    o = 5
    name, desc = s(), s()
    sc = []
    while d[o:o + 2] != b'\x1f\x8b':
        m = s()
        size, = struct.unpack_from('<I', d, o)
        sc.append((m, size, d[o + 4]))
        o += 5
    maps = []
    for m, size, pre in sc:
        if size:
            maps.append(gzip.decompress(d[o:o + size]))
            o += size
    assert o == len(d), (o, len(d))
    return region, name, desc, [x for x in sc if x[1]], maps


def sections(txt):
    """CAMPTEXT.TXT: '//<title>' header lines followed by entries, blank lines between sections."""
    out, cur = {}, None
    for line in txt.split('\r\n'):
        if line.startswith('//'):
            cur = out.setdefault(line[2:].strip(), [])
        elif line.strip() and cur is not None:
            cur.append(line.strip())
    return out


def build(tables, out_dir):
    """tables: name -> LOD entry (localized entries already override base ones). Returns written files."""
    dec = lambda b: b.decode('cp1252')
    regions = load(os.path.join(SRC, 'campaignRegions.json'))
    camptext = sections(dec(lod_read(tables['CAMPTEXT.TXT'])))
    diag = {}
    for line in dec(lod_read(tables['CAMPDIAG.TXT'])).split('\r\n'):
        if '\t' in line:
            k, v = line.split('\t', 1)
            diag[k.strip().lower()] = v.strip().strip('"')
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for fname in FILES:
        if fname + '.H3C' not in tables:
            continue
        region, name, desc, sc, maps = parse_v1(lod_read(tables[fname + '.H3C']))
        rs = REGION_SETS[region - 1]
        reg = dict(regions[rs])
        reg['desc'] = reg['desc'][:len(sc)]
        reg['colorSuffixLength'] = 0  # RoE region art has no per-colour variants (G1A_EN, not G1A_ENR)
        title = rs[:-1].capitalize() + ' ' + rs[-1] + ' Map Region Names'  # e.g. "Good 1 Map Region Names"
        names = camptext.get(title, [])
        scen = []
        for i, (m, size, pre) in enumerate(sc):
            letter = 'abcd'[i]
            brief = diag.get(f'{rs}{letter}') or next((v for k, v in diag.items() if k.startswith(f'{rs}{letter}/')), '')
            # owner's HD videos: Good1a.., NeutralA.., SecretA..; Evil2ap1/2 = epilog after Evil2a
            base = rs[:-1].capitalize()
            video = f'{base}{rs[-1]}{letter}' if base in ('Good', 'Evil') else f'{base}{letter.upper()}'
            epi = diag.get(f'{rs}{letter}p')
            scen.append({
                'map': str(i),
                'preconditions': [j for j in range(8) if pre >> j & 1],
                'color': 0,
                'difficulty': 1,
                'regionText': names[i] if i < len(names) else '',
                'prolog': {'text': brief, 'video': [video]} if brief else {},
                'epilog': {'text': epi, 'video': [f'{video}p1', f'{video}p2']} if epi else {},
                'heroKeeps': [],
                'keepCreatures': [],
                'startOptions': 'none',
                'bonuses': [],
            })
        header = {'version': 1, 'regions': reg, 'name': dec(name), 'description': dec(desc),
                  'allowDifficultySelection': True, 'scenarios': scen}
        path = os.path.join(out_dir, fname + '.vcmp')
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('header.json', json.dumps(header, ensure_ascii=False, indent=1))
            for i, mp in enumerate(maps):
                z.writestr(f'{i}.h3m', mp)
        written.append(path)
    return written


if __name__ == '__main__':
    import sys
    from legacy import lod_files
    t = {}
    for lod in sys.argv[2:]:
        t.update(lod_files(lod))  # later LODs (localized) win
    for p in build(t, sys.argv[1]):
        h = json.loads(zipfile.ZipFile(p).read('header.json'))
        assert h['scenarios'] and all(s['regionText'] for s in h['scenarios']), p
        print(p, h['name'], [(s['regionText'], s['preconditions'], bool(s['prolog'])) for s in h['scenarios']])
