import {readdirSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
const root='/refresh/root', info=root+'/var/lib/dpkg/info';
const expected=new Set(['zlib1g']);
const version='1:1.3.dfsg+really1.3.1-1+deb13u1', previous='1:1.3.dfsg+really1.3.1-1+b1';
const fields=text=>Object.fromEntries([...text.matchAll(/^([\w-]+): (.*)$/gm)].map(([,k,v])=>[k,v]));
let records=readFileSync('/refresh/status','utf8').trimEnd().split(/\n\n/);
const debs=readdirSync('/refresh/debs').filter(x=>x.endsWith('.deb'));
if(debs.length!==1)throw Error('zlib_package_count');
for(const file of debs){
  const deb='/refresh/debs/'+file, control=execFileSync('dpkg-deb',['-f',deb],{encoding:'utf8'}), data=fields(control);
  if(!expected.delete(data.Package)||data.Version!==version||data.Architecture!=='amd64'||data['Multi-Arch']!=='same')
    throw Error('unexpected_zlib_package');
  const before=records.filter(x=>fields(x).Package===data.Package);
  if(before.length!==1)throw Error('zlib_parent_package_missing');
  const previousFields=fields(before[0]);
  if(previousFields.Version!==previous||previousFields.Status!=='install ok installed'||previousFields.Architecture!=='amd64')
    throw Error('zlib_parent_version_changed');
  execFileSync('dpkg-deb',['-x',deb,root]);
  const directory='/refresh/control-'+data.Package;mkdirSync(directory);
  execFileSync('dpkg-deb',['-e',deb,directory]);
  const stem=info+'/'+data.Package+(data['Multi-Arch']==='same'?':amd64':'');
  for(const entry of readdirSync(directory))if(entry!=='control')writeFileSync(stem+'.'+entry,readFileSync(directory+'/'+entry));
  const members=execFileSync('tar',['-tf','-'],{input:execFileSync('dpkg-deb',['--fsys-tarfile',deb],{maxBuffer:32*1024*1024}),encoding:'utf8'});
  writeFileSync(stem+'.list',members.trimEnd().split('\n').map(x=>x.replace(/^\.\//,'/').replace(/\/$/,'')||'/.').join('\n')+'\n');
  let replacement=control.trimEnd()+'\nStatus: install ok installed';
  // dpkg conffile checksums are retained when the conffile is byte-identical.
  const conffiles=before[0].match(/^Conffiles:\n(?: .+\n?)+/m);
  if(conffiles)replacement+='\n'+conffiles[0].trimEnd();
  records=records.map(x=>fields(x).Package===data.Package?replacement:x);
}
if(expected.size)throw Error('zlib_package_set_incomplete');
writeFileSync(root+'/var/lib/dpkg/status',records.join('\n\n')+'\n');
