import sys, glob, os, json
sys.path.insert(0, '.')
from svg2scene import Board, mmul, IDENT

def F(x):
    s = ('%.4f' % x).rstrip('0').rstrip('.')
    if s in ('-0', ''): s = '0'
    return s + 'f'

def pack(board, leaves):
    """-> dict of tables + node list (ints into tables)."""
    grads = board.gradients()
    mats, anims, clips, chains, colors, gl = [], [], [], [], [], []
    def idx(lst, v):
        if v in lst: return lst.index(v)
        lst.append(v); return len(lst) - 1
    chains.append(())
    nodes = []
    def fillcode(c):
        if c is None: return 0
        k, v = c
        if k == 'ink': return v
        if k == 'grad': return 10 + idx(gl, v)
        return 30 + idx(colors, v)
    for lf in leaves:
        # merge consecutive static matrices, drop identity
        steps = []
        for k, v in lf['chain']:
            if k == 'm':
                if steps and steps[-1][0] == 'm': steps[-1] = ('m', mmul(steps[-1][1], v))
                else: steps.append(('m', v))
            else: steps.append((k, v))
        enc = []
        for k, v in steps:
            if k == 'm':
                v = tuple(round(x, 4) for x in v)
                if v == IDENT: continue
                enc.append(idx(mats, v))
            elif k == 'a':
                key = json.dumps(v, sort_keys=True)
                enc.append(1000 + idx(anims, key))
            else: enc.append(2000 + idx(clips, v))
        ch = idx(chains, tuple(enc))
        nodes.append(dict(d=lf['d'], c=ch, f=fillcode(lf['fill']), fa=lf['fa'], s=fillcode(lf['stroke']),
                          sa=lf['sa'], sw=lf['sw'], cap=lf['cap'], ev=lf['ev']))
    return dict(mats=mats, anims=[json.loads(a) for a in anims], clips=clips, chains=chains, colors=colors,
                grads=[(g, grads[g]) for g in gl], nodes=nodes)

def hexl(h, a=1.0):
    return '0x%02X%sL' % (round(a * 255), h[1:].upper())

def emit_scene(name, w, h, P, out):
    L = out.append
    L(f'    val {name}: LangScene by lazy {{')
    L('        LangScene(')
    L(f'            w = {F(w)}, h = {F(h)},')
    L('            mats = floatArrayOf(' + ', '.join(F(x) for m in P['mats'] for x in m) + '),')
    L('            anims = arrayOf(')
    for a in P['anims']:
        tf = 'floatArrayOf(' + ', '.join(F(x) for s in a['tf'] for x in s) + ')' if a['tf'] else 'null'
        op = 'floatArrayOf(' + ', '.join(F(x) for s in a['op'] for x in s) + ')' if a['op'] else 'null'
        L(f"                LangAnim({F(a['dur'])}, {F(a['delay'])}, {a['ease']}, {F(a['ox'])}, {F(a['oy'])}, {tf}, {op}),")
    L('            ),')
    L('            clips = arrayOf(' + ', '.join('floatArrayOf(' + ', '.join(F(x) for r in c for x in r) + ')' for c in P['clips']) + '),')
    L('            chains = arrayOf(' + ', '.join('intArrayOf(' + ', '.join(
        (f'lm({c})' if c < 1000 else f'la({c-1000})' if c < 2000 else f'lk({c-2000})') for c in ch) + ')' for ch in P['chains']) + '),')
    L('            grads = arrayOf(')
    for gid, g in P['grads']:
        L(f"                LangGrad({'true' if g['radial'] else 'false'}, floatArrayOf({', '.join(F(x) for x in g['a'])}), "
          f"floatArrayOf({', '.join(F(s[0]) for s in g['stops'])}), longArrayOf({', '.join(hexl(s[1], s[2]) for s in g['stops'])})),")
    L('            ),')
    L('            colors = longArrayOf(' + ', '.join(hexl(c) for c in P['colors']) + '),')
    L('            nodes = ' + ' + '.join(f'{name}Nodes{i}()' for i in range((len(P['nodes']) + 59) // 60)) or 'emptyArray()')
    L('        )')
    L('    }')
    # chunked node functions
    for i in range(0, len(P['nodes']), 60):
        L(f'    private fun {name}Nodes{i//60}() = arrayOf(')
        for n in P['nodes'][i:i+60]:
            args = [f'"{n["d"]}"', str(n['c']), str(n['f']), F(n['fa']), str(n['s']), F(n['sa']), F(n['sw']), str(n['cap']), 'true' if n['ev'] else 'false']
            L('        LangNode(' + ', '.join(args) + '),')
        L('    )')

def main(proj, outdir):
    os.makedirs(outdir, exist_ok=True)
    meta = {}
    for f in sorted(glob.glob(proj + '/*.dc.html')):
        b = Board(f); mk = pack(b, b.scene_mark()); am = pack(b, b.scene_amb())
        assert not b.warn, b.warn
        cls = b.code.capitalize() + 'Art'
        out = ['// GENERATED from the ' + b.code + ' board of the Fluvour language marks design. Do not hand-edit; see docs/LANG_ART.md.',
               'package app.fluvour.ui.art.lang', '', 'import app.fluvour.ui.art.*', '',
               f'/** {b.code}: inks il1..il5 = {", ".join(b.inks)}; accent {b.accent}. */',
               f'internal object {cls} {{']
        emit_scene('mark', 140, 140, mk, out)
        emit_scene('ambient', 296, 300, am, out)
        out.append('}')
        open(f'{outdir}/{cls}.kt', 'w').write('\n'.join(out) + '\n')
        meta[b.code] = dict(inks=b.inks, accent=b.accent, nodes=(len(mk['nodes']), len(am['nodes'])), P=(mk, am))
        print(b.code, len(mk['nodes']), len(am['nodes']), len(mk['anims']), len(am['anims']), len(mk['chains']), os.path.getsize(f'{outdir}/{cls}.kt'))
    json.dump({k: dict(inks=v['inks'], accent=v['accent']) for k, v in meta.items()}, open(outdir + '/meta.json', 'w'))
    return meta
if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '../fl/project', sys.argv[2] if len(sys.argv) > 2 else 'out/lang')
