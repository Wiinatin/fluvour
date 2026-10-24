"""Parse one Fluvour language board (.dc.html) into scene dicts for the Kotlin emitter."""
import re, math, sys, json
import xml.etree.ElementTree as ET
from svgelements import Path as SPath, Matrix as SMatrix
sys.path.insert(0, '.')
from cssparse import parse_css, decls

IDENT = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
def mmul(m, n):
    a, b, c, d, e, f = m; A, B, C, D, E, F = n
    return (a*A + c*B, b*A + d*B, a*C + c*D, b*C + d*D, a*E + c*F + e, b*E + d*F + f)
def translate(x, y=0.0): return (1, 0, 0, 1, x, y)
def scale(x, y=None): return (x, 0, 0, x if y is None else y, 0, 0)
def rotate(deg, cx=0.0, cy=0.0):
    r = math.radians(deg); co, si = math.cos(r), math.sin(r)
    m = (co, si, -si, co, 0, 0)
    return mmul(mmul(translate(cx, cy), m), translate(-cx, -cy))
def parse_transform(s):
    m = IDENT
    for fn, args in re.findall(r'(\w+)\(([^)]*)\)', s or ''):
        v = [float(x) for x in re.split(r'[\s,]+', args.strip()) if x]
        if fn == 'translate': t = translate(v[0], v[1] if len(v) > 1 else 0)
        elif fn == 'scale': t = scale(v[0], v[1] if len(v) > 1 else None)
        elif fn == 'rotate': t = rotate(v[0], *(v[1:3] if len(v) == 3 else (0, 0)))
        elif fn == 'matrix': t = tuple(v)
        elif fn == 'skewX': t = (1, 0, math.tan(math.radians(v[0])), 1, 0, 0)
        elif fn == 'skewY': t = (1, math.tan(math.radians(v[0])), 0, 1, 0, 0)
        else: raise ValueError(fn)
        m = mmul(m, t)
    return m

