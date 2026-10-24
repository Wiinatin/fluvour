import re
def strip_comments(s): return re.sub(r'/\*.*?\*/','',s,flags=re.S)
def split_blocks(s):
    """yield (prelude, body) top-level blocks"""
    i=0;n=len(s);out=[]
    while i<n:
        j=s.find('{',i)
        if j<0:break
        pre=s[i:j].strip(); d=1;k=j+1
        while k<n and d:
            if s[k]=='{':d+=1
            elif s[k]=='}':d-=1
            k+=1
        out.append((pre,s[j+1:k-1])); i=k
    return out
def decls(body):
    d={}
    for part in body.split(';'):
        if ':' in part:
            k,v=part.split(':',1); d[k.strip()]=v.strip()
    return d
def parse_css(text):
    text=strip_comments(text)
    rules=[];kf={}
    for pre,body in split_blocks(text):
        if pre.startswith('@keyframes'):
            name=pre.split()[1]; frames=[]
            for p2,b2 in split_blocks(body):
                for pc in p2.split(','):
                    pc=pc.strip()
                    pct={'from':0.0,'to':100.0}.get(pc) if pc in('from','to') else float(pc.rstrip('%'))
                    frames.append((pct,decls(b2)))
            kf[name]=sorted(frames,key=lambda x:x[0])
        elif pre.startswith('@'): continue
        else:
            rules.append(([x.strip() for x in pre.split(',')],decls(body)))
    return rules,kf
if __name__=='__main__':
    import glob,collections
    props=collections.Counter(); sels=collections.Counter()
    for f in glob.glob('/tmp/claude-0/-home-claude/1aae2146-14d3-5ef0-978e-97b12c52a692/scratchpad/fl/project/*.dc.html'):
        s=open(f).read(); css=s[s.find('<style>')+7:s.find('</style>')]
        rules,kf=parse_css(css)
        for sl,d in rules:
            if any(x.startswith('.p-') or x.startswith('body') or x in('svg',) or '*' in x for x in sl):continue
            for x in sl:
                if not re.fullmatch(r'\.[\w-]+',x): sels[x]+=1
            for k in d: props[k]+=1
        for n,fr in kf.items():
            for p,d in fr:
                for k in d: props['kf:'+k]+=1
    print(props); print(sels)
