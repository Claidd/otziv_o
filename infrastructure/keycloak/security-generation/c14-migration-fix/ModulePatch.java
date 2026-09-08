import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.io.*;

/** Packages javac output from three exact vendor source files; no bytecode editing. */
public final class ModulePatch {
    static final Map<String,List<String>> MODULES = Map.of(
        "keycloak-model-storage-private", List.of("org/keycloak/models/cache/CacheRealmProvider", "org/keycloak/migration/migrators/RealmMigration"),
        "keycloak-model-infinispan", List.of("org/keycloak/models/cache/infinispan/RealmCacheSession"));
    static final Map<String,String> EXPECTED = Map.of(
        "keycloak-model-storage-private", "bc50fbd743d2c5dc5f7c68c0fccbdece69e3d26639e81ff6c180fe3734f22859",
        "keycloak-model-infinispan", "a4a0e2148c9795bf12f365932a0e6e2f4fb25c1339d8cc2b08bf5e66b8541652");
    static void require(boolean value,String code) { if (!value) throw new IllegalStateException(code); }
    static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    static String sha(Path path) throws Exception { return sha(Files.readAllBytes(path)); }
    static boolean family(String entry,List<String> roots) {
        return roots.stream().anyMatch(root -> entry.equals(root+".class") || entry.startsWith(root+"$") && entry.endsWith(".class"));
    }
    static void class17(byte[] bytes) {
        require(bytes.length>8 && bytes[0]==(byte)0xca && bytes[1]==(byte)0xfe && bytes[2]==(byte)0xba && bytes[3]==(byte)0xbe, "class_magic");
        require((bytes[6]&255)*256+(bytes[7]&255)==61,"class_major_not_61");
    }
    static Map<String,byte[]> contents(Path file) throws Exception {
        Map<String,byte[]> result=new TreeMap<>();
        try(ZipFile zip=new ZipFile(file.toFile())) {
            for(var items=zip.entries();items.hasMoreElements();) {
                ZipEntry entry=items.nextElement();
                require(!result.containsKey(entry.getName()),"duplicate_zip_entry");
                require(!entry.getName().matches("(?i)META-INF/.*\\.(SF|RSA|DSA|EC)"),"signed_vendor_jar_requires_explicit_resigning");
                if(!entry.isDirectory()) result.put(entry.getName(),zip.getInputStream(entry).readAllBytes());
            }
        }
        byte[] manifest=result.get("META-INF/MANIFEST.MF");require(manifest!=null,"manifest_missing");
        require(!new String(manifest,java.nio.charset.StandardCharsets.UTF_8).contains("-Digest"),"signed_manifest");
        return result;
    }
    static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n")+"\"";
        if(value instanceof Number || value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().sorted(Comparator.comparing(e->e.getKey().toString())).map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(value instanceof Collection<?> c)return "["+String.join(",",c.stream().map(ModulePatch::json).toList())+"]";
        throw new IllegalArgumentException("json_type");
    }
    public static void main(String[] args) throws Exception {
        Path vendor=Path.of(args[0]),classes=Path.of(args[1]),out=Path.of(args[2]),patch=Path.of(args[3]);
        Map<String,byte[]> emitted=new TreeMap<>();
        try(var paths=Files.walk(classes)) { for(Path p:paths.filter(Files::isRegularFile).toList()) {
            String name=classes.relativize(p).toString().replace(File.separatorChar,'/');
            require(MODULES.values().stream().anyMatch(roots->family(name,roots)),"unexpected_compiler_output");
            byte[] bytes=Files.readAllBytes(p);class17(bytes);emitted.put(name,bytes);
        }}
        List<Object> modules=new ArrayList<>();Set<String> used=new TreeSet<>();
        for(String module:new TreeSet<>(MODULES.keySet())) {
            Path original=vendor.resolve("main/org.keycloak."+module+"-26.7.3.jar");
            require(sha(original).equals(EXPECTED.get(module)),"vendor_jar_identity");
            List<String> roots=MODULES.get(module);Map<String,byte[]> old=contents(original),next=new TreeMap<>(old);
            List<Object> replaced=new ArrayList<>();List<String> obsolete=new ArrayList<>();Map<String,String> unchanged=new TreeMap<>();
            for(var e:old.entrySet()) {
                if(family(e.getKey(),roots)) {
                    class17(e.getValue());next.remove(e.getKey());
                    if(!emitted.containsKey(e.getKey()))obsolete.add(e.getKey());
                } else unchanged.put(e.getKey(),sha(e.getValue()));
            }
            for(var e:emitted.entrySet()) if(family(e.getKey(),roots)) {
                next.put(e.getKey(),e.getValue());used.add(e.getKey());
                replaced.add(Map.of("entry",e.getKey(),"originalSha256",old.containsKey(e.getKey())?sha(old.get(e.getKey())):"absent","compiledSha256",sha(e.getValue())));
            }
            for(String root:roots)require(next.containsKey(root+".class"),"missing_source_top_level_class");
            Path target=out.resolve(module+".jar");
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(target))) {
                zip.setLevel(9);
                for(var e:next.entrySet()) {
                    ZipEntry entry=new ZipEntry(e.getKey());entry.setTimeLocal(java.time.LocalDateTime.of(1980,1,1,0,0));
                    zip.putNextEntry(entry);zip.write(e.getValue());zip.closeEntry();
                }
            }
            Map<String,byte[]> verified=contents(target);
            for(var e:unchanged.entrySet())require(sha(verified.get(e.getKey())).equals(e.getValue()),"unrelated_entry_changed");
            require(Arrays.equals(old.get("META-INF/MANIFEST.MF"),verified.get("META-INF/MANIFEST.MF")),"manifest_changed");
            modules.add(Map.of("module",module,"originalSha256",sha(original),"patchedSha256",sha(target),"recompiledEntries",replaced,"removedObsoleteSourceClasses",obsolete,"unchangedEntrySha256",unchanged,"manifestUnchanged",true,"vendorSignaturesAbsent",true));
        }
        require(used.equals(emitted.keySet()),"unpackaged_compiler_output");
        Map<String,String> classpath=new TreeMap<>();
        try(var paths=Files.walk(vendor)) {for(Path p:paths.filter(x->Files.isRegularFile(x)&&x.toString().endsWith(".jar")).toList())classpath.put(vendor.relativize(p).toString().replace(File.separatorChar,'/'),sha(p));}
        Map<String,Object> provenance=new TreeMap<>();
        provenance.put("schema","otziv-keycloak-realm-migration-source-rebuild-v1");
        provenance.put("baseImage","ghcr.io/claidd/otziv-security@sha256:93dd42a5379a80fc0673bc1d0c5601d3b35009c8250394b1e2b45082388c82cc");
        provenance.put("sourceArchiveSha256",sha(Path.of("/source.tar.gz")));
        provenance.put("sourceProvenanceSha256",sha(patch.resolve("source-provenance.json")));
        provenance.put("patchSha256",sha(patch.resolve("managed-models.patch")));
        provenance.put("compiler",System.getProperty("java.runtime.version"));provenance.put("classMajor",61);
        provenance.put("classpathExpression","/vendor/boot/*:/vendor/main/*:/vendor/deployment/*");
        provenance.put("classpathJarSha256",classpath);provenance.put("modules",modules);
        provenance.put("archiveEntryTimestamp","1980-01-01T00:00:00");provenance.put("archiveEntryOrder","lexicographic");
        Files.writeString(out.resolve("provenance.json"),json(provenance)+"\n");
        System.out.println("PASS exact source families packaged; all unrelated entries unchanged");
    }
}
