"""Reproduce Infinix AI's original, UNREVIEWED FORMA glTF 2.0 mannequin.

Review-only: no exercise technique, medical validity, Android renderer or UI.
Uses Python standard library; never fetches external media.
"""
import hashlib
import json
import math
import struct
from pathlib import Path

TASK = '20261123T103931_b06a115a'
BASE = 'fa6c567a1bd8c9f4487a4bbb0b86980e7996bac6'
SHA = 'e7d622a770bd77026f30dfd8baa0cf0d6880de3204baf6c158be61823c27b6f5'

def generate():
    parts = []
    def sphere(name,center,radius,mat,rings=12,slices=18):
        cx,cy,cz=center;rx,ry,rz=radius;v=[];n=[];indices=[]
        for lat in range(rings+1):
            angle=math.pi*lat/rings;st,ct=math.sin(angle),math.cos(angle)
            for lon in range(slices+1):
                p=2*math.pi*lon/slices;d=(st*math.cos(p),ct,st*math.sin(p))
                v.append((cx+rx*d[0],cy+ry*d[1],cz+rz*d[2]))
                normal=(d[0]/rx,d[1]/ry,d[2]/rz)
                mag=math.sqrt(sum(x*x for x in normal))
                n.append(tuple(x/mag for x in normal))
        for lat in range(rings):
            for lon in range(slices):
                a=lat*(slices+1)+lon;b=a+slices+1
                if lat>0:indices.extend((a,a+1,b))
                if lat<rings-1:indices.extend((a+1,b+1,b))
        parts.append((name,mat,v,n,indices))
    def limb(name,start,end,r1,r2,mat,slices=18):
        vector=tuple(b-a for a,b in zip(start,end));length=math.sqrt(sum(x*x for x in vector))
        d=tuple(x/length for x in vector)
        helper=(1,0,0) if abs(d[0])<.8 else (0,0,1)
        u=(d[1]*helper[2]-d[2]*helper[1],d[2]*helper[0]-d[0]*helper[2],d[0]*helper[1]-d[1]*helper[0])
        mag=math.sqrt(sum(x*x for x in u));u=tuple(x/mag for x in u)
        w=(d[1]*u[2]-d[2]*u[1],d[2]*u[0]-d[0]*u[2],d[0]*u[1]-d[1]*u[0])
        v=[];n=[];ix=[]
        for pos,radius in ((start,r1),(end,r2)):
            for j in range(slices):
                th=2*math.pi*j/slices
                across=tuple(math.cos(th)*u[i]+math.sin(th)*w[i] for i in range(3))
                v.append(tuple(pos[i]+radius*across[i] for i in range(3)))
                normal=tuple(across[i]+d[i]*(r1-r2)/length for i in range(3))
                mag=math.sqrt(sum(x*x for x in normal))
                n.append(tuple(x/mag for x in normal))
        for j in range(slices):
            a=j;b=(j+1)%slices;c=slices+j;dd=slices+(j+1)%slices
            ix.extend((a,b,c,b,dd,c))
        for at,sign,radius in ((start,-1,r1),(end,1,r2)):
            center_idx=len(v);v.append(at);n.append(tuple(sign*x for x in d));first=len(v)
            for j in range(slices):
                th=2*math.pi*j/slices
                v.append(tuple(at[i]+radius*(math.cos(th)*u[i]+math.sin(th)*w[i]) for i in range(3)))
                n.append(tuple(sign*x for x in d))
            for j in range(slices):
                k=(j+1)%slices
                if sign==-1:ix.extend((center_idx,first+j,first+k))
                else:ix.extend((center_idx,first+k,first+j))
        parts.append((name,mat,v,n,ix))
    sphere('Head',(0,1.69,0),(.148,.17,.148),0)
    sphere('Torso',(0,1.28,0),(.25,.345,.155),0)
    sphere('Pelvis',(0,.93,0),(.20,.17,.16),0)
    sphere('Chest focus left',(-.115,1.39,-.143),(.106,.136,.044),1)
    sphere('Chest focus right',(.115,1.39,-.143),(.106,.136,.044),1)
    for side in (-1,1):
        lab='Left' if side<0 else 'Right'
        shoulder=(side*.29,1.49,0);elbow=(side*.40,1.13,-.05);wrist=(side*.39,.81,-.12)
        limb(lab+' upper arm',shoulder,elbow,.102,.087,0)
        limb(lab+' lower arm',elbow,wrist,.085,.06,0)
        sphere(lab+' hand',(side*.39,.77,-.12),(.075,.083,.065),0)
        hip=(side*.12,.83,0);knee=(side*.15,.47,.045);ankle=(side*.18,.11,.035)
        limb(lab+' thigh',hip,knee,.13,.105,0)
        limb(lab+' shin',knee,ankle,.102,.065,0)
        sphere(lab+' quadriceps focus',(side*.155,.655,-.091),(.092,.165,.044),1)
        sphere(lab+' foot',(side*.18,.075,-.087),(.09,.06,.19),0)
    limb('Round training pedestal',(0,-.06,0),(0,0,0),.61,.61,2)
    blob=bytearray();views=[];accessors=[];meshes=[];nodes=[]
    def attr(rows,typ,component,target,bound=False):
        while len(blob)%4:blob.append(0)
        flat=[v for row in rows for v in row]
        raw=struct.pack('<'+str(len(flat))+('f' if component==5126 else 'H'),*flat)
        pos=len(blob);blob.extend(raw)
        views.append({'buffer':0,'byteOffset':pos,'byteLength':len(raw),'target':target})
        rec={'bufferView':len(views)-1,'componentType':component,'count':len(rows),'type':typ}
        if bound:
            rec['min']=[min(x[k] for x in rows) for k in range(3)]
            rec['max']=[max(x[k] for x in rows) for k in range(3)]
        accessors.append(rec);return len(accessors)-1
    for name,mat,verts,normals,inds in parts:
        p=attr(verts,'VEC3',5126,34962,True)
        n=attr(normals,'VEC3',5126,34962)
        ix=attr([(i,) for i in inds],'SCALAR',5123,34963)
        meshes.append({'name':name,'primitives':[{'attributes':{'POSITION':p,'NORMAL':n},'indices':ix,'material':mat,'mode':4}]})
        nodes.append({'mesh':len(meshes)-1,'name':name})
    materials=[
      {'name':'Graphite · body','pbrMetallicRoughness':{'baseColorFactor':[0.17,0.25,0.32,1],'metallicFactor':0.38,'roughnessFactor':0.47},'doubleSided':True},
      {'name':'Copper · muscle emphasis','pbrMetallicRoughness':{'baseColorFactor':[0.92,0.36,0.19,1],'metallicFactor':0.18,'roughnessFactor':0.44},'doubleSided':True},
      {'name':'Obsidian · stage','pbrMetallicRoughness':{'baseColorFactor':[0.055,0.08,0.13,1],'metallicFactor':0.58,'roughnessFactor':0.35},'doubleSided':True}]
    doc={'asset':{'version':'2.0','generator':'Infinix AI original offline procedural FORMA concept (not clinical anatomy)'},
      'scene':0,'scenes':[{'name':'FORMA 3D schematic training concept','nodes':list(range(len(nodes)))}],
      'meshes':meshes,'nodes':nodes,'materials':materials,'buffers':[{'byteLength':len(blob)}],
      'bufferViews':views,'accessors':accessors,
      'extras':{'schema':1,'task_id':TASK,'base_sha':BASE,'technique_verified':False,'medical_accuracy_verified':False,'review_status':'CONCEPT_ONLY','copyright':'Original procedural geometry; no third-party meshes'}}
    encoded=json.dumps(doc,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode('utf-8')
    encoded+=b' '*((-len(encoded))%4)
    while len(blob)%4:blob.append(0)
    payload=struct.pack('<I4s',len(encoded),b'JSON')+encoded+struct.pack('<I4s',len(blob),b'BIN\0')+blob
    result=struct.pack('<4sII',b'glTF',2,12+len(payload))+payload
    if len(result)!=125288 or hashlib.sha256(result).hexdigest()!=SHA:
        raise ValueError('Result differs from exact original Infinix GLB')
    return result

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    raw=generate()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    if args.output.exists() and args.output.read_bytes()!=raw:
        raise SystemExit('Refusing to replace a different GLB')
    args.output.write_bytes(raw)
    print('REPRO_PASS '+SHA)
