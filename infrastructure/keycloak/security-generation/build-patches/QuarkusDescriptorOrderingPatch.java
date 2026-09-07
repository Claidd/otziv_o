import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** Annotation-only downstream equivalent of descriptor-ordering.patch; no method code changes. */
public final class QuarkusDescriptorOrderingPatch {
    static final String ORIGINAL_SHA256 = "d2efdcb37188c16d2e8916516553c2cce4b98c7abeec23f2532dd065b896e39f";
    static final String ENTRY = "io/quarkus/hibernate/orm/deployment/HibernateOrmProcessor.class";
    static final String METHOD = "contributePersistenceXmlToJpaModel";
    static final String DESCRIPTOR = "(Lio/quarkus/deployment/annotations/BuildProducer;Ljava/util/List;)V";
    static final String CONSUME = "Lio/quarkus/deployment/annotations/Consume;";
    static final String ITEM = "Lio/quarkus/hibernate/orm/deployment/spi/AdditionalJpaModelBuildItem;";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected immutable input and separate output JAR");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) throw new IllegalArgumentException("Separate output required");
        if (!hash(Files.readAllBytes(input)).equals(ORIGINAL_SHA256)) throw new IllegalArgumentException("Unexpected vendor JAR SHA256");
        byte[] original;
        try (JarFile jar = new JarFile(input.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (entry.getName().matches("(?i)META-INF/.*\\.(SF|RSA|DSA|EC)")) throw new IllegalArgumentException("Signed JAR cannot be rewritten");
            }
            original = jar.getInputStream(jar.getJarEntry(ENTRY)).readAllBytes();
        }
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] targets = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!METHOD.equals(name) || !DESCRIPTOR.equals(desc)) return delegate;
                targets[0]++;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (CONSUME.equals(annotation)) throw new IllegalArgumentException("Unexpected existing ordering annotation");
                        return super.visitAnnotation(annotation, visible);
                    }
                    @Override public void visitEnd() {
                        AnnotationVisitor annotation = delegate.visitAnnotation(CONSUME, true);
                        annotation.visit("value", Type.getType(ITEM));
                        annotation.visitEnd();
                        super.visitEnd();
                    }
                };
            }
        }, 0);
        if (targets[0] != 1) throw new IllegalArgumentException("Expected exactly one target method");
        byte[] patched = writer.toByteArray();
        // ASM preserves the original constant-pool prefix, but normalizes the order
        // of the visited method's Code sub-attributes. Restore their original bytes
        // after verifying equal size and unchanged constant-pool indices.
        if (!Arrays.equals(original, 10, reader.header, patched, 10, reader.header)) throw new IllegalStateException("Original constant-pool entries changed");
        Map<String, CodeSection> originalCode = codeSections(original), patchedCode = codeSections(patched);
        for (var entry : originalCode.entrySet()) {
            CodeSection afterSection = patchedCode.get(entry.getKey());
            if (afterSection == null) throw new IllegalStateException("Method disappeared");
            if (!Arrays.equals(entry.getValue().data(), afterSection.data())) {
                if (!entry.getKey().equals(METHOD + DESCRIPTOR) || entry.getValue().data().length != afterSection.data().length) throw new IllegalStateException("Unexpected method rewrite");
                System.arraycopy(entry.getValue().data(), 0, patched, afterSection.offset(), afterSection.data().length);
            }
        }
        Map<String, String> codeHashes = codeHashes(original);
        if (!codeHashes.equals(codeHashes(patched))) throw new IllegalStateException("Method Code attributes changed");
        Map<String, String> before = new TreeMap<>(), after = new TreeMap<>();
        try (JarFile jar = new JarFile(input.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                before.put(entry.getName(), hash(bytes));
                JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(entry.getTime());
                if (entry.getComment() != null) copy.setComment(entry.getComment());
                out.putNextEntry(copy);
                out.write(ENTRY.equals(entry.getName()) ? patched : bytes);
                out.closeEntry();
            }
        }
        try (JarFile jar = new JarFile(output.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) after.put(entry.getName(), hash(jar.getInputStream(entry).readAllBytes()));
        }
        if (!before.keySet().equals(after.keySet())) throw new IllegalStateException("JAR entries changed");
        List<String> changed = before.keySet().stream().filter(name -> !before.get(name).equals(after.get(name))).toList();
        if (!changed.equals(List.of(ENTRY))) throw new IllegalStateException("Unexpected changed JAR entries");
        StringBuilder evidence = new StringBuilder("{\n  \"schema\": \"otziv-quarkus-ordering-patch-v1\",\n");
        evidence.append("  \"vendorJarSha256\": \"").append(ORIGINAL_SHA256).append("\",\n");
        evidence.append("  \"derivativeJarSha256\": \"").append(hash(Files.readAllBytes(output))).append("\",\n");
        evidence.append("  \"changedEntry\": \"").append(ENTRY).append("\",\n");
        evidence.append("  \"unchangedEntries\": ").append(before.size() - 1).append(",\n");
        evidence.append("  \"methodCodeAttributesUnchanged\": ").append(codeHashes.size()).append(",\n");
        evidence.append("  \"vendorSignaturesPresent\": false,\n  \"derivativeIsVendorSigned\": false,\n  \"methodCodeSha256\": {\n");
        int count = 0;
        for (var entry : codeHashes.entrySet()) {
            if (count++ > 0) evidence.append(",\n");
            evidence.append("    \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append('"');
        }
        evidence.append("\n  }\n}\n");
        Files.writeString(Path.of(output + ".provenance.json"), evidence, StandardOpenOption.CREATE_NEW);
        System.out.println("PASS: one annotation, one changed class entry, " + codeHashes.size() + " unchanged method Code attributes");
    }

    static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    record CodeSection(int offset, byte[] data) {}

    static Map<String, String> codeHashes(byte[] bytes) throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (var entry : codeSections(bytes).entrySet()) result.put(entry.getKey(), hash(entry.getValue().data()));
        return result;
    }

    static Map<String, CodeSection> codeSections(byte[] bytes) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xcafebabe) throw new IllegalArgumentException("Invalid class");
        in.readUnsignedShort(); in.readUnsignedShort();
        int count = in.readUnsignedShort(); String[] utf8 = new String[count];
        for (int index = 1; index < count; index++) {
            switch (in.readUnsignedByte()) {
                case 1 -> utf8[index] = in.readUTF();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); index++; }
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new IllegalArgumentException("Unknown constant pool tag");
            }
        }
        in.skipNBytes(6); in.skipNBytes(in.readUnsignedShort() * 2L);
        for (int field = in.readUnsignedShort(); field > 0; field--) skipMember(in);
        Map<String, CodeSection> result = new TreeMap<>();
        for (int method = in.readUnsignedShort(); method > 0; method--) {
            in.readUnsignedShort(); String name = utf8[in.readUnsignedShort()] + utf8[in.readUnsignedShort()];
            for (int attribute = in.readUnsignedShort(); attribute > 0; attribute--) {
                String attributeName = utf8[in.readUnsignedShort()];
                int length = in.readInt(), offset = bytes.length - in.available();
                byte[] data = in.readNBytes(length);
                if ("Code".equals(attributeName)) result.put(name, new CodeSection(offset, data));
            }
        }
        return result;
    }

    static void skipMember(DataInputStream in) throws IOException {
        in.skipNBytes(6);
        for (int attribute = in.readUnsignedShort(); attribute > 0; attribute--) { in.readUnsignedShort(); in.skipNBytes(Integer.toUnsignedLong(in.readInt())); }
    }
}
