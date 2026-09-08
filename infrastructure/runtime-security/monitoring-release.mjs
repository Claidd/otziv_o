import { pathToFileURL } from 'node:url';

export function validateReleaseImages(environment) {
  const images = {};
  for (const component of ['PROMETHEUS', 'LOKI', 'ALLOY', 'TEMPO', 'GRAFANA']) {
    const value = environment[`OTZIV_${component}_SECURITY_IMAGE`];
    if (typeof value !== 'string' || value !== value.trim() || !/^[a-z0-9][a-z0-9.:-]*(?:\/[a-z0-9]+(?:[._-][a-z0-9]+)*)+@sha256:[a-f0-9]{64}$/.test(value)) {
      throw new Error(`${component.toLowerCase()}_published_immutable_image_required`);
    }
    images[component.toLowerCase()] = value;
  }
  return images;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { console.log(JSON.stringify({ immutableReferences: 'PASS', images: validateReleaseImages(process.env), artifactProvenance: 'REQUIRES_RELEASE_EVIDENCE' })); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
