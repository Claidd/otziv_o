import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

/** Offline DTO-only check. The classpath must use the verified artifact's
 * BOOT-INF/classes and BOOT-INF/lib/*; this never starts the application. */
class ArtifactPublicPaymentFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected BOOT-INF/classes directory, synthetic input JSON and new output JSON");
        var classes = Path.of(args[0]).toRealPath();
        var type = Class.forName("com.hunt.otziv.payments.dto.PublicPaymentLinkResponse");
        var loaded = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!loaded.equals(classes)) throw new IllegalStateException("DTO did not load from the expected artifact classes");
        var output = Path.of(args[2]);
        if (Files.exists(output)) throw new IllegalStateException("Refusing to overwrite existing artifact fixture");
        var mapper = new JacksonJsonHttpMessageConverter().getMapper();
        var dto = mapper.readValue(Files.readString(Path.of(args[1])), type);
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto) + "\n");
        System.out.println("Verified artifact DTO serialized through its own Spring MVC/Jackson runtime; no application started");
    }
}
