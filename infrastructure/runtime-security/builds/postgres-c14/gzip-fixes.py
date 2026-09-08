#!/usr/bin/env python3
"""Apply the two published GNU fixes; test the exact decoder initializer body.

The control seeds valid in-bounds arrays and invokes no decompression payload.
It checks the state reset in isolation, then the normal upstream binary suite
runs separately. The original release must fail the same bounded assertion.
"""
import hashlib
import json
import pathlib
import re
import subprocess

source=pathlib.Path('/sources/gzip-1.14/unlzh.c')
original=source.read_text()
patches=[('gzip-63dbf6b.patch','6c9448ef05935ef874a8570b32d9d8c6d24e60a72b1c3b085908dce58e942e35'),
         ('gzip-e7378c2.patch','de7bb5c805e24517d9e2d4e99f0e51ed75abce004db96124e91ec0f9830d5bfc')]
for name,expected in patches:
    patch=pathlib.Path('/build',name).read_bytes()
    assert hashlib.sha256(patch).hexdigest()==expected
    # Apply only the unchanged upstream implementation hunk. NEWS/THANKS differ
    # between release/tag dates and are not executable changes.
    start=patch.index(b'diff --git a/unlzh.c b/unlzh.c\n')
    part=patch[start:];part=part.split(b'\n-- \n',1)[0]+b'\n'
    subprocess.run(['patch','--batch','--fuzz=0','-p1'],input=part,check=True)

def control(text,name):
    function=re.search(r'static void\s+huf_decode_start\s*\([^)]*\)\s*\{.*?\n\}',text,re.S).group()
    wrapper='''#include <stdint.h>
#include <string.h>
#define NC 510
#define memzero(p,n) memset(p,0,n)
static uint16_t left[2*NC-1],right[2*NC-1];
static unsigned blocksize,initialized;
static void init_getbits(void){ initialized++; }
'''+function+'''
int main(void){
  for(unsigned i=0;i<2*NC-1;i++){left[i]=1;right[i]=2;}
  blocksize=3;huf_decode_start();
  for(unsigned i=0;i<2*NC-1;i++) if(left[i]||right[i]) return 42;
  return blocksize||initialized!=1;
}
'''
    test=pathlib.Path('/build/evidence',name+'.c');test.write_text(wrapper)
    executable='/build/'+name
    subprocess.run(['cc','-O2','-Wall','-Wextra','-Werror',str(test),'-o',executable],check=True)
    return subprocess.run([executable]).returncode

fixed=source.read_text()
assert control(original,'gzip-original-state-control')==42
assert control(fixed,'gzip-fixed-state-control')==0
result={'result':'PASS','originalInBoundsStateResetExit':42,'fixedInBoundsStateResetExit':0,
        'originalUnlzhSha256':hashlib.sha256(original.encode()).hexdigest(),
        'fixedUnlzhSha256':hashlib.sha256(fixed.encode()).hexdigest(),
        'patches':[{'file':name,'sha256':sha} for name,sha in patches]}
pathlib.Path('/build/evidence/gzip-patch-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
