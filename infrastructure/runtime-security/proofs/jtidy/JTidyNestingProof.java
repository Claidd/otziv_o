import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.w3c.tidy.Tidy;

/** Bounded in-memory regression; same compiled class and inputs for both vendor JARs. */
public final class JTidyNestingProof {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("ordinary") || args[0].equals("excessive"))) {
            throw new IllegalArgumentException("ordinary or excessive required");
        }
        boolean excessive = args[0].equals("excessive");
        int depth = excessive ? 8192 : 4;
        String input = "<!DOCTYPE html><html><head><title>JTidy regression</title></head><body>"
                + "<div>".repeat(depth) + "bounded ordinary text" + "</div>".repeat(depth) + "</body></html>";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        System.out.println("INPUT case=" + args[0] + " depth=" + depth + " bytes=" + bytes.length
                + " sha256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        Tidy tidy = new Tidy();
        // Upstream updates the public getParseErrors() counter only with quiet=false.
        tidy.setQuiet(false);
        tidy.setShowWarnings(false);
        tidy.setInputEncoding("UTF-8");
        tidy.setOutputEncoding("UTF-8");
        StringWriter diagnostics = new StringWriter();
        tidy.setErrout(new PrintWriter(diagnostics));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            Object document = tidy.parse(new ByteArrayInputStream(bytes), output);
            int errors = tidy.getParseErrors();
            if (excessive) {
                if (errors != 1 || output.size() != 0) {
                    System.out.println("FAIL expected controlled rejection: errors=" + errors + " outputBytes=" + output.size());
                    System.exit(43);
                }
                System.out.println("PASS controlled excessive-nesting rejection: errors=" + errors + " outputBytes=0");
            } else {
                String rendered = output.toString(StandardCharsets.UTF_8);
                if (document == null || errors != 0 || !rendered.contains("bounded ordinary text")
                        || !rendered.contains("JTidy regression")) {
                    throw new AssertionError("ordinary HTML behavior changed");
                }
                System.out.println("PASS ordinary HTML preserved: errors=0 outputBytes=" + output.size());
            }
        } catch (StackOverflowError error) {
            // Record the historical failure without dumping or retaining an unbounded stack trace.
            System.out.println("FAIL unbounded parser recursion: StackOverflowError");
            System.exit(42);
        }
    }
}
