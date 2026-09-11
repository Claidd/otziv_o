"""Exercise the settings script against a CLI boundary enforcing Keycloak session rules."""
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHELL = shutil.which("sh") or (str(Path("C:/Program Files/Git/bin/bash.exe")) if Path("C:/Program Files/Git/bin/bash.exe").is_file() else None)
FAKE = r'''
import json, os, sys
from pathlib import Path
p=Path(os.environ["KC_TEST_STATE"])
s=json.loads(p.read_text())
a=sys.argv[1:]
settings={}
for i,v in enumerate(a[:-1]):
 if v=="-s":
  k,_,value=a[i+1].partition("=");settings[k]=value.strip('"')
if a[0]=="get" and a[1]=="clients":
 query=a[a.index("-q")+1].split("=",1)[1];print({"otziv-frontend":"web","otziv-mobile":"mobile","otziv-backend":"backend"}[query])
elif a[0]=="update" and a[1].startswith("realms/"):
 s["idle"]=int(settings["ssoSessionIdleTimeout"]);s["max"]=int(settings["ssoSessionMaxLifespan"])
 s["offline"]=int(settings["offlineSessionIdleTimeout"]);s["offlineMax"]=int(settings["offlineSessionMaxLifespan"])
elif a[0]=="update" and a[1]=="clients/mobile":
 next_client=dict(s["mobile"])
 for key,limit in [('client.session.idle.timeout',s["idle"]),('client.session.max.lifespan',s["max"])]:
  setting='attributes."'+key+'"'
  if setting in settings:next_client[key]=int(settings[setting])
  if next_client[key]>limit:
   print("Client session timeout exceeds realm SSO limit",file=sys.stderr);sys.exit(1)
 s["mobile"]=next_client
 if "optionalClientScopes" in settings:s["scopes"]=json.loads(settings["optionalClientScopes"])
 s["updates"]+=1
p.write_text(json.dumps(s))
'''

@unittest.skipUnless(SHELL, "POSIX shell required")
class KeycloakClientSessionPolicyTest(unittest.TestCase):
    def exercise(self, offline_seconds):
        with tempfile.TemporaryDirectory(prefix="otziv-kc-policy-") as temp:
            directory=Path(temp)
            fake=directory/"kc.py";fake.write_text(FAKE,encoding="utf-8")
            state=directory/"state.json"
            state.write_text(json.dumps({"idle":28800,"max":86400,"offline":2592000,
                "mobile":{"client.session.idle.timeout":2592000,"client.session.max.lifespan":2592000},"updates":0}))
            source=(HERE/"apply-keycloak-prod-settings.sh").read_text(encoding="utf-8")
            replacement="kc() {\n  "+shlex.quote(sys.executable.replace("\\","/"))+" "+shlex.quote(fake.as_posix())+' "$@"\n}'
            source,count=re.subn(r'^kc\(\) \{\n.*?^\}',lambda _:replacement,source,count=1,flags=re.M|re.S)
            self.assertEqual(count,1)
            script=directory/"settings.sh";script.write_text(source,encoding="utf-8",newline="\n")
            fixture_value=secrets.token_urlsafe(24)
            env=dict(os.environ,KC_TEST_STATE=str(state),KEYCLOAK_ADMIN="test-admin",
                KEYCLOAK_ADMIN_PASSWORD=fixture_value,KEYCLOAK_ADMIN_CLIENT_SECRET=fixture_value,
                OTZIV_APP_BASE_URL="https://example.invalid",KEYCLOAK_PUBLIC_URL="https://example.invalid/keycloak",
                OTZIV_MOBILE_SESSION_SECONDS=str(offline_seconds))
            for _ in range(2):
                result=subprocess.run([SHELL,script.as_posix()],cwd=directory,env=env,capture_output=True,text=True,timeout=30)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertIn("Keycloak production settings applied.",result.stdout)
                self.assertNotIn("could not update optional mobile",result.stderr)
            stored=json.loads(state.read_text())
            self.assertEqual(stored["offline"],offline_seconds)
            self.assertEqual(stored["offlineMax"],offline_seconds)
            self.assertEqual(stored["scopes"],["offline_access"])
            self.assertTrue(all(v<=stored["idle"] for v in stored["mobile"].values()))

    def test_existing_invalid_online_overrides_are_repaired_before_other_client_edits(self):
        self.exercise(2592000)

    def test_custom_offline_duration_does_not_extend_online_sessions(self):
        self.exercise(1209600)

    def test_imported_clients_obey_realm_online_limits(self):
        for name in ("realm-config.json","realm-config.prod.json"):
            realm=json.loads((ROOT/"infrastructure"/"keycloak"/name).read_text(encoding="utf-8"))
            for client in realm["clients"]:
                attrs=client.get("attributes",{})
                for field,limit in (("client.session.idle.timeout","ssoSessionIdleTimeout"),
                                    ("client.session.max.lifespan","ssoSessionMaxLifespan")):
                    self.assertLessEqual(int(attrs.get(field,0)),realm[limit],(name,client["clientId"],field))

if __name__=="__main__":
    unittest.main()
