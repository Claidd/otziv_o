import java.lang.reflect.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.quarkus.builder.*;
import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.annotations.*;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.hibernate.orm.deployment.*;
import io.quarkus.hibernate.orm.deployment.spi.AdditionalJpaModelBuildItem;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.jboss.jandex.Indexer;

/** Executes actual vendor mutation/snapshot methods under the actual Quarkus build scheduler. */
public final class DescriptorOrderingProof {
    @jakarta.persistence.Entity public static class CustomEntityA { @jakarta.persistence.Id public long id; }
    @jakarta.persistence.Entity public static class CustomEntityB { @jakarta.persistence.Id public long id; }
    @jakarta.persistence.Entity public static class OtherUnitEntity { @jakarta.persistence.Id public long id; }
    public static final class Snapshot extends SimpleBuildItem { final Set<String> names; Snapshot(Set<String> names) { this.names = names; } }
    public static final class WriterComplete extends SimpleBuildItem {}

    static final class Descriptor extends ParsedPersistenceXmlDescriptor {
        final List<String> live = new ArrayList<>(List.of("existing.A", "existing.B"));
        final CountDownLatch readerEntered = new CountDownLatch(1), writerDone = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        Descriptor() { super((URL) null); setName("keycloak-default"); }
        @Override public void addClasses(String... classes) { live.addAll(List.of(classes)); }
        @Override public List<String> getManagedClassNames() {
            return new AbstractList<>() {
                public int size() { return live.size(); }
                public String get(int i) { return live.get(i); }
                public Iterator<String> iterator() {
                    Iterator<String> iterator = live.iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public String next() {
                            if (!completed.get()) { readerEntered.countDown(); await(writerDone); }
                            return iterator.next();
                        }
                    };
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        boolean expectedPatched = args.length == 1 && args[0].equals("patched");
        System.setProperty("io.quarkus.builder.execution.corePoolSize", "2");
        System.setProperty("io.quarkus.builder.execution.maxPoolSize", "2");
        Method reader = HibernateOrmProcessor.class.getMethod("contributePersistenceXmlToJpaModel", BuildProducer.class, List.class);
        boolean ordered = Arrays.stream(reader.getAnnotationsByType(Consume.class)).anyMatch(annotation -> annotation.value() == AdditionalJpaModelBuildItem.class);
        if (ordered != expectedPatched) throw new AssertionError("Unexpected compiled annotation");
        Class<?> processorClass = Class.forName("org.keycloak.quarkus.deployment.KeycloakProcessor");
        Constructor<?> constructor = processorClass.getDeclaredConstructor(); constructor.setAccessible(true);
        Object keycloak = constructor.newInstance();
        Method writer = processorClass.getDeclaredMethod("configureDefaultPersistenceUnitEntities", ParsedPersistenceXmlDescriptor.class, CombinedIndexBuildItem.class, List.class);
        writer.setAccessible(true);
        Indexer indexer = new Indexer();
        indexer.indexClass(CustomEntityA.class); indexer.indexClass(CustomEntityB.class); indexer.indexClass(OtherUnitEntity.class);
        var index = indexer.complete();
        CombinedIndexBuildItem indexed = new CombinedIndexBuildItem(index, index);
        Descriptor descriptor = new Descriptor();
        BuildChainBuilder chain = BuildChain.builder();
        chain.addFinal(Snapshot.class).addFinal(WriterComplete.class);
        chain.addBuildStep(context -> {
            try {
                // Baseline reader is forced into ArrayList iteration before actual registration.
                // With the dependency edge the reader cannot start, so this bounded wait expires.
                descriptor.readerEntered.await(250, TimeUnit.MILLISECONDS);
                writer.invoke(keycloak, descriptor, indexed, List.of(OtherUnitEntity.class.getName()));
                descriptor.completed.set(true); descriptor.writerDone.countDown();
                context.produce(new WriterComplete());
            } catch (Exception failure) { throw new RuntimeException(failure); }
        }).produces(AdditionalJpaModelBuildItem.class).produces(WriterComplete.class).build();
        BuildStepBuilder reading = chain.addBuildStep(context -> {
            List<JpaModelPersistenceUnitContributionBuildItem> contributions = new ArrayList<>();
            new HibernateOrmProcessor().contributePersistenceXmlToJpaModel(contributions::add, List.of(new PersistenceXmlDescriptorBuildItem(descriptor)));
            if (contributions.size() != 1) throw new AssertionError("Expected one persistence unit");
            context.produce(new Snapshot(contributions.get(0).explicitlyListedClassNames));
        }).produces(Snapshot.class);
        // Same annotation -> scheduler mapping used by vendor ExtensionLoader:797-802.
        for (Consume annotation : reader.getAnnotationsByType(Consume.class)) reading.afterProduce(annotation.value());
        reading.build();
        try {
            BuildResult result = chain.build().createExecutionBuilder("descriptor-ordering-proof").execute();
            if (!expectedPatched) throw new AssertionError("Baseline did not reproduce the race");
            Set<String> expected = Set.of("existing.A", "existing.B", CustomEntityA.class.getName(), CustomEntityB.class.getName());
            if (!result.consume(Snapshot.class).names.equals(expected)) throw new AssertionError("Lost or incorrectly included model classes");
            System.out.println("PASS: actual ordered Quarkus graph, complete four-class model, other-unit entity excluded");
        } catch (BuildException failure) {
            if (expectedPatched || !containsConcurrentModification(failure)) throw failure;
            System.out.println("EXPECTED_RED: actual vendor descriptor snapshot races with actual Keycloak entity registration");
        }
    }

    static boolean containsConcurrentModification(Throwable failure) {
        if (failure instanceof ConcurrentModificationException) return true;
        if (failure.getCause() != null && containsConcurrentModification(failure.getCause())) return true;
        for (Throwable suppressed : failure.getSuppressed()) if (containsConcurrentModification(suppressed)) return true;
        return false;
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Causal barrier did not complete"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
