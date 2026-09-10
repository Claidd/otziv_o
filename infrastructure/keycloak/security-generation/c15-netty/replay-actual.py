import pathlib,sys,re
if len(sys.argv)!=3:raise SystemExit('usage: replay-actual.py immutable-or-local-candidate protected-private-directory')
candidate=sys.argv[1]
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./:@-]+',candidate):raise SystemExit('invalid_candidate_reference')
PRIVATE=pathlib.Path(sys.argv[2]).resolve();PRIVATE_ROOT=PRIVATE.parent
ROOT=pathlib.Path(__file__).resolve().parents[4]
if PRIVATE==ROOT or ROOT in PRIVATE.parents:raise SystemExit('private_artifacts_must_be_outside_repository')
exec(compile((ROOT / 'infrastructure/keycloak/security-generation/c14-migration-fix/private-runtime.py').read_text(),str((ROOT / 'infrastructure/keycloak/security-generation/c14-migration-fix/private-runtime.py')),'exec'))
OWNER='otziv-pg-vps-'+uuid.uuid4().hex[:12];LABEL='com.otziv.pg-vps-rehearsal.owner'
OLD_PG='postgres@sha256:a426e44bac0b759c95894d68e1a0ac03ecc20b619f498a91aae373bf06d8508d'
OLD_KC='quay.io/keycloak/keycloak@sha256:4883630ef9db14031cde3e60700c9a9a8eaf1b5c24db1589d6a2d43de38ba2a9'
containers=[];volumes=[];network=None;stage='setup'
acl(PRIVATE_ROOT);acl(PRIVATE)
for required in ['before.json','database.dump','postgres.env','keycloak.env','restored-tables.json']:acl(PRIVATE/required)
NEW_KC=candidate
NEW_PG='ghcr.io/claidd/otziv-security@sha256:07834abbfd80ed7183afa9096db99afc4d93b99fd51aae726e550d07ade65dbf'
PROOF=ROOT/'infrastructure/keycloak/security-generation/c15-netty/proofs'
PROOF.mkdir(exist_ok=True)
REPORT={'schema':'otziv-keycloak-actual-migration-fix-v1','result':'FAIL','checks':[],'productionAccess':False,'privateRowsPublished':False,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'candidate':candidate}
before=json.loads((PRIVATE/'before.json').read_text())
dump=PRIVATE/'database.dump';pgenv=PRIVATE/'postgres.env';kcenv=PRIVATE/'keycloak.env'

def records(pg):
    result={}
    for table,cols in CRITICAL.items():
        projection='jsonb_build_object('+','.join("'"+c+"',to_jsonb(t)->'"+c+"'" for c in cols)+')'
        result[table]=json.loads(local_sql(pg,'SELECT coalesce(jsonb_agg('+projection+"),'[]'::jsonb) FROM public."+table+' t;'))
    return result
def compare(pg,baseline,label):
    current=records(pg)
    for table in ['realm','user_entity','client','credential']:
        check(label+'_'+table+'_unchanged',sorted(map(digest,current[table]))==sorted(map(digest,baseline[table])))
    for table in ['keycloak_role','user_role_mapping']:
        check(label+'_'+table+'_existing_records_preserved',set(map(digest,baseline[table])).issubset(set(map(digest,current[table]))))
    original=set(map(digest,baseline['keycloak_role']));added=[x for x in current['keycloak_role'] if digest(x) not in original]
    check(label+'_only_expected_organization_role_additions',all(x['name'] in ['view-organizations','manage-organizations','query-organizations'] for x in added))
    REPORT.setdefault('criticalCounts',{})[label]={table:{'source':len(baseline[table]),'current':len(current[table])} for table in CRITICAL}
    return current
