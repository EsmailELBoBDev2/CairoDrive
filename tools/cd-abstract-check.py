#!/usr/bin/env python3
"""Does every class implement the abstract methods of the class it extends?

The type-checker resolves NAMES; it cannot see that a superclass declared a method abstract and
the subclass never implemented it. That is a compile error the local tooling was blind to, and it
shipped once - GoogleTrafficLayer had no onDraw.
"""
import os, re, sys

def strip(src):
    out=[]; i=0; n=len(src); st=None
    while i<n:
        c=src[i]; nx=src[i+1] if i+1<n else ''
        if st is None:
            if c=='/' and nx=='/': st='line'; out.append('  '); i+=2; continue
            if c=='/' and nx=='*': st='block'; out.append('  '); i+=2; continue
            if c=='"': st='str'; out.append('"'); i+=1; continue
            out.append(c); i+=1; continue
        if st=='line':
            if c=='\n': st=None; out.append('\n')
            else: out.append(' ')
            i+=1; continue
        if st=='block':
            if c=='*' and nx=='/': st=None; out.append('  '); i+=2; continue
            out.append('\n' if c=='\n' else ' '); i+=1; continue
        if c=='\\': out.append('  '); i+=2; continue
        if c=='"': st=None; out.append('"'); i+=1; continue
        out.append('\n' if c=='\n' else ' '); i+=1
    return ''.join(out)

index={}
for root,_,files in os.walk('OsmAnd/src'):
    for f in files:
        if f.endswith('.java'): index[f[:-5]]=os.path.join(root,f)
for root,_,files in os.walk('OsmAnd-java/src/main/java'):
    for f in files:
        if f.endswith('.java'): index.setdefault(f[:-5], os.path.join(root,f))

def abstract_methods(cls):
    """Abstract methods of the TOP-LEVEL class only.

    Depth matters: OsmandMapLayer contains a nested `public abstract class MapLayerData<T>` whose
    abstract calculateResult() has nothing to do with subclasses of the outer class. Scanning the
    whole file reports it as missing on every layer, which is noise that makes the tool ignorable.
    Members of the top-level class body sit at brace depth 1.
    """
    p=index.get(cls)
    if not p: return None
    s=strip(open(p,encoding='utf-8',errors='replace').read())
    body=s[s.index('{'):] if '{' in s else s
    out=set(); depth=0; i=0
    while i<len(body):
        c=body[i]
        if c=='{': depth+=1
        elif c=='}': depth-=1
        elif depth==1:
            m=re.match(r'\babstract\s+[\w<>\[\],.?\s]+?\s(\w+)\s*\(', body[i:])
            if m and (i==0 or not body[i-1].isalnum()):
                out.add(m.group(1)); i+=m.end()-1
        i+=1
    return out

def declared(path):
    s=strip(open(path,encoding='utf-8',errors='replace').read())
    return set(re.findall(r'\b(?:public|protected|private)\s+[\w<>\[\],.?\s]+?\s(\w+)\s*\(', s))

bad=0
for f in sys.argv[1:]:
    if not f.endswith('.java') or not os.path.exists(f): continue
    s=strip(open(f,encoding='utf-8',errors='replace').read())
    m=re.search(r'\bclass\s+(\w+)[^{]*?\bextends\s+(\w+)', s)
    if not m: continue
    sub,sup=m.group(1),m.group(2)
    if re.search(r'\babstract\s+class\s+'+sub+r'\b', s): continue
    req=abstract_methods(sup)
    if req is None:
        print("  %-28s extends %s (not in index - skipped)" % (sub,sup)); continue
    have=declared(f)
    missing=sorted(req-have)
    if missing:
        bad+=1
        print("  %-28s extends %-24s MISSING: %s" % (sub,sup,', '.join(missing)))
    else:
        print("  %-28s extends %-24s ok (%d abstract satisfied)" % (sub,sup,len(req)))
print("\n%d class(es) missing an abstract implementation" % bad)
sys.exit(1 if bad else 0)
