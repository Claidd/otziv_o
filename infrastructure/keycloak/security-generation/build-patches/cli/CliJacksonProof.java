import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.datatype.Duration;
import javax.xml.datatype.XMLGregorianCalendar;

/** Manual, tiny-input check of the real shaded CLI Jackson classes; no server calls. */
public class CliJacksonProof {
    static ObjectMapper mapper() {
        return new ObjectMapper(JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(16).build()).build());
    }
    static boolean rejects(String value, Class<?> type) throws Exception {
        try { mapper().readValue('"' + value + '"', type); return false; }
        catch (com.fasterxml.jackson.core.JsonProcessingException expected) {
            return expected.getMessage().contains("exceeds the maximum allowed");
        }
    }
    public static void main(String[] args) throws Exception {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        for (String module : new String[] {"core.json", "databind.cfg", "datatype.jdk8", "datatype.jsr310", "dataformat.yaml"}) {
            Object version = Class.forName("com.fasterxml.jackson." + module + ".PackageVersion").getField("VERSION").get(null);
            checks.put("jackson_" + module + "_version", "2.21.6".equals(version.toString()));
        }
        checks.put("duration_numeric_constraint", rejects("PT" + "7".repeat(64) + "H", Duration.class));
        checks.put("calendar_numeric_constraint", rejects("2".repeat(64) + "-01-01T00:00:00Z", XMLGregorianCalendar.class));
        checks.put("ordinary_duration", mapper().readValue("\"PT12H\"", Duration.class).getHours() == 12);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checks", checks);
        result.put("jacksonVersion", new ObjectMapper().version().toString());
        result.put("jacksonSource", ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation().toString());
        System.out.println(new ObjectMapper().writeValueAsString(result));
        if (checks.values().stream().anyMatch(value -> !value)) System.exit(1);
    }
}
