Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$source=(Resolve-Path 'infrastructure/scripts/local/restore-prod-db-local.ps1').Path
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Parse failed'}
foreach($name in @('Assert-LocalRestoreContract','Import-LocalMySqlGzipStream','Initialize-EmptyLocalMySqlVolume')){
 $node=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
 . ([scriptblock]::Create($node.Extent.Text))
}
$script:passed=0
$root=(Get-Location).Path
$compose=Join-Path $root 'compose.prod-local.yaml'
$image='ghcr.io/claidd/otziv-security@sha256:3a3caaab4e71b3bfdec9da21c17c00ed10ca237151919aeddac8e5ce4b8b7baa'
$volume='otziv-prod-local_mysql_973_data'
$command=@('mysqld','--user=999','--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci','--default-time-zone=+08:00','--restrict-fk-on-non-standard-key=OFF','--gtid-mode=OFF','--enforce-gtid-consistency=OFF','--log-bin=mysql-bin','--binlog-format=ROW','--event-scheduler=OFF')
function Reset-Fixture {
 $script:config=@{name='otziv-prod-local';services=@{mysql=@{image=$image;user='999:999';command=$command.Clone();volumes=@(@{type='volume';source='mysql_data';target='/var/lib/mysql';volume=@{nocopy=$true}},@{type='bind';source=(Join-Path $root 'data/mysql_backup');target='/backup'},@{type='bind';source=(Join-Path $root 'data/bots');target='/var/lib/mysql-files'});tmpfs=@('/var/run/mysqld:rw,noexec,nosuid,size=16m,uid=999,gid=999,mode=0755')}};volumes=@{mysql_data=@{name=$volume}}}
 $script:imageMetadata=@{Id=$image.Split('@')[1];Os='linux';Architecture='amd64';Config=@{Entrypoint=@('/entrypoint.sh')};RepoDigests=@($image)}
 $script:volumeMetadata=@{Name=$volume;Driver='local';Options=@{};Labels=@{'com.docker.compose.project'='otziv-prod-local';'com.docker.compose.volume'='mysql_data';'com.otziv.mysql.local-engine'='9.7.3'}}
 $script:containerMetadata=@{Id=('a'*64);Config=@{Image=$image;Labels=@{'com.docker.compose.project'='otziv-prod-local';'com.docker.compose.service'='mysql';'com.docker.compose.config-hash'=('b'*64);'com.docker.compose.oneoff'='False';'com.docker.compose.project.working_dir'=$root;'com.docker.compose.project.config_files'=$compose}}}
 $script:inventoryMismatch=$false;$script:composeVersion='5.5.0';$script:versionFailed=$false;$script:volumeOnly=$false;$script:volumeQueryRead=$false;$script:existing=$false;$script:attached=$false;$script:readCalls=0;$script:mutationCalls=0
}
function docker {
 $global:LASTEXITCODE=0
 $script:readCalls++
 $flatArgs=@($args | ForEach-Object { $_ }); $key=$flatArgs -join ' '
 if($key -eq 'ps -aq --no-trunc --filter label=com.docker.compose.project=otziv-prod-local'){if($script:attached){('a'*64)};return}
 if($key -eq 'ps -aq --no-trunc --filter label=com.docker.compose.project=otziv-prod-local --filter label=com.docker.compose.config-hash'){if($script:attached -and $script:containerMetadata.Config.Labels.ContainsKey('com.docker.compose.config-hash')){('a'*64)};return}
 if($key -eq 'compose ps --all --quiet'){if($script:attached -and -not $script:inventoryMismatch -and $script:containerMetadata.Config.Labels.ContainsKey('com.docker.compose.config-hash')){('a'*64)};return}
 if($key -eq 'compose version --short'){if($script:versionFailed){$global:LASTEXITCODE=1};$script:composeVersion;return}
 if($key -eq "volume ls -q --filter name=^${script:volume}$"){if($script:existing){$script:volume};return}
 if($key -eq "ps -aq --filter volume=$script:volume"){$script:volumeQueryRead=$true;if($script:attached -or $script:volumeOnly){('a'*64)};return}
 throw "Unexpected Docker read/mutation: $key"
}
function Get-LocalRestoreDockerJson {
 param([string[]]$Arguments)
 $script:readCalls++
 if($Arguments[0] -eq 'compose'){return $script:config}
 if(($Arguments -join ' ') -eq "image inspect $image"){return @($script:imageMetadata)}
 if(($Arguments -join ' ') -eq "volume inspect $script:volume"){return @($script:volumeMetadata)}
 if(($Arguments -join ' ') -eq ('inspect '+('a'*64))){return @($script:containerMetadata)}
 throw 'Unexpected metadata read'
}
function Invoke-External {param([string]$FilePath,[string[]]$Arguments);$script:mutationCalls++;throw 'Unexpected mutation'}
function Assert-Pass([string]$name,[scriptblock]$body){Reset-Fixture;&$body;$script:passed++;Write-Output "PASS $name"}
function Assert-Reject([string]$name,[scriptblock]$change,[string]$selected=$volume,[string]$selectedCompose=$compose){Reset-Fixture;&$change;$rejected=$false;try{$null=Assert-LocalRestoreContract -ComposeArguments @('compose') -RepoRoot $root -ComposePath $selectedCompose -VolumeName $selected}catch{$rejected=$true};if(-not $rejected){throw "Expected rejection: $name"};if($script:mutationCalls){throw 'Mutation before rejection'};$script:passed++;Write-Output "PASS $name"}
Assert-Pass 'fresh_reviewed_contract' {$r=Assert-LocalRestoreContract -ComposeArguments @('compose') -RepoRoot $root -ComposePath $compose -VolumeName $volume;if($r.VolumeExists -or $r.Image -ne $image){throw 'wrong result'}}
Assert-Pass 'owned_existing_973_contract' {$script:existing=$true;$script:attached=$true;$r=Assert-LocalRestoreContract -ComposeArguments @('compose') -RepoRoot $root -ComposePath $compose -VolumeName $volume;if(-not $r.VolumeExists){throw 'wrong result'}}
Assert-Reject 'old_volume_name_before_docker' {} 'otziv-prod-local_mysql_data'
Assert-Reject 'foreign_volume_name_before_docker' {} 'production_mysql_973_data'
Assert-Reject 'different_compose_file' {} $volume (Join-Path $root 'compose.production.yaml')
Assert-Pass 'compose_json_null_entrypoint_keeps_native_entrypoint' {$script:config.services.mysql.entrypoint=$null;$null=Assert-LocalRestoreContract -ComposeArguments @('compose') -RepoRoot $root -ComposePath $compose -VolumeName $volume}
Assert-Reject 'compose_empty_entrypoint_cannot_disable_native_entrypoint' {$script:config.services.mysql.entrypoint=@()}
Assert-Reject 'foreign_auxiliary_bind' {$script:config.services.mysql.volumes[2].source='C:\production\mysql'}
Assert-Reject 'readonly_bots_prevents_native_initialization' {$script:config.services.mysql.volumes[2].read_only=$true}
Assert-Reject 'foreign_project' {$script:config.name='production'}
Assert-Reject 'old_engine_image' {$script:config.services.mysql.image='mysql:9.0.0'}
Assert-Reject 'root_user' {$script:config.services.mysql.user='0:0'}
Assert-Reject 'custom_entrypoint' {$script:config.services.mysql.entrypoint=@('sh')}
Assert-Reject 'gtid_changed' {$script:config.services.mysql.command[6]='--gtid-mode=ON'}
Assert-Reject 'datadir_copy_allowed' {$script:config.services.mysql.volumes[0].volume.nocopy=$false}
Assert-Reject 'datadir_bind' {$script:config.services.mysql.volumes[0].type='bind'}
Assert-Reject 'external_volume' {$script:config.volumes.mysql_data.external=$true}
Assert-Reject 'driver_bind_options' {$script:config.volumes.mysql_data.driver_opts=@{type='none';device='/prod';o='bind'}}
Assert-Reject 'unreviewed_mount' {$script:config.services.mysql.volumes+=@{type='bind';source='custom';target='/etc/my.cnf'}}
Assert-Reject 'wrong_tmpfs_owner' {$script:config.services.mysql.tmpfs=@('/var/run/mysqld:uid=0,gid=0')}
Assert-Reject 'wrong_immutable_image_id' {$script:imageMetadata.Id='sha256:bad'}
Assert-Reject 'wrong_image_platform' {$script:imageMetadata.Architecture='arm64'}
Assert-Reject 'same_project_other_workspace' {$script:attached=$true;$script:containerMetadata.Config.Labels['com.docker.compose.project.working_dir']='C:\other'}
Assert-Reject 'same_project_other_config' {$script:attached=$true;$script:containerMetadata.Config.Labels['com.docker.compose.project.config_files']='C:\other\compose.yaml'}
Assert-Reject 'unowned_existing_volume' {$script:existing=$true;$script:volumeMetadata.Labels['com.otziv.mysql.local-engine']='9.0.0'}
Assert-Reject 'foreign_existing_volume_driver' {$script:existing=$true;$script:volumeMetadata.Options=@{device='/prod'}}
Assert-Reject 'old_image_attached_to_versioned_volume' {$script:existing=$true;$script:attached=$true;$script:containerMetadata.Config.Image='mysql:9.0.0'}
Assert-Reject 'foreign_service_attached_to_volume' {$script:existing=$true;$script:attached=$true;$script:containerMetadata.Config.Labels['com.docker.compose.service']='other'}
Assert-Pass 'initializer_old_volume_fails_before_mutation' {$rejected=$false;try{Initialize-EmptyLocalMySqlVolume -VolumeName 'otziv-prod-local_mysql_data' -Image $image}catch{$rejected=$true};if(-not $rejected -or $script:mutationCalls){throw 'unsafe initializer'}}
Assert-Pass 'initializer_existing_volume_fails_before_mutation' {$script:existing=$true;$rejected=$false;try{Initialize-EmptyLocalMySqlVolume -VolumeName $volume -Image $image}catch{$rejected=$true};if(-not $rejected -or $script:mutationCalls){throw 'unsafe initializer'}}
$privateDir=Join-Path $root '.codex-tmp/release-readiness-20260908/compose-selector/unit-synthetic'
[IO.Directory]::CreateDirectory($privateDir)|Out-Null
$dump=Join-Path $privateDir 'synthetic.sql.gz'
$stream=[IO.File]::Create($dump);$gz=[IO.Compression.GZipStream]::new($stream,[IO.Compression.CompressionMode]::Compress);$bytes=[Text.Encoding]::UTF8.GetBytes('SELECT 1;');$gz.Write($bytes,0,$bytes.Length);$gz.Dispose();$stream.Dispose()
Assert-Pass 'changed_dump_rejected_before_provider_process' {$rejected=$false;try{Import-LocalMySqlGzipStream -ComposeArguments @('must-not-execute') -Path $dump -ExpectedSha256 ('0'*64)}catch{if($_.Exception.Message -eq 'The verified local dump changed before import.'){$rejected=$true}};if(-not $rejected){throw 'hash fence did not reject'}}
$badDump=Join-Path $privateDir 'not-a-gzip.sql.gz';[IO.File]::WriteAllText($badDump,'not a gzip');$badSha=(Get-FileHash -LiteralPath $badDump -Algorithm SHA256).Hash.ToLowerInvariant()
Assert-Pass 'invalid_gzip_rejected_before_provider_process' {$rejected=$false;try{Import-LocalMySqlGzipStream -ComposeArguments @('must-not-execute') -Path $badDump -ExpectedSha256 $badSha}catch{if($_.Exception.ToString() -match 'compression|compressed|archive'){$rejected=$true}};if(-not $rejected){throw 'gzip fence did not reject'}}
Write-Output "TOTAL PASS $script:passed"




