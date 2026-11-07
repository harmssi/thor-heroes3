#!/usr/bin/env python3
"""RoE-Bridge: rewrite the owner's RoE-only HD-Edition text tables into the SoD layout VCMI expects.

Usage: convert.py <dir with H3bitmap.lod [+ H3pbitma.lod]> <out Data dir>
Only owner data is reshuffled; missing SoD entries become neutral placeholder rows
(clones of an existing owner row with the name replaced) - no foreign game data.
"""
import os, sys
from legacy import lod_files, lod_read, rows_crlf as rows, dump_quoted as dump

PH = b'(fehlt)'  # placeholder name for content that only exists in AB/SoD

SOD_CREATURES = 150
SOD_ARTIFACTS = 144
SOD_HEROES = 156
SOD_CLASSES = 18
ROE_GENRLTXT = 709    # entries 0..708 match SoD; HD adds its own lines after that
SOD_GENRLTXT = 764    # VCMI references core.genrltxt.* up to 763
# SoD-only GENRLTXT entries that VCMI fills via MetaString replace -> need these specifiers
GENRLTXT_FMT = {714: b' %s', 715: b' %s', 716: b' %s', 717: b' %d %s', 718: b' %s %s', 719: b' %s'}
# SoD-only GENRLTXT entries VCMI shows in RoE play -> own short texts instead of a placeholder (de, en)
GENRLTXT_OWN = {
    709: ('Heldenaustausch', 'Hero Exchange'),
    714: ('Alle Zauber der Stufe in der %s', 'All spells of this level in the %s'),
    726: ('Kampagne ausw\xe4hlen', 'Select a Campaign'),
    731: ('Zauber abgebrochen', 'Spell cancelled'),
    732: ('Ihr besitzt alle Teile f\xfcr: ', 'You possess all components for: '),
    733: ('Dieses Artefakt zerlegen?', 'Disassemble this artifact?'),
    740: ('Zufallskarte', 'Random Map'),
    742: ('Kein Tutorial vorhanden.', 'No tutorial available.'),
    751: ('Ung\xfcltige Zufallskarten-Optionen.', 'Invalid random map options.'),
    762: ('Kampagnen', 'Campaigns'),
}
# RoE creature table: 0..117 identical to SoD, then the 4 war machines (SoD 145..148)
ROE_WARMACHINE_TO_SOD = {118: 145, 119: 146, 120: 147, 121: 148}
ROE_BALLISTA = 119
SOD_ARROW_TOWER = 149
# RoE HEROBIOS has two campaign bios after the 128 heroes: Catherine, Roland
ROE_BIO_TO_SOD = {128: 146, 129: 152}


def table_lang(src):
    return 'de' if os.path.exists(os.path.join(src, 'H3pbitma.lod')) else 'en'


def load_tables(src):
    base = os.path.join(src, 'H3bitmap.lod')
    loc = os.path.join(src, 'H3pbitma.lod')  # localized tables override the base ones
    tables = {k: v for k, v in lod_files(base).items() if k.endswith('.TXT')}
    if os.path.exists(loc):
        tables.update({k: v for k, v in lod_files(loc).items() if k.endswith('.TXT')})
    def table(name):
        r = rows(lod_read(tables[name]))
        while r and not any(f.strip() for f in r[-1]):  # English HD tables end in empty rows
            r.pop()
        return r
    return table


def renamed(row, *names):
    r = list(row)
    for i, n in enumerate(names):
        r[i] = n
    return r


def insert_col(table, idx, header_name, value, header_row=1):
    """Insert one column into every row that is long enough (rows before header_row+1 get '')."""
    out = []
    for k, r in enumerate(table):
        if len(r) < idx:
            out.append(r)
            continue
        v = header_name if k == header_row else (b'' if k < header_row else value)
        out.append(r[:idx] + [v] + r[idx:])
    return out


def creatures_layout(table, n_header, placeholder_of, arrow_tower):
    head, recs = table[:n_header], [r for r in table[n_header:] if r and r[0].strip()]
    assert len(recs) == 122, len(recs)
    out = [None] * SOD_CREATURES
    for i, r in enumerate(recs):
        out[ROE_WARMACHINE_TO_SOD.get(i, i)] = r
    out[SOD_ARROW_TOWER] = arrow_tower(recs[ROE_BALLISTA])
    out = [r if r is not None else placeholder_of(recs[0]) for r in out]
    return head + out


HELP_ANCHORS = [('SCENARIO_OPTIONS', 45), ('ADVANCED_OPTIONS', 46), ('CHAT_DESC', 48),
                ('Multiplayer Selection', 263), ('ANIMATE_SPELLBOOK_ID,', 364), ('Adventure Options', 369),
                ('Recruit', 552)]
SOD_HELP = 640


