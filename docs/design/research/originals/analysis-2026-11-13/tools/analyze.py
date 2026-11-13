import io, json, collections
from dexp import Dex

dexs=[Dex("dex/classes.dex"), Dex("dex/classes2.dex")]
cls_map=collections.defaultdict(set)
midx_all=[]
for dx in dexs:
    mids=dx.method_ids()
    midx_all.append(mids)
    for cname, ms in dx.classes().items():
        cur=0
        for mdiff, flags, code in ms:
            cur += mdiff
            if cur < len(mids):
                cls_map[cname].add(mids[cur][1])

# 1) 自有类 -> 方法名
own={k:sorted(v) for k,v in cls_map.items() if k.startswith('Lchromahub/rhythm/app/')}
print("own classes:", len(own))

# 2) 目标 API 包的方法引用
target_prefix = {
 'anim_core':'Landroidx/compose/animation/core/',
 'anim':'Landroidx/compose/animation/',
 'm3':'Landroidx/compose/material3/',
 'dynanim':'Landroidx/dynamicanimation/',
 'graphics':'Landroidx/compose/ui/graphics/',
}
usage=collections.defaultdict(collections.Counter)
for mids in midx_all:
    for c,m in mids:
        for tag,p in target_prefix.items():
            if c.startswith(p):
                usage[tag][(c,m)]+=1
for tag in target_prefix:
    print(f"{tag}: distinct={len(usage[tag])} refs={sum(usage[tag].values())}")

json.dump({k:sorted(v) for k,v in own.items()}, io.open('out-own-classes.json','w',encoding='utf-8'), ensure_ascii=False, indent=1)
json.dump({tag:{f"{c}#{m}":n for (c,m),n in cnt.items()} for tag,cnt in usage.items()},
          io.open('out-api-usage.json','w',encoding='utf-8'), ensure_ascii=False, indent=1)
print("written")
