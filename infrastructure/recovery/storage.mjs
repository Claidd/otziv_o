import { run } from './process.mjs';

export function httpsEndpoint(value) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('storage_endpoint_invalid');
  }
  return url.origin;
}

export class BackupStorage {
  constructor(config, environment = process.env, execute = run) {
    this.config = config;
    this.execute = execute;
    httpsEndpoint(config.endpoint);
    for (const name of ['RECOVERY_S3_ACCESS_KEY', 'RECOVERY_S3_SECRET_KEY']) {
      if (!environment[name]) throw new Error('dedicated_storage_credentials_missing');
    }
    if (environment.RECOVERY_S3_ACCESS_KEY === environment.S3_ACCESS_KEY ||
        environment.RECOVERY_S3_SECRET_KEY === environment.S3_SECRET_KEY) {
      throw new Error('primary_storage_credentials_reused');
    }
    this.env = { ...environment, AWS_ACCESS_KEY_ID: environment.RECOVERY_S3_ACCESS_KEY,
      AWS_SECRET_ACCESS_KEY: environment.RECOVERY_S3_SECRET_KEY,
      AWS_EC2_METADATA_DISABLED: 'true', AWS_PAGER: '', AWS_MAX_ATTEMPTS: '3' };
    for (const name of ['AWS_PROFILE', 'AWS_DEFAULT_PROFILE', 'AWS_SESSION_TOKEN', 'AWS_ENDPOINT_URL',
      'AWS_ENDPOINT_URL_S3', 'AWS_ROLE_ARN', 'AWS_WEB_IDENTITY_TOKEN_FILE']) delete this.env[name];
    if (environment.RECOVERY_S3_SESSION_TOKEN) this.env.AWS_SESSION_TOKEN = environment.RECOVERY_S3_SESSION_TOKEN;
  }
  async call(operation, args) {
    const raw = await this.execute(this.config.awsExecutable || 'aws', ['--endpoint-url', this.config.endpoint,
      '--region', this.config.region, '--cli-connect-timeout', '10', '--cli-read-timeout', '120',
      '--output', 'json', 's3api', operation, '--bucket', this.config.bucket, ...args],
    { env: this.env, timeoutMs: this.config.operationTimeoutMs || 3_600_000 });
    try { return JSON.parse(raw); } catch { throw new Error('storage_response_invalid'); }
  }
  async upload(path, objectKey, sha256) {
    const args = ['--key', objectKey, '--body', path, '--metadata', `sha256=${sha256}`];
    if (this.config.requireServerSideEncryption) args.push('--server-side-encryption', 'AES256');
    const retention = this.config.retention;
    if (retention?.enabled) {
      args.push('--object-lock-mode', retention.mode, '--object-lock-retain-until-date',
        new Date(Date.now() + retention.days * 86_400_000).toISOString());
    }
    const result = await this.call('put-object', args);
    if (typeof result.VersionId !== 'string' || !result.VersionId || result.VersionId === 'null') throw new Error('storage_versioning_required');
    return result.VersionId;
  }
  async verifyHead(objectKey, versionId, sha256, bytes) {
    const head = await this.call('head-object', ['--key', objectKey, '--version-id', versionId]);
    if (head.ContentLength !== bytes || head.Metadata?.sha256 !== sha256 || head.VersionId !== versionId ||
        (this.config.requireServerSideEncryption && head.ServerSideEncryption !== 'AES256')) {
      throw new Error('storage_head_verification_failed');
    }
    if (this.config.retention?.enabled) {
      const { Retention } = await this.call('get-object-retention', ['--key', objectKey, '--version-id', versionId]);
      const until = Date.parse(Retention?.RetainUntilDate);
      if (Retention?.Mode !== this.config.retention.mode ||
          !Number.isFinite(until) || until < Date.now() + (this.config.retention.days - 0.01) * 86_400_000) {
        throw new Error('storage_retention_verification_failed');
      }
    }
  }
  async download(objectKey, versionId, destination) {
    const result = await this.call('get-object', ['--key', objectKey, '--version-id', versionId, destination]);
    if (result.VersionId !== versionId) throw new Error('storage_download_version_mismatch');
  }
}
