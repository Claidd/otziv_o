import hashlib,pathlib,zipfile
expected='1290c2795e93e8a6861a6c4d9ff0d844d32f5ea178362cb2721edf5561e828b1'
source=pathlib.Path('/tmp/databind.jar');assert hashlib.sha256(source.read_bytes()).hexdigest()==expected
def selected(n):return n.startswith('com/fasterxml/jackson/databind/') or n.startswith('META-INF/maven/com.fasterxml.jackson.core/jackson-databind/')
with zipfile.ZipFile(source) as updated,zipfile.ZipFile('/tmp/cli.jar') as old,zipfile.ZipFile('/tmp/patched-cli.jar','w') as out:
 names=old.namelist();assert len(names)==len(set(names))
 assert any(n.startswith('com/fasterxml/jackson/databind/') for n in names)
 for item in old.infolist():
  if not selected(item.filename):out.writestr(item,old.read(item.filename))
 for item in updated.infolist():
  if selected(item.filename):out.writestr(item,updated.read(item.filename))
with zipfile.ZipFile('/tmp/patched-cli.jar') as new,zipfile.ZipFile('/tmp/cli.jar') as old,zipfile.ZipFile(source) as updated:
 original={n:hashlib.sha256(old.read(n)).hexdigest() for n in old.namelist() if not selected(n)}
 assert original=={n:hashlib.sha256(new.read(n)).hexdigest() for n in new.namelist() if not selected(n)}
 assert {n:hashlib.sha256(new.read(n)).hexdigest() for n in new.namelist() if selected(n)}=={n:hashlib.sha256(updated.read(n)).hexdigest() for n in updated.namelist() if selected(n)}
