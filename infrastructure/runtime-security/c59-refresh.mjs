import assert from 'node:assert/strict';
import {dirname} from 'node:path';
import {validateC50ActivationScan} from './c50-refresh.mjs';

// This candidate retains the earlier musl fix and all raw-scan requirements.
// Its server and every installed ABI-coupled module must be the exact signed fix.
export async function validateC59ActivationScan(publication,publicationPath,read){
  assert.equal(publication.component,'nginx');
  assert.equal(publication.manifestSet,'c59-nginx');
  await validateC50ActivationScan(publication,publicationPath,read);
  const raw=JSON.parse(await read(dirname(publicationPath).replaceAll('\\','/')+'/vulnerabilities.json'));
  const packages=raw.Results.filter(row=>row.Class==='os-pkgs').flatMap(row=>row.Packages||[]);
  for(const [name,version] of Object.entries({nginx:'1.30.7-r1','nginx-module-acme':'1.30.7.0.4.2-r1',
    'nginx-module-geoip':'1.30.7-r1','nginx-module-njs':'1.30.7.1.0.2-r1','nginx-module-xslt':'1.30.7-r1'})){
    const found=packages.filter(pkg=>pkg.Name===name);
    assert.equal(found.length,1,'c59_package_coverage:'+name);
    assert.equal([found[0].Version,found[0].Release].filter(Boolean).join('-'),version,'c59_fixed_version:'+name);
  }
}