ORG_SQL="""
WITH ac AS (
 SELECT r.master_admin_client AS id,'master' AS kind FROM realm r WHERE r.master_admin_client IS NOT NULL
 UNION ALL SELECT c.id,'local' FROM client c JOIN realm r ON r.id=c.realm_id WHERE c.client_id='realm-management' AND r.name<>'master'
), wanted AS (
 SELECT ac.id,ac.kind,c.realm_id,roles.name FROM ac JOIN client c ON c.id=ac.id CROSS JOIN (VALUES ('view-organizations'),('manage-organizations'),('query-organizations')) roles(name)
), found AS (
 SELECT w.*,k.id AS role_id FROM wanted w LEFT JOIN keycloak_role k ON k.client=w.id AND k.name=w.name
), parents AS (
 SELECT f.*,p.id AS parent_id FROM found f LEFT JOIN keycloak_role p ON
  (f.kind='master' AND p.realm_id=f.realm_id AND p.name='admin' AND NOT p.client_role)
  OR (f.kind='local' AND p.client=f.id AND p.name='realm-admin')
)
SELECT jsonb_build_object('adminClients',(SELECT count(*) FROM ac),'expectedRoles',(SELECT count(*) FROM wanted),
 'rolesPresent',(SELECT count(*) FROM found WHERE role_id IS NOT NULL),
 'adminComposites',(SELECT count(*) FROM parents p JOIN composite_role cr ON cr.composite=p.parent_id AND cr.child_role=p.role_id),
 'viewQueryComposites',(SELECT count(*) FROM ac JOIN keycloak_role v ON v.client=ac.id AND v.name='view-organizations'
  JOIN keycloak_role q ON q.client=ac.id AND q.name='query-organizations' JOIN composite_role cr ON cr.composite=v.id AND cr.child_role=q.id));
"""
def organization_roles(pg,label):
    x=json.loads(local_sql(pg,ORG_SQL))
    check(label+'_all_organization_roles',x['adminClients']>0 and x['expectedRoles']==3*x['adminClients']==x['rolesPresent'])
    check(label+'_admin_role_composites',x['adminComposites']==x['expectedRoles'])
    check(label+'_view_implies_query',x['viewQueryComposites']==x['adminClients'])
    REPORT.setdefault('organizationRoles',{})[label]=x
    return x
def wait_existing_kc(name,runner):
    for _ in range(120):
        if not inspect(name)['State']['Running']:raise RuntimeError('restarted_keycloak_exited')
        r=request(runner,'http://'+name+':9000/health/ready')
        if r and r['status']==200 and json.loads(r['text']).get('status')=='UP':return
        time.sleep(.5)
    raise RuntimeError('restarted_keycloak_timeout')
def new_volume(suffix):
    name=OWNER+'-'+suffix;docker(['volume','create','--label',LABEL+'='+OWNER,name]);volumes.append(name);return name

