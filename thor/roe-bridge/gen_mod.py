#!/usr/bin/env python3
"""Generate the VCMI mod 'roe-support' (pattern of vcmi-mods/demo-support) into ./mod.

Hides everything that needs Armageddon's Blade / Shadow of Death data:
Conflux town, AB/SoD creatures, Conflux + campaign heroes, AB battlefields.
Reads ids from VCMI's own config (src/config), writes only JSON + empty null assets.
"""
import json, os, shutil
from vcmiidx import SRC, load, index
import campaigns

HERE = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.join(HERE, 'mod')
CFG = os.path.join(MOD, 'content', 'config', 'roe-support')

SOD_CREATURES = range(118, 145)   # Conflux + SoD neutrals (war machines 145..148 exist in RoE)
SOD_HEROES = range(128, 156)      # Conflux heroes + AB/SoD campaign heroes
# special battlefield -> RoE battlefield whose background (and obstacles) it borrows
BF_DONOR = {'fiery_fields': 'lava', 'rocklands': 'rough', 'magic_clouds': 'magic_plains',
            'lucid_pools': 'magic_plains', 'holy_ground': 'grass_pines', 'clover_field': 'grass_hills',
            'evil_fog': 'cursed_ground'}
DONOR_TERRAIN = {'lava': 'lava', 'rough': 'rough', 'grass_pines': 'grass', 'grass_hills': 'grass'}
NULL_SOUNDS = {k: 'null.wav' for k in ('attack', 'defend', 'killed', 'move', 'shoot', 'wince')}


def png_blank(w, h):
    """Fully transparent RGBA PNG (stdlib only)."""
    import struct, zlib
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
    raw = b''.join(b'\0' + b'\0' * 4 * w for _ in range(h))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def write(rel, data):
    p = os.path.join(CFG, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent='\t', ensure_ascii=False)
    return 'config/roe-support/' + rel


