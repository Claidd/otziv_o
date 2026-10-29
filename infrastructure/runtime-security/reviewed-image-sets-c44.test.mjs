import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {validateReviewedImageSet} from './reviewed-image-sets.mjs';

test('C44 republishes the exact inspected C43 executable with a fresh security gate', async () => {
  const root=new URL('../../',import.meta.url);
  const baseline=await readFile(new URL('infrastructure/runtime-security/reviewed-images.json',root));
  const bytes=await readFile(new URL('infrastructure/runtime-security/reviewed-images-c44-grafana.json',root));
  const [image]=validateReviewedImageSet('c44-grafana',bytes,baseline).images;
  const recipe=await readFile(new URL(image.dockerfile,root),'utf8');
  assert.equal(createHash('sha256').update(recipe).digest('hex'),image.dockerfileSha256);
  const source=JSON.parse(await readFile(new URL('infrastructure/runtime-security/proofs/c43-grafana-discovery/publication/publication.json',root)));
  assert.equal(image.candidateBaseRef,source.reference);
  assert.ok(recipe.includes('FROM '+source.reference+'\n'));
  assert.doesNotMatch(recipe,/^(?:RUN|COPY|ADD|USER|ENTRYPOINT|CMD) /m);
  const firstManifest=await readFile(new URL('infrastructure/runtime-security/reviewed-images-c43-grafana.json',root));
  assert.equal(createHash('sha256').update(firstManifest).digest('hex'),source.manifestSha256);
  const [build]=validateReviewedImageSet('c43-grafana',firstManifest,baseline).images;
  assert.equal(build.dockerfileSha256,source.dockerfileSha256);
});
