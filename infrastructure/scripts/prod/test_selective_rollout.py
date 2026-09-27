import io,json,os
from pathlib import Path
import tarfile,tempfile,unittest
from selective_rollout import sync_bundle,unchanged

class SelectiveRolloutTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
    def bundle(self,members):
        target=self.root/'release.tar.gz'
        with tarfile.open(target,'w:gz') as out:
            for name,data in members:
                item=tarfile.TarInfo(name);item.size=len(data);out.addfile(item,io.BytesIO(data))
        return target
    def test_identical_bind_file_keeps_inode_and_changed_file_replaced(self):
        target=self.root/'live';target.mkdir();file=target/'settings.yml';file.write_bytes(b'old')
        inode=file.stat().st_ino
        self.assertEqual([],sync_bundle(self.bundle([('./settings.yml',b'old')]),target))
        self.assertEqual(inode,file.stat().st_ino)
        self.assertEqual(['settings.yml'],sync_bundle(self.bundle([('./settings.yml',b'new')]),target))
        self.assertEqual(b'new',file.read_bytes())
    def test_traversal_rejected_before_any_existing_file_changes(self):
        target=self.root/'live';target.mkdir();file=target/'settings.yml';file.write_bytes(b'old')
        with self.assertRaises(ValueError):sync_bundle(self.bundle([('settings.yml',b'new'),('../escape',b'bad')]),target)
        self.assertEqual(b'old',file.read_bytes())
    def test_only_healthy_matching_service_with_unchanged_bind_is_retained(self):
        row={'State':{'Status':'running','Health':{'Status':'healthy'}},'Config':{'Labels':{'com.docker.compose.config-hash':'same'}},
             'Mounts':[{'Type':'bind','Source':str(self.root/'config')} ]}
        self.assertTrue(unchanged('app','same',[row],['unrelated'],self.root))
        self.assertFalse(unchanged('app','different',[row],[],self.root))
        self.assertFalse(unchanged('app','same',[row],['config/settings.yml'],self.root))
        row['State']['Health']['Status']='unhealthy'
        self.assertFalse(unchanged('app','same',[row],[],self.root))
        self.assertFalse(unchanged('app','same',[],[],self.root))
    def test_seccomp_content_change_recreates_service(self):
        row={'State':{'Status':'running'},'Config':{'Labels':{'com.docker.compose.config-hash':'same'}}}
        desired={'security_opt':['seccomp=policy.json']}
        self.assertFalse(unchanged('worker','same',[row],['policy.json'],self.root,desired))

if __name__=='__main__':unittest.main()
