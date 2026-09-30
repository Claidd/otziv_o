import {readdirSync,readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
const root='/refresh/root', info=root+'/var/lib/dpkg/info';
const expected=new Set(['libssl3t64','openssl','openssl-provider-legacy']);
const fields=text=>Object.fromEntries([...text.matchAll(/^([\w-]+): (.*)$/gm)].map(([,k,v])=>[k,v]));
let records=readFileSync('/refresh/status','utf8').trimEnd().split(/\n\n/);
for(const file of readdirSync('/refresh/debs').filter(x=>x.endsWith('.deb'))){
  const deb='/refresh/debs/'+file, control=execFileSync('dpkg-deb',['-f',deb],{encoding:'utf8'}), data=fields(control);
  if(!expected.delete(data.Package)||data.Version!=='3.5.7-1~deb13u3'||data.Architecture!=='amd64')throw Error('unexpected_ssl_package');
  const before=records.filter(x=>fields(x).Package===data.Package);
  if(before.length!==1)throw Error('ssl_parent_package_missing');
  execFileSync('dpkg-deb',['-x',deb,root]);
  const directory='/refresh/control-'+data.Package;mkdirSync(directory);
  execFileSync('dpkg-deb',['-e',deb,directory]);
  const stem=info+'/'+data.Package+(data['Multi-Arch']==='same'?':amd64':'');
  for(const entry of readdirSync(directory))if(entry!=='control')writeFileSync(stem+'.'+entry,readFileSync(directory+'/'+entry));
  const members=execFileSync('tar',['-tf','-'],{input:execFileSync('dpkg-deb',['--fsys-tarfile',deb],{maxBuffer:32*1024*1024}),encoding:'utf8'});
  writeFileSync(stem+'.list',members.trimEnd().split('\n').map(x=>x.replace(/^\.\//,'/').replace(/\/$/,'')||'/.').join('\n')+'\n');
  const previous=fields(before[0]);
  let replacement=control.trimEnd()+'\nStatus: install ok installed';
  // dpkg conffile checksums are retained when the conffile is byte-identical.
  const conffiles=before[0].match(/^Conffiles:\n(?: .+\n?)+/m);
  if(conffiles)replacement+='\n'+conffiles[0].trimEnd();
  records=records.map(x=>fields(x).Package===data.Package?replacement:x);
}
if(expected.size)throw Error('ssl_package_set_incomplete');
writeFileSync(root+'/var/lib/dpkg/status',records.join('\n\n')+'\n');
