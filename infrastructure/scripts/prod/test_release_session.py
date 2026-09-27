import unittest
from release_session import transition,active,process_identity
from release_ci import GateError
import os

class SessionTest(unittest.TestCase):
    def begin(self,**kw):return transition(None,'begin','a'*40,'b'*40,'test',now=100,**kw)
    def test_foreign_owner_cannot_begin_renew_or_finish(self):
        value=self.begin()
        for action in ['begin','renew','advance','finish']:
            with self.assertRaises(GateError):transition(value,action,'a'*40,'b'*40,'other',token='other',now=101)
    def test_identical_merge_advances_only_with_owned_token(self):
        value=self.begin()
        with self.assertRaises(GateError):transition(value,'renew','c'*40,'b'*40,'test',value['token'],now=101)
        changed=transition(value,'advance','c'*40,'b'*40,'test',value['token'],now=101)
        self.assertEqual('c'*40,changed['revision'])
        with self.assertRaises(GateError):transition(value,'advance','c'*40,'d'*40,'test',value['token'],now=101)
    def test_expired_preparation_can_be_reclaimed_without_discarding_code(self):
        value=self.begin(ttl=60)
        self.assertFalse(active(value,161))
        result=transition(value,'begin','a'*40,'b'*40,'next',now=161)
        self.assertNotEqual(value['token'],result['token'])
    def test_live_deploy_cannot_expire_into_a_second_deploy(self):
        value=self.begin(pid=123,ttl=60,process=lambda pid:'birth')
        self.assertTrue(active(value,10000,lambda pid:'birth'))
        self.assertFalse(active(value,101,lambda pid:None))
        self.assertFalse(active(value,101,lambda pid:'new-birth'))
    def test_current_process_has_stable_birth_identity(self):
        self.assertTrue(process_identity(os.getpid()))
        self.assertEqual(process_identity(os.getpid()),process_identity(os.getpid()))
    def test_owned_finish_releases_preparation(self):
        value=self.begin();result=transition(value,'finish','a'*40,'b'*40,'test',value['token'],now=101)
        self.assertFalse(active(result,102))

if __name__=='__main__':unittest.main()
