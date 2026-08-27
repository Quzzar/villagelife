import gzip, struct
def read(f):
    d = gzip.open(f,'rb').read()
    p = [0]
    def u(fmt, n):
        v = struct.unpack_from(fmt, d, p[0]); p[0]+=n; return v[0]
    def name():
        n = u('>H',2); s = d[p[0]:p[0]+n].decode('utf8','replace'); p[0]+=n; return s
    def val(t):
        if t==1: return u('>b',1)
        if t==2: return u('>h',2)
        if t==3: return u('>i',4)
        if t==4: return u('>q',8)
        if t==5: return u('>f',4)
        if t==6: return u('>d',8)
        if t==7:
            n=u('>i',4); b=d[p[0]:p[0]+n]; p[0]+=n; return b
        if t==8: return name()
        if t==9:
            it=u('>b',1); n=u('>i',4); return [val(it) for _ in range(n)]
        if t==10:
            o={}
            while True:
                tt=u('>b',1)
                if tt==0: return o
                k=name(); o[k]=val(tt)
        if t==11:
            n=u('>i',4); return [u('>i',4) for _ in range(n)]
        if t==12:
            n=u('>i',4); return [u('>q',8) for _ in range(n)]
        raise ValueError("tag %d"%t)
    t=u('>b',1); name(); return val(t)