INH = ['fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'fill-rule', 'fill-opacity', 'stroke-opacity']
SKIP = {'defs', 'linearGradient', 'radialGradient', 'clipPath', 'filter', 'style', 'title', 'symbol', 'stop'}
def lname(el): return el.tag.split('}')[-1]

class Board:
    def __init__(self, path):
        s = open(path).read()
        self.src = s
        css = s[s.find('<style>') + 7:s.find('</style>')]
        self.rules, self.kf = parse_css(css)
        # defs block + symbol
        a = s.find('<svg width="0" height="0"'); b = s.find('</svg>', a) + 6
        defs = ET.fromstring('<root>' + s[a:b] + '</root>')
        a2 = s.find('<svg id="amb"'); b2 = s.find('</svg>', a2) + 6
        self.amb = ET.fromstring(s[a2:b2])
        self.ids = {}
        for root in (defs, self.amb):
            for el in root.iter():
                if el.get('id'): self.ids[el.get('id')] = el
        sym = [e for i, e in self.ids.items() if i.startswith('m-')]
        assert len(sym) == 1, path
        self.sym = sym[0]; self.code = self.sym.get('id')[2:]
        m = re.search(r'--il1:(#\w+);--il2:(#\w+);--il3:(#\w+);--il4:(#\w+);--il5:(#\w+)', s)
        self.inks = list(m.groups())
        self.accent = re.search(r'--ac:(#\w+)', s).group(1)
        self.warn = []

    # ---- style resolution
    def class_decls(self, el):
        out = {}
        for c in (el.get('class') or '').split():
            for sels, d in self.rules:
                if '.' + c in sels: out.update(d)
        return out
    def style_of(self, el, inh, vars_):
        st = dict(inh); vars_ = dict(vars_)
        for k in INH:
            if el.get(k) is not None: st[k] = el.get(k)
        cd = self.class_decls(el)
        inline = decls(el.get('style') or '')
        merged = {**cd, **inline}
        for k, v in merged.items():
            if k.startswith('--'): vars_[k] = v
            elif k in INH: st[k] = v
        return st, vars_, merged

    def resolve_color(self, v, vars_):
        if v is None: return None
        v = v.strip()
        mm = re.fullmatch(r'var\(--il(\d)\)', v)
        if mm: return ('ink', int(mm.group(1)))
        m = re.fullmatch(r'var\((--\w+)(?:,\s*([^)]*))?\)', v)
        if m: v = vars_.get(m.group(1)) or (m.group(2) or '')
        if v == 'none' or v == '': return None
        m = re.fullmatch(r'--il(\d)', v)
        mm = re.fullmatch(r'var\(--il(\d)\)', v)
        if mm: return ('ink', int(mm.group(1)))
        if v.startswith('url('): return ('grad', re.search(r'#([\w-]+)', v).group(1))
        if v.startswith('#'): return ('rgb', v.upper())
        raise ValueError('color ' + v)

    # ---- geometry
    def shape_d(self, el):
        t = lname(el); g = lambda k, d=0.0: float(el.get(k, d))
        if t == 'path': return el.get('d')
        if t in ('circle', 'ellipse'):
            cx, cy = g('cx'), g('cy')
            rx = g('rx') if t == 'ellipse' else g('r'); ry = g('ry') if t == 'ellipse' else g('r')
            f = lambda x: ('%.4f' % x).rstrip('0').rstrip('.')
            return f'M{f(cx-rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2*rx)} 0a{f(rx)} {f(ry)} 0 1 0 {f(-2*rx)} 0z'
        if t == 'rect':
            x, y, w, h = g('x'), g('y'), g('width'), g('height')
            rx = float(el.get('rx', el.get('ry', 0)) or 0); ry = float(el.get('ry', el.get('rx', 0)) or 0)
            f = lambda v: ('%.4f' % v).rstrip('0').rstrip('.')
            if rx == 0 and ry == 0: return f'M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}z'
            return (f'M{f(x+rx)} {f(y)}h{f(w-2*rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}v{f(h-2*ry)}'
                    f'a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}h{f(-(w-2*rx))}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}'
                    f'v{f(-(h-2*ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}z')
        raise ValueError(t)

    # ---- animation
    def parse_anim(self, merged, vars_, bbox):
        if 'animation' not in merged: return None
        parts = merged['animation'].split()
        name, dur, ease = parts[0], float(parts[1].rstrip('s')), parts[2]
        delay = float(merged.get('animation-delay', '0s').rstrip('s'))
        dur = float(merged.get('animation-duration', parts[1]).rstrip('s'))
        if delay > 0: self.warn.append(f'positive delay {name}')
        ob = merged.get('transform-box') == 'fill-box'
        org = merged.get('transform-origin', '0 0').split()
        def pct(x, lo, size):
            if x.endswith('%'): return float(x[:-1]) / 100
            return float(x.rstrip('px')) / size if size else 0
        if ob and bbox:
            x0, y0, x1, y1 = bbox
            ox = x0 + pct(org[0], x0, x1 - x0) * (x1 - x0); oy = y0 + pct(org[1], y0, y1 - y0) * (y1 - y0)
        else:
            ox = oy = 0.0
            if not ob and merged.get('transform-origin'): self.warn.append('origin without fill-box')
        tf, op = self.channels(name, vars_)
        easen = {'linear': 0, 'ease-in-out': 1, 'ease-out': 2}[ease]
        return dict(name=name, dur=dur, delay=delay, ease=easen, ox=ox, oy=oy, tf=tf, op=op)

    def channels(self, name, vars_):
        frames = self.kf[name]
        tfst = []; opst = []
        for pct, d in frames:
            t = pct / 100
            if 'transform' in d:
                s = d['transform']
                s = re.sub(r'var\((--\w+),\s*([^)]*)\)', lambda m: vars_.get(m.group(1), m.group(2)), s)
                tx = ty = rot = 0.0; sx = sy = 1.0; stage = 0
                for fn, args in re.findall(r'(\w+)\(([^)]*)\)', s):
                    v = [float(re.sub(r'[a-z%]+$', '', x)) for x in re.split(r'[\s,]+', args.strip()) if x]
                    if fn in ('translate', 'translateX', 'translateY'):
                        assert stage == 0, (name, s)
                        if fn == 'translate': tx += v[0]; ty += v[1] if len(v) > 1 else 0
                        elif fn == 'translateX': tx += v[0]
                        else: ty += v[0]
                    elif fn == 'rotate': assert stage <= 1; stage = 1; rot += v[0]
                    elif fn in ('scale', 'scaleX', 'scaleY'):
                        stage = 2
                        if fn == 'scale': sx *= v[0]; sy *= v[1] if len(v) > 1 else v[0]
                        elif fn == 'scaleX': sx *= v[0]
                        else: sy *= v[0]
                    else: raise ValueError(fn)
                tfst.append([t, tx, ty, rot, sx, sy])
            if 'opacity' in d: opst.append([t, float(d['opacity'])])
        def pad(st, ident):
            if not st: return None
            if st[0][0] > 0: st.insert(0, [0.0] + ident)
            if st[-1][0] < 1: st.append([1.0] + ident)
            return st
        return pad(tfst, [0, 0, 0, 1, 1]), pad(opst, [1.0])

    # ---- tree build: returns leaves; each leaf = dict(d, fill, fa, stroke, sa, sw, cap, ev, chain)
    def build(self, el, inh, vars_, opm=1.0, depth=0):
        t = lname(el)
        if t in SKIP: return []
        st, vars2, merged = self.style_of(el, inh, vars_)
        own_op = float(merged.get('opacity', el.get('opacity', 1)))
        if 'animation' in merged and any('opacity' in d for _, d in self.kf[merged['animation'].split()[0]]): own_op = 1.0
        op = opm * own_op
        own = []; anim = None
        # children
        if t in ('g', 'svg'):
            leaves = []
            for ch in el: leaves += self.build(ch, st, vars2, op, depth + 1)
        elif t == 'use':
            tgt = self.ids[el.get('href').lstrip('#')]
            x, y = float(el.get('x', 0)), float(el.get('y', 0))
            if lname(tgt) == 'symbol':
                leaves = []
                for ch in tgt: leaves += self.build(ch, st, vars2, op, depth + 1)
                vb = [float(v) for v in tgt.get('viewBox').split()]
                w, h = float(el.get('width')), float(el.get('height'))
                k = min(w / vb[2], h / vb[3])
                tx = x + (w - vb[2] * k) / 2 - vb[0] * k; ty = y + (h - vb[3] * k) / 2 - vb[1] * k
                leaves = self.wrap(leaves, [('m', (k, 0, 0, k, tx, ty))])
            else:
                leaves = self.build(tgt, st, vars2, op, depth + 1)
                if x or y: leaves = self.wrap(leaves, [('m', translate(x, y))])
        else:
            leaves = self.shape_leaf(el, st, vars2, op)
        if not leaves: return []
        # own steps
        if 'animation' in merged:
            if el.get('transform'): self.warn.append('transform attr overridden by animation on ' + t)
            bbox = self.bbox(leaves)
            anim = self.parse_anim(merged, vars2, bbox)
            own.append(('a', anim))
        else:
            tm = parse_transform(el.get('transform'))
            if tm != IDENT: own.append(('m', tm))
        if el.get('clip-path'):
            cid = re.search(r'#([\w-]+)', el.get('clip-path')).group(1)
            rects = []
            for r in self.ids[cid]:
                rects.append([float(r.get(k)) for k in ('x', 'y', 'width', 'height')])
            own.append(('k', tuple(map(tuple, rects))))
        # a `use` carries its transform attr first
        return self.wrap(leaves, own)
    def wrap(self, leaves, own):
        out = []
        for lf in leaves:
            l2 = dict(lf); l2['chain'] = own + lf['chain']; out.append(l2)
        return out
    def shape_leaf(self, el, st, vars_, op):
        d = self.shape_d(el)
        fill = self.resolve_color(st.get('fill', 'black'), vars_) if st.get('fill', 'black') != 'black' else ('ink', 4)
        if st.get('fill') is None: self.warn.append('no fill on ' + lname(el))
        stroke = self.resolve_color(st.get('stroke', 'none'), vars_)
        if fill is None and stroke is None: return []
        return [dict(d=d, fill=fill, fa=float(st.get('fill-opacity', 1)) * op,
                     stroke=stroke, sa=float(st.get('stroke-opacity', 1)) * op,
                     sw=float(st.get('stroke-width', 1)), cap={'butt': 0, 'round': 1, 'square': 2}[st.get('stroke-linecap', 'butt')],
                     ev=st.get('fill-rule') == 'evenodd', chain=[])]
    def static_matrix(self, chain):
        m = IDENT
        for k, v in chain:
            if k == 'm': m = mmul(m, v)
        return m
    def bbox(self, leaves):
        xs = []
        for lf in leaves:
            m = self.static_matrix(lf['chain'])
            p = SPath(lf['d']) * SMatrix(*m)
            b = p.bbox()
            if b: xs.append(b)
        if not xs: return None
        return (min(b[0] for b in xs), min(b[1] for b in xs), max(b[2] for b in xs), max(b[3] for b in xs))

    def gradients(self):
        out = {}
        for i, el in self.ids.items():
            t = lname(el)
            if t not in ('linearGradient', 'radialGradient'): continue
            stops = []
            for s in el:
                col = s.get('stop-color'); o = float(s.get('offset')); a = float(s.get('stop-opacity', 1))
                stops.append((o, col.upper(), a))
            if t == 'linearGradient':
                a = [float(el.get(k, d)) for k, d in (('x1', 0), ('y1', 0), ('x2', 1), ('y2', 0))]
            else:
                a = [float(el.get('cx', .5)), float(el.get('cy', .5)), float(el.get('r', .5))]
            assert el.get('gradientUnits') in (None, 'objectBoundingBox')
            out[i] = dict(radial=t == 'radialGradient', a=a, stops=stops)
        return out

    def scene_mark(self):
        leaves = []
        for ch in self.sym: leaves += self.build(ch, {}, {})
        return leaves
    def scene_amb(self):
        leaves = []
        for ch in self.amb: leaves += self.build(ch, {}, {})
        return leaves

if __name__ == '__main__':
    import glob
    for f in sorted(glob.glob('../fl/project/*.dc.html')):
        b = Board(f); mk = b.scene_mark(); am = b.scene_amb()
        print(b.code, len(mk), len(am), sorted(set(b.warn)), b.inks, b.accent)
