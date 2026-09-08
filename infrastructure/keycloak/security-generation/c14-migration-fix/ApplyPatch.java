import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Strict unified-diff application: exact line numbers/context, no offsets or fuzz. */
public final class ApplyPatch {
    static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toRealPath();List<String> patch=Files.readAllLines(Path.of(args[1]));
        Set<String> allowed=new HashSet<>(List.of(
            "model/storage-private/src/main/java/org/keycloak/models/cache/CacheRealmProvider.java",
            "model/storage-private/src/main/java/org/keycloak/migration/migrators/RealmMigration.java",
            "model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/RealmCacheSession.java"));
        Pattern header=Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
        int i=0;
        while(i<patch.size()){
            String old=patch.get(i++);require(old.startsWith("--- a/"),"old_path_header");String path=old.substring(6);
            require(allowed.remove(path),"unexpected_or_repeated_source_file");
            require(patch.get(i++).equals("+++ b/"+path),"new_path_header");
            Path file=root.resolve(path).normalize();require(file.startsWith(root),"source_path_escape");
            String raw=Files.readString(file);require(!raw.contains("\r")&&raw.endsWith("\n"),"source_not_canonical_lf");
            List<String> source=new ArrayList<>(Arrays.asList(raw.split("\n",-1)));source.remove(source.size()-1);
            List<String> output=new ArrayList<>();int cursor=0;
            while(i<patch.size()&&!patch.get(i).startsWith("--- a/")){
                Matcher h=header.matcher(patch.get(i++));require(h.matches(),"hunk_header");
                int start=Integer.parseInt(h.group(1))-1,oldCount=h.group(2)==null?1:Integer.parseInt(h.group(2)),newStart=Integer.parseInt(h.group(3))-1,newCount=h.group(4)==null?1:Integer.parseInt(h.group(4));
                require(start>=cursor&&start<=source.size(),"hunk_source_offset");
                while(cursor<start)output.add(source.get(cursor++));require(output.size()==newStart,"hunk_target_offset");
                int removed=0,added=0;
                while(i<patch.size()&&!patch.get(i).startsWith("@@ ")&&!patch.get(i).startsWith("--- a/")){
                    String line=patch.get(i++);require(!line.isEmpty(),"missing_hunk_prefix");char op=line.charAt(0);String value=line.substring(1);
                    require(op==' '||op=='+'||op=='-',"unsupported_hunk_operation");
                    if(op!='+') {require(cursor<source.size()&&source.get(cursor).equals(value),"non_exact_source_context");cursor++;removed++;}
                    if(op!='-'){output.add(value);added++;}
                }
                require(removed==oldCount&&added==newCount,"hunk_line_counts");
            }
            while(cursor<source.size())output.add(source.get(cursor++));Files.writeString(file,String.join("\n",output)+"\n");
        }
        require(allowed.isEmpty(),"missing_source_patch");System.out.println("PASS exact three-file source patch");
    }
}