def help_layout(rows):
    key = lambda r: r[0].strip().strip(b'"').decode('latin1') if r else ''
    marks, start = [(0, 0)], 0
    for name, sod in HELP_ANCHORS:
        i = next(i for i in range(start, len(rows)) if key(rows[i]) == name)
        marks.append((i, sod))
        start = i + 1
    out = [[b'', b''] for _ in range(SOD_HELP)]
    for k, (src, dst) in enumerate(marks):
        end_src = marks[k + 1][0] if k + 1 < len(marks) else len(rows)
        end_dst = marks[k + 1][1] if k + 1 < len(marks) else SOD_HELP
        for j in range(end_src - src):
            if dst + j < end_dst:
                out[dst + j] = rows[src + j]
    return out


def convert(src, dst):
    T = load_tables(src)
    lang = table_lang(src)
    out = {}

    # creatures: CRTRAITS (2 header rows) + CRANIM (2 header rows), same index layout
    # RoE lacks the SoD 'Spells' column after 'Shots' (col 19)
    crt = T('CRTRAITS.TXT')
    assert crt[1][19] == b'Shots' and crt[1][20] == b'Low', crt[1][19:21]
    crt = insert_col(crt, 20, b'Spells', b'0')
    out['CRTRAITS.TXT'] = creatures_layout(
        crt, 2,
        lambda r: renamed(r, PH, PH),
        lambda r: renamed(r, *((b'Pfeilturm', b'Pfeilt\xfcrme') if lang == 'de' else (b'Arrow Tower', b'Arrow Towers'))))
    out['CRANIM.TXT'] = creatures_layout(T('CRANIM.TXT'), 2, list, list)

    # artifacts: 127 -> 144, placeholders are class S (special = never random)
    art = T('ARTRAITS.TXT')
    # RoE has 18 slot columns, SoD 19: add MISC5 after MACH1 (col 7), filled like MISC1
    assert art[1][7] == b'Misc 4' and art[1][10] == b'Misc 1', art[1][7:11]
    art = [r[:7] + [b'Misc 5' if k == 1 else (r[10] if k > 1 else b'')] + r[7:] for k, r in enumerate(art)]
    head, recs = art[:2], art[2:]
    assert len(recs) == 127, len(recs)
    ph = renamed(recs[-1], PH)
    ph[-2] = b'S'
    ph[-1] = b''
    out['ARTRAITS.TXT'] = head + recs + [list(ph) for _ in range(SOD_ARTIFACTS - len(recs))]
    ev = T('ARTEVENT.TXT')
    out['ARTEVENT.TXT'] = ev + [[b''] for _ in range(SOD_ARTIFACTS - len(ev))]

    # hero classes 16 -> 18, plus the missing Conflux tavern-chance column
    hc = T('HCTRAITS.TXT')
    assert hc[1][49] == b'Fortress', hc[1][49]
    hc = insert_col(hc, 50, b'Conflux', b'0')
    out['HCTRAITS.TXT'] = hc + [renamed(hc[-1], PH) for _ in range(SOD_CLASSES - (len(hc) - 2))]

    # heroes 128 -> 156
    ho = T('HOTRAITS.TXT')
    out['HOTRAITS.TXT'] = ho + [renamed(ho[2], PH) for _ in range(SOD_HEROES - (len(ho) - 2))]
    hs = T('HEROSPEC.TXT')
    out['HEROSPEC.TXT'] = hs + [[PH, b'', b''] for _ in range(SOD_HEROES - (len(hs) - 2))]
    bios = T('HEROBIOS.TXT')
    nb = [[b''] for _ in range(SOD_HEROES)]
    for i, r in enumerate(bios):
        nb[ROE_BIO_TO_SOD.get(i, i)] = r
    out['HEROBIOS.TXT'] = nb

    # spells: Conflux gain-chance column after Fortress (col 23) and Acid Breath (SoD ability 80)
    sp = T('SPTRAITS.TXT')
    assert sp[1][23] == b'Fortress', sp[1][23]
    sp = insert_col(sp, 24, b'Conflux', b'0')
    while not sp[-1] or not sp[-1][0].strip():
        sp.pop()
    out['SPTRAITS.TXT'] = sp + [renamed(sp[-1], PH, PH)]

    # GENRLTXT/ARRAYTXT: the German HD tables contain bare LFs inside entries (the game splits on CRLF,
    # VCMI on LF) -> re-emit quoted. GENRLTXT: drop HD-only lines, add SoD-only tail as placeholders.
    # ARRAYTXT: SoD inserted 1 luck line after RoE 82, 4 battlefield lines after RoE 121 and
    # Conflux in the town-type names after RoE 221 (anchors checked against VCMI's own index use)
    at = T('ARRAYTXT.TXT')
    assert at[83][0] == b'cMoraleInfo' and at[122][0] == b'cNewTurn' and at[222][0] == b'NewLoadSave', \
        (at[83][0], at[122][0], at[222][0])
    out['ARRAYTXT.TXT'] = at[:83] + [[b'']] + at[83:122] + [[b'']] * 4 + at[122:222] + [[PH]] + at[222:]
    gt = T('GENRLTXT.TXT')
    assert len(gt) >= 1 + ROE_GENRLTXT, len(gt)
    li = 0 if lang == 'de' else 1
    own = {i: t[li].encode('latin1') for i, t in GENRLTXT_OWN.items()}
    # entries VCMI never shows for RoE content stay empty (format specifiers kept for MetaString)
    out['GENRLTXT.TXT'] = gt[:1 + ROE_GENRLTXT] + [
        [own.get(i, GENRLTXT_FMT.get(i, b'').strip())] for i in range(ROE_GENRLTXT, SOD_GENRLTXT)]

    # the German tables leave the per-town blacksmith names empty (game falls back to the generic one)
    bn = T('BLDGNEUT.TXT')
    out['BLDGNEUT.TXT'] = [[bn[16][0]] + r[1:] if i > 18 and r and not r[0].strip() else r
                           for i, r in enumerate(bn)]

    # HELP.TXT (core.help / zelp): SoD inserted rows (random map tab, new options, campaign bonuses) and
    # dropped a few RoE ones. Re-place each RoE section at its SoD index via anchor rows (first column);
    # rows a section cannot hold are dropped, holes stay empty. Anchors checked against VCMI's own uses.
    out['HELP.TXT'] = help_layout(T('HELP.TXT'))

    # BUILDING.TXT (costs): VCMI reads 9 factions; add zero-cost Conflux blocks after Fortress in the
    # unique-building and dwelling sections, otherwise every later cost is read from the wrong row
    bd = T('BUILDING.TXT')
    heads = [i for i, r in enumerate(bd) if r and r[0].strip() == b'Fortress']
    assert len(heads) == 2 and bd[heads[0] + 10][0].strip() == b'', heads
    zero = lambda n: [[b'0'] * 7 + [b' Conflux'] for _ in range(n)]
    sep = [b''] * 8
    out['BUILDING.TXT'] = (bd[:heads[0] + 10] + [sep, [b'Conflux'] + [b''] * 7] + zero(9) +
                           bd[heads[0] + 10:heads[1] + 15] + [sep, [b'Conflux'] + [b''] * 7] + zero(14) + bd[heads[1] + 15:])

    # factions 8 -> 9 (Conflux placeholder; the roe-support mod hides it)
    tt = T('TOWNTYPE.TXT')
    assert len(tt) == 8, len(tt)
    out['TOWNTYPE.TXT'] = tt + [[PH]]
    out['TOWNNAME.TXT'] = T('TOWNNAME.TXT') + [[PH] for _ in range(16)]

    # SoD-only keymaster tent colours (not in RoE); neutral colour words
    tents = (b'Hellblau,Gr\xfcn,Rot,Dunkelblau,Braun,Lila,Wei\xdf,Schwarz' if lang == 'de'
             else b'Light Blue,Green,Red,Dark Blue,Brown,Purple,White,Black')
    out['TENTCOLR.TXT'] = [[w] for w in tents.split(b',')]

    os.makedirs(dst, exist_ok=True)
    # VCMI's hero screen is the SoD background HeroScr4 (adds the 5th misc slot); RoE ships HEROSCR3
    with open(os.path.join(dst, 'HEROSCR4.PCX'), 'wb') as f:
        f.write(lod_read(lod_files(os.path.join(src, 'H3bitmap.lod'))['HEROSCR3.PCX']))
    for name, rws in out.items():
        with open(os.path.join(dst, name), 'wb') as f:
            f.write(dump(rws))
    return out