def main(lod_names, tables=None):
    if os.path.isdir(MOD):
        shutil.rmtree(MOD)
    cr = index('creatures/*.json')
    he = index('heroes/*.json')
    files = {}

    files['creatures'] = [write('creatures.json', {
        f'core:{cr[i]}': {'disabled': True, 'graphics': None, 'sound': NULL_SOUNDS} for i in SOD_CREATURES})]

    files['heroes'] = [write('heroes.json', {f'core:{he[i]}': {'special': True} for i in SOD_HEROES})]

    # Conflux: no town art in RoE -> special faction, null backgrounds/borders (as demo-support does)
    conflux = load(os.path.join(SRC, 'factions', 'conflux.json'))['conflux']
    structs = {k: {'border': 'null', 'area': 'null'} for k, v in conflux['town']['structures'].items()
               if 'border' in v or 'area' in v}
    files['factions'] = [write('conflux.json', {'core:conflux': {
        'special': True,
        'creatureBackground': {'120px': 'null', '130px': 'null'},
        'town': {'structures': structs, 'musicTheme': ['music/null'], 'guildBackground': 'null',
                 'townBackground': 'null', 'guildWindow': 'null', 'hallBackground': 'null'}}})]

    # AB/SoD special battlefields have no RoE art -> nearest RoE background + its obstacles
    bf = load(os.path.join(SRC, 'battlefields.json'))
    stems = {n.rsplit('.', 1)[0] for n in lod_names}  # images: VCMI ignores the extension (.BMP vs .PCX in LOD)
    have = lambda g: g.upper().rsplit('.', 1)[0] in stems
    assert all(have(bf[d]['graphics']) for d in BF_DONOR.values())
    missing = {k for k, v in bf.items() if isinstance(v, dict) and not have(v.get('graphics', ''))}
    assert missing <= set(BF_DONOR), missing - set(BF_DONOR)
    files['battlefields'] = [write('battlefields.json', {
        f'core:{k}': {'graphics': bf[BF_DONOR[k]]['graphics']} for k in sorted(missing)})]

    ob = {k: v for k, v in load(os.path.join(SRC, 'obstacles.json')).items() if isinstance(v, dict)}
    obst = {}
    for k, v in ob.items():
        if not have(v['animation']):  # AB obstacle art -> never placed (valid stand-in art for the validator)
            stand_in = next(o['animation'] for o in ob.values() if have(o['animation']) and o.get('absolute') == v.get('absolute'))
            obst[f'core:{k}'] = {'allowedTerrains': [], 'specialBattlefields': [], 'animation': stand_in}
            continue
        sp = list(v.get('specialBattlefields', []))
        add = [m for m in sorted(missing) if BF_DONOR[m] in sp or DONOR_TERRAIN.get(BF_DONOR[m]) in v.get('allowedTerrains', [])]
        if add and not v.get('absolute'):
            obst[f'core:{k}'] = {'specialBattlefields': sp + add}
    files['obstacles'] = [write('obstacles.json', obst)]

    # main menu: RoE has no campaign-set buttons (CSS*.DEF) -> "Campaign" opens the RoE set directly
    mm = load(os.path.join(SRC, 'mainmenu.json'))
    for item in mm['window']['items']:
        for b in item['buttons']:
            if b['command'] == 'to campaign':
                b['command'] = 'campaigns roe'
    mm['window']['items'] = [i for i in mm['window']['items'] if i['name'] != 'campaign']
    p = os.path.join(MOD, 'content', 'config', 'mainmenu.json')
    with open(p, 'w', encoding='utf-8') as f:
        json.dump(mm, f, indent='\t')

    # SoD map-format icons (SCSELC.DEF) do not exist in RoE -> blank frames instead of the "missing" sign
    blank = os.path.join(MOD, 'content', 'sprites', 'roe-support', 'blank.png')
    os.makedirs(os.path.dirname(blank), exist_ok=True)
    with open(blank, 'wb') as f:
        f.write(png_blank(1, 1))
    with open(os.path.join(MOD, 'content', 'sprites', 'SCSELC.json'), 'w') as f:
        json.dump({'images': [{'frame': i, 'file': 'roe-support/blank.png'} for i in range(4)]}, f, indent='\t')

    # SoD creature portraits are indexed creature+2; RoE DEFs end after its 122 creatures. War machines
    # (SoD 145..148) and the arrow tower (149, stand-in: ballista) reuse the RoE frames of the same DEF.
    roe_frame = {145: 118, 146: 119, 147: 120, 148: 121, 149: 119}
    for deff in ('CPRSMALL', 'TWCRPORT'):
        with open(os.path.join(MOD, 'content', 'sprites', deff + '.json'), 'w') as f:
            json.dump({'images': [{'frame': sod + 2, 'defFile': deff + '.DEF', 'defFrame': roe + 2}
                                  for sod, roe in roe_frame.items()]}, f, indent='\t')

    # RoE 1.0 campaigns (H3C v1) -> .vcmp; mod files override the LOD entries of the same name
    if tables:
        campaigns.build(tables, os.path.join(MOD, 'content', 'data'))

    for d, n in (('sounds', 'null.wav'), ('music', 'null.ogg'), ('sprites', 'null.png')):
        os.makedirs(os.path.join(MOD, 'content', d), exist_ok=True)
        open(os.path.join(MOD, 'content', d, n), 'wb').close()

    mod = {
        'name': 'RoE support',
        'description': 'Play VCMI with Restoration of Erathia data (e.g. the Steam HD Edition). '
                       'Hides content that needs Armageddon\'s Blade / Shadow of Death. '
                       'Needs the text tables converted by the Thor Edition RoE bridge.',
        'author': 'Thor Edition for VCMI (pattern: vcmi-mods/demo-support, CC-BY-SA 4.0)',
        'modType': 'Other',
        'licenseName': 'Creative Commons Attribution-ShareAlike 4.0 (structure after vcmi-mods/demo-support)',
        'licenseURL': 'https://creativecommons.org/licenses/by-sa/4.0/',
        'version': '0.2',
        **files,
    }
    with open(os.path.join(MOD, 'mod.json'), 'w', encoding='utf-8') as f:
        json.dump(mod, f, indent='\t', ensure_ascii=False)
    return mod


if __name__ == '__main__':
    import sys
    from legacy import lod_files
    tables = {}
    for lod in sys.argv[1:]:  # pass the localized LOD (H3pbitma) last so it wins
        tables.update(lod_files(lod))
    assert tables, 'usage: gen_mod.py <H3bitmap.lod> [<H3sprite.lod> ...] [<H3pbitma.lod>]'
    m = main(set(tables), tables)
    print({k: v for k, v in m.items() if isinstance(v, list)})
