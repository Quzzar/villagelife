import gzip, struct
def _s(v): b=v.encode('utf8'); return struct.pack('>H',len(b))+b
def _tag(v):
    if isinstance(v,bool): return 1, struct.pack('>b',1 if v else 0)
    if isinstance(v,int):  return 3, struct.pack('>i',v)
    if isinstance(v,float):return 6, struct.pack('>d',v)
    if isinstance(v,str):  return 8, _s(v)
    if isinstance(v,dict):
        out=b''
        for k,val in v.items():
            t,enc=_tag(val); out+=struct.pack('>b',t)+_s(k)+enc
        return 10, out+b'\x00'
    if isinstance(v,list):
        if not v: return 9, struct.pack('>b',0)+struct.pack('>i',0)
        t,_=_tag(v[0]); body=b''.join(_tag(x)[1] for x in v)
        return 9, struct.pack('>b',t)+struct.pack('>i',len(v))+body
    raise ValueError(type(v))
def write(path, root):
    t,enc=_tag(root)
    gzip.open(path,'wb').write(struct.pack('>b',t)+_s('')+enc)