def selfcheck(out):
    cr = [r for r in out['CRTRAITS.TXT'][2:]]
    assert len(cr) == SOD_CREATURES and cr[146][0] in (b'Balliste', b'Ballista') and cr[149][0] in (b'Pfeilturm', b'Arrow Tower'), cr[146][0]
    assert len(out['CRANIM.TXT']) - 2 == SOD_CREATURES
    assert len(out['ARTRAITS.TXT']) - 2 == SOD_ARTIFACTS == len(out['ARTEVENT.TXT'])
    assert len(out['HOTRAITS.TXT']) - 2 == SOD_HEROES == len(out['HEROSPEC.TXT']) - 2 == len(out['HEROBIOS.TXT'])
    assert len(out['HCTRAITS.TXT']) - 2 == SOD_CLASSES
    assert len(out['GENRLTXT.TXT']) - 1 == SOD_GENRLTXT and PH not in b''.join(r[0] for r in out['GENRLTXT.TXT'])
    assert all(r[0].strip() for r in out['BLDGNEUT.TXT'][19:28]), 'blacksmith names'
    h = out['HELP.TXT']
    assert h[370][1].startswith(b'{') and h[48][0].strip() == b'CHAT_DESC' and h[553][0] == b'Maximum', (h[370], h[553])
    at = [r[0] for r in out['ARRAYTXT.TXT']]
    # section labels at the SoD positions VCMI indexes from (128 = days-left text, 229 = select scenario)
    assert at[84] == b'cMoraleInfo' and at[127] == b'cNewTurn' and at[228] == b'NewLoadSave', at[127]
    assert sum(1 for r in out['SPTRAITS.TXT'] if len(r) > 3 and r[2].strip().isdigit()) == 81


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    o = convert(sys.argv[1], sys.argv[2])
    selfcheck(o)
    print('ok:', ', '.join(sorted(o)))