try:
    acl(PRIVATE_ROOT);acl(PRIVATE);check('private_acl_exclusive',True)
    endpoint=docker(['context','inspect','--format','{{.Endpoints.docker.Host}}']).stdout.decode().strip()
    check('local_docker_only',endpoint.startswith(('npipe://','unix://')))
    REPORT['imageId']=inspect(NEW_KC,'image')['Id'];REPORT['postgresImageId']=inspect(NEW_PG,'image')['Id'];REPORT['sourceDumpSha256']=hashlib.sha256(dump.read_bytes()).hexdigest()
    stage='network_setup';network=OWNER+'-net';docker(['network','create','--internal','--label',LABEL+'='+OWNER,network]);check('internal_network',inspect(network,'network')['Internal'])
    runner=OWNER+'-http';docker(['run','-d','--name',runner,'--label',LABEL+'='+OWNER,'--network',network,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','256m','--cpus','.25','--pids-limit','64','--entrypoint','node','node:22-bookworm-slim','-e','setInterval(()=>{},100000)']);containers.append(runner)
    stage='clean_restore';pg=start_pg('clean-pg',NEW_PG,new_volume('clean'),pgenv);restore(pg,dump)
    check('original_dump_critical_hashes_exact',local_critical(pg)==before['critical']);baseline=records(pg)
    (PRIVATE/'migration-fixed-baseline-records.json').write_text(json.dumps(baseline))
    stage='clean_target';kc=start_kc('fixed-kc',NEW_KC,kcenv,runner,before['admin'])
    check('existing_password_login_without_reset',REPORT['keycloakChecks']['fixed-kc']['adminRead'])
    current=compare(pg,baseline,'clean_migration');org=organization_roles(pg,'clean_migration')
    provider=request(runner,'http://'+kc+':8080/realms/master/otziv-security/generation/00000000-0000-0000-0000-000000000000')
    check('provider_unauthenticated_endpoint_denied',provider and provider['status']==401)
    provider_tables=json.loads(local_sql(pg,"SELECT jsonb_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'otziv_sec_%'"))
    check('provider_schema_present',isinstance(provider_tables,list) and len(provider_tables)>0);REPORT['providerTableCount']=len(provider_tables)
    REPORT['cleanMigrationResult']='PASS'
    (PROOF/'actual-source-provisional.json').write_text(json.dumps(REPORT,indent=2)+'\n')
    print('PASS CLEAN_ACTUAL_MIGRATION_PROVISIONAL',flush=True)
    stage='target_restart';docker(['restart',kc]);wait_existing_kc(kc,runner)
    check('second_start_critical_records_idempotent',{k:sorted(map(digest,v)) for k,v in records(pg).items()}=={k:sorted(map(digest,v)) for k,v in current.items()})
    check('second_start_organization_roles_idempotent',organization_roles(pg,'second_start')==org);stop(kc);stop(pg)
    stage='fault_restore';faultpg=start_pg('fault-pg',NEW_PG,new_volume('fault'),pgenv);restore(faultpg,dump)
    faultbaseline=records(faultpg)
    local_sql(faultpg,"""CREATE SEQUENCE otziv_fixture_org_attempts;
    CREATE FUNCTION otziv_fixture_reject_org_role() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
     IF NEW.name IN ('view-organizations','manage-organizations','query-organizations') THEN
      IF nextval('otziv_fixture_org_attempts') >= 4 THEN RAISE EXCEPTION 'fixture_after_three_organization_role_writes'; END IF;
     END IF; RETURN NEW; END $$;
    CREATE TRIGGER otziv_fixture_reject_org_role BEFORE INSERT ON keycloak_role FOR EACH ROW EXECUTE FUNCTION otziv_fixture_reject_org_role();""")
    stage='fault_target';fault_failed=False
    try:start_kc('fault-kc',NEW_KC,kcenv,runner,before['admin'])
    except RuntimeError as error:
        fault_failed=True;REPORT['injectedFailureCode']=str(error)
    check('injected_role_write_failure_stops_startup',fault_failed)
    attempts=int(local_sql(faultpg,'SELECT last_value FROM otziv_fixture_org_attempts'))
    check('failure_after_at_least_three_actual_role_inserts',attempts>=4)
    afterfault=records(faultpg)
    check('fresh_transaction_no_partial_critical_model_changes',{k:sorted(map(digest,v)) for k,v in afterfault.items()}=={k:sorted(map(digest,v)) for k,v in faultbaseline.items()})
    REPORT['injectedRoleInsertAttempts']=attempts
    local_sql(faultpg,'DROP TRIGGER otziv_fixture_reject_org_role ON keycloak_role; DROP FUNCTION otziv_fixture_reject_org_role(); DROP SEQUENCE otziv_fixture_org_attempts;')
    stage='recovery_target';recovered=start_kc('recovered-kc',NEW_KC,kcenv,runner,before['admin']);compare(faultpg,faultbaseline,'recovered_migration');organization_roles(faultpg,'recovered_migration');stop(recovered);stop(faultpg)
    stage='fresh_rollback';oldpg=start_pg('rollback-pg',OLD_PG,new_volume('rollback'),pgenv);restore(oldpg,dump)
    check('fresh_17_10_rollback_all_88_tables',local_tables(oldpg)==json.loads((PRIVATE/'restored-tables.json').read_text()))
    oldkc=start_kc('rollback-kc',OLD_KC,kcenv,runner,before['admin']);stop(oldkc)
    check('fresh_rollback_old_keycloak_critical_hashes',local_critical(oldpg)==before['critical']);stop(oldpg)
    REPORT['freshRollbackResult']='PASS';REPORT['result']='PASS'
except Exception as error:
    REPORT['errorCode']=str(error) if isinstance(error,RuntimeError) else stage+'_unexpected_error'
    (PRIVATE/'candidate-rehearsal-exception.txt').write_text(repr(error))
finally:
    for name in reversed(containers):
        try:
            obj=inspect(name)
            if obj['Config'].get('Labels',{}).get(LABEL)==OWNER:
                p=docker(['logs',name],allow=True);(PRIVATE/(name+'.log')).write_bytes(p.stdout+p.stderr);docker(['rm','-f','-v',name],allow=True)
        except Exception:REPORT.setdefault('cleanupErrors',[]).append('container_cleanup_failed')
    for name in volumes:
        try:
            if inspect(name,'volume').get('Labels',{}).get(LABEL)==OWNER:docker(['volume','rm',name])
        except Exception:REPORT.setdefault('cleanupErrors',[]).append('volume_cleanup_failed')
    if network:
        try:
            if inspect(network,'network').get('Labels',{}).get(LABEL)==OWNER:docker(['network','rm',network])
        except Exception:REPORT.setdefault('cleanupErrors',[]).append('network_cleanup_failed')
    REPORT['completedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();REPORT['scriptSha256']=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()
    if REPORT.get('cleanupErrors'):REPORT['result']='FAIL'
    (PROOF/('actual-source-'+REPORT.get('imageId','unknown').replace(':','-')[:24]+'.json')).write_text(json.dumps(REPORT,indent=2)+'\n')
    print(json.dumps({'result':REPORT['result'],'cleanMigrationResult':REPORT.get('cleanMigrationResult'),'checks':len(REPORT['checks']),'errorCode':REPORT.get('errorCode')}),flush=True)
    sys.exit(0 if REPORT['result']=='PASS' else 1)
