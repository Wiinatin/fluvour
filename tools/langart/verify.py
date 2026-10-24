import sys, math, json, re, asyncio
sys.path.insert(0,'.')
from emit import pack
from svg2scene import Board
from playwright.sync_api import sync_playwright
from PIL import Image, ImageChops
import numpy as np, glob

def ease(k,u):
    if k==0: return u
    # cubic bezier solve
    x1,y1,x2,y2=(.42,0,.58,1) if k==1 else (0,0,.58,1)
    lo,hi=0.0,1.0
    for _ in range(40):
        mid=(lo+hi)/2
        x=3*(1-mid)**2*mid*x1+3*(1-mid)*mid**2*x2+mid**3
        if x<u: lo=mid
        else: hi=mid
    s=(lo+hi)/2
    return 3*(1-s)**2*s*y1+3*(1-s)*s**2*y2+s**3
def sample(a,p,k):
    n=len(a)
    i=0
    while i<n-2 and p>=a[i+1][0]: i+=1
    t0,t1=a[i][0],a[i+1][0]
    u=min(max((p-t0)/(t1-t0),0),1) if t1>t0 else 1
    u=ease(k,u)
    return [a[i][j]+(a[i+1][j]-a[i][j])*u for j in range(1,len(a[i]))]
def mul(m,n):
    a,b,c,d,e,f=m;A,B,C,D,E,F=n
    return (a*A+c*B,b*A+d*B,a*C+c*D,b*C+d*D,a*E+c*F+e,b*E+d*F+f)
def snapshot(P,b,t,w,h,inks):
    defs=[];body=[]
    for i,(gid,g) in enumerate(P['grads']):
        st=''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o,c,a in g['stops'])
        if g['radial']: defs.append(f'<radialGradient id="G{i}" cx="{g["a"][0]}" cy="{g["a"][1]}" r="{g["a"][2]}">{st}</radialGradient>')
        else: defs.append(f'<linearGradient id="G{i}" x1="{g["a"][0]}" y1="{g["a"][1]}" x2="{g["a"][2]}" y2="{g["a"][3]}">{st}</linearGradient>')
    def col(c):
        if c==0: return 'none'
        if c<=5: return inks[c-1]
        if c<30: return f'url(#G{c-10})'
        return P['colors'][c-30]
    for n in P['nodes']:
        m=(1,0,0,1,0,0);op=1;wrap_open='';wrap_close=''
        for st in P['chains'][n['c']]:
            if st<1000: m=mul(m,P['mats'][st])
            elif st<2000:
                an=P['anims'][st-1000]
                p=((t-an['delay'])/an['dur'])%1.0
                if an['tf']:
                    tx,ty,rot,sx,sy=sample(an['tf'],p,an['ease'])
                    r=math.radians(rot);co,si=math.cos(r),math.sin(r)
                    a,bb,c,d=co*sx,si*sx,-si*sy,co*sy
                    m=mul(m,(a,bb,c,d,an['ox']+tx-(a*an['ox']+c*an['oy']),an['oy']+ty-(bb*an['ox']+d*an['oy'])))
                if an['op']: op*=sample(an['op'],p,an['ease'])[0]
            else:
                cid=f'C{st-2000}_{len(body)}'
                defs.append(f'<clipPath id="{cid}">'+''.join(f'<rect x="{r[0]}" y="{r[1]}" width="{r[2]}" height="{r[3]}"/>' for r in P['clips'][st-2000])+'</clipPath>')
                wrap_open+=f'<g transform="matrix({",".join(map(str,m))})" clip-path="url(#{cid})">'; wrap_close+='</g>'; m=(1,0,0,1,0,0)
        fe='evenodd' if n['ev'] else 'nonzero'
        s=f'<path d="{n["d"]}" transform="matrix({",".join(map(str,m))})" fill="{col(n["f"])}" fill-opacity="{n["fa"]*op}" fill-rule="{fe}"'
        if n['s']: s+=f' stroke="{col(n["s"])}" stroke-opacity="{n["sa"]*op}" stroke-width="{n["sw"]}" stroke-linecap="{["butt","round","square"][n["cap"]]}"'
        body.append(wrap_open+s+'/>'+wrap_close)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w*2}" height="{h*2}"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>'

T=float(sys.argv[1]) if len(sys.argv)>1 else 3.7
with sync_playwright() as pw:
    br=pw.chromium.launch(); pg=br.new_page(viewport={'width':700,'height':700})
    for f in sorted(glob.glob('sources/*.dc.html')):
        b=Board(f); src=b.src
        for kind,(w,h) in (('mark',(140,140)),('amb',(296,300))):
            P=pack(b,b.scene_mark() if kind=='mark' else b.scene_amb())
            # original: standalone page
            css=src[src.find('<style>')+7:src.find('</style>')]
            a=src.find('<svg width="0" height="0"'); bb=src.find('</svg>',a)+6
            defs=src[a:bb]
            if kind=='mark':
                si=src.find(f'<symbol id="m-{b.code}"'); si=src.find('>',si)+1; se=src.find('</symbol>',si)
                inner=f'<svg viewBox="0 0 140 140" width="280" height="280">{src[si:se]}</svg>'
            else:
                a2=src.find('<svg id="amb"'); b2=src.find('</svg>',a2)+6
                inner=src[a2:b2].replace('id="amb"','').replace('preserveAspectRatio="xMidYMin slice"','').replace('width="296" height="300"','width="592" height="600"')
                inner=re.sub(r'style="[^"]*mask[^"]*"','',inner,count=1)
            vars_=';'.join(f'--il{i+1}:{c}' for i,c in enumerate(b.inks))
            html=f'<html><style>{css}</style><body style="margin:0;background:#fff;{vars_}">{defs}{inner}</body></html>'
            pg.set_content(html); pg.evaluate(f'document.getAnimations().forEach(a=>{{a.currentTime={T*1000};a.pause()}})')
            pg.screenshot(path='o.png',clip={'x':0,'y':0,'width':w*2,'height':h*2})
            svg=snapshot(P,b,T,w,h,b.inks)
            pg.set_content(f'<html><body style="margin:0;background:#fff">{svg}</body></html>')
            pg.screenshot(path='s.png',clip={'x':0,'y':0,'width':w*2,'height':h*2})
            A=np.asarray(Image.open('o.png').convert('RGB')).astype(int);B=np.asarray(Image.open('s.png').convert('RGB')).astype(int)
            d=np.abs(A-B).max(axis=2)
            print(b.code,kind,'diff>40 px:',int((d>40).sum()),'of',d.size, 'max',int(d.max()))
            if (d>40).sum()>d.size*0.002: Image.open('o.png').save(f'bad_{b.code}_{kind}_o.png');Image.open('s.png').save(f'bad_{b.code}_{kind}_s.png')
    br.close()