function Set-ProjectOnlyExtra {
 $script:attached=$true
 $script:containerMetadata.Config.Labels.Remove('com.docker.compose.config-hash')
 $script:containerMetadata.Config.Labels.Remove('com.docker.compose.oneoff')
 $script:containerMetadata.Config.Labels.Remove('com.docker.compose.project.working_dir')
 $script:containerMetadata.Config.Labels.Remove('com.docker.compose.project.config_files')
}
function Assert-SelectorReject([string]$name,[scriptblock]$change,[string]$reason){
 Reset-Fixture;&$change;$rejected=$false
 try{$null=Assert-LocalRestoreContract -ComposeArguments @('compose') -RepoRoot $root -ComposePath $compose -VolumeName $volume}
 catch{if($_.Exception.Message -match $reason){$rejected=$true}else{throw}}
 if(-not $rejected){throw "Expected selector rejection: $name"}
 if($script:mutationCalls){throw 'Mutation before selector rejection'}
 $script:passed++;Write-Output "PASS $name"
}
Assert-Pass 'both_labels_absent_exact_tested_version_allowed' {Set-ProjectOnlyExtra;$null=Assert-LocalRestoreContract -ComposeArguments @('compose') -RepoRoot $root -ComposePath $compose -VolumeName $volume}
Assert-Pass 'no_extras_does_not_pin_otherwise_observed_compose_version' {$script:attached=$true;$script:composeVersion='6.0.0';$null=Assert-LocalRestoreContract -ComposeArguments @('compose') -RepoRoot $root -ComposePath $compose -VolumeName $volume}
foreach($value in @('False','True','')){
 Assert-SelectorReject "missing_hash_with_oneoff_${value}_rejects" {Set-ProjectOnlyExtra;$script:containerMetadata.Config.Labels['com.docker.compose.oneoff']=$value} 'selected by Compose start'
}
Assert-SelectorReject 'foreign_managed_hash_still_rejected' {$script:attached=$true;$script:containerMetadata.Config.Labels['com.docker.compose.project.working_dir']='C:\foreign'} 'another workspace'
Assert-SelectorReject 'empty_hash_is_present_not_exempt' {Set-ProjectOnlyExtra;$script:containerMetadata.Config.Labels['com.docker.compose.config-hash']=''} 'another workspace'
Assert-SelectorReject 'unknown_version_with_unmanaged_extras_rejected' {Set-ProjectOnlyExtra;$script:composeVersion='5.6.0'} 'verified Docker Compose 5.5.0'
Assert-SelectorReject 'failed_version_with_unmanaged_extras_rejected' {Set-ProjectOnlyExtra;$script:versionFailed=$true} 'verified Docker Compose 5.5.0'
Assert-SelectorReject 'compose_docker_inventory_disagreement_rejected' {$script:attached=$true;$script:inventoryMismatch=$true} 'disagree'
Assert-SelectorReject 'inspected_id_change_rejected' {$script:attached=$true;$script:containerMetadata.Id=('c'*64)} 'identity changed'
Assert-SelectorReject 'inspected_project_change_rejected' {$script:attached=$true;$script:containerMetadata.Config.Labels['com.docker.compose.project']='foreign'} 'identity changed'
Assert-SelectorReject 'project_only_extra_volume_user_not_ignored' {Set-ProjectOnlyExtra;$script:existing=$true;$script:containerMetadata.Config.Image='mysql:9.0.0'} 'foreign or legacy MySQL container'
if(-not $script:volumeQueryRead){throw 'All-volume-users guard was not queried'}
Assert-SelectorReject 'foreign_volume_user_outside_project_still_rejected' {$script:existing=$true;$script:volumeOnly=$true;$script:containerMetadata.Config.Labels['com.docker.compose.project']='foreign'} 'foreign or legacy MySQL container'
if(-not $script:volumeQueryRead){throw 'All-volume-users guard was not queried'}
Write-Output "FINAL TOTAL PASS $script:passed"
