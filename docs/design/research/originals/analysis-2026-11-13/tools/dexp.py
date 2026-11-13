import struct, io, os, json, collections

def uleb(d, off):
    r=0; s=0
    while True:
        b=d[off]; off+=1
        r |= (b & 0x7f) << s
        if not (b & 0x80): break
        s += 7
    return r, off

class Dex:
    def __init__(self, path):
        self.d = open(path,'rb').read()
        d=self.d
        (self.str_n,self.str_off)=struct.unpack_from('<II',d,0x38)
        (self.type_n,self.type_off)=struct.unpack_from('<II',d,0x40)
        (self.meth_n,self.meth_off)=struct.unpack_from('<II',d,0x58)
        (self.cls_n,self.cls_off)=struct.unpack_from('<II',d,0x60)
        self.strings=[self._str(i) for i in range(self.str_n)]
        self.types=[self.strings[struct.unpack_from('<I',d,self.type_off+i*4)[0]] for i in range(self.type_n)]
    def _str(self,i):
        off=struct.unpack_from('<I',self.d,self.str_off+i*4)[0]
        j=off
        while self.d[j]&0x80: j+=1
        j+=1
        end=self.d.index(b'\x00',j)
        return self.d[j:end].decode('utf-8','replace')
    def method_ids(self):
        out=[]
        for i in range(self.meth_n):
            c,p,n=struct.unpack_from('<HHI',self.d,self.meth_off+i*8)
            out.append((self.types[c], self.strings[n]))
        return out
    def classes(self):
        out={}
        for i in range(self.cls_n):
            base=self.cls_off+i*32
            cls_idx,acc,sup,ifs,src,ann,cdata,_=struct.unpack_from('<IIIIIIII',self.d,base)
            name=self.types[cls_idx]
            methods=[]
            if cdata:
                off=cdata
                sf,off=uleb(self.d,off); inf,off=uleb(self.d,off)
                dm,off=uleb(self.d,off); vm,off=uleb(self.d,off)
                for _ in range(sf+inf):
                    _,off=uleb(self.d,off); _,off=uleb(self.d,off)
                for _ in range(dm+vm):
                    mdiff,off=uleb(self.d,off); flags,off=uleb(self.d,off); code,off=uleb(self.d,off)
                    methods.append((mdiff,flags,code))
            out[name]=methods
        return out

if __name__=="__main__":
    for p in ("dex/classes.dex","dex/classes2.dex"):
        dx=Dex(p)
        print(p, "strings",len(dx.strings),"types",len(dx.types),"methods",dx.meth_n,"classes",dx.cls_n)
