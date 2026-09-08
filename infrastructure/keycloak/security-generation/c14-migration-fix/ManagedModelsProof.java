import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import jakarta.persistence.EntityManager;
import org.hibernate.LazyInitializationException;
import org.keycloak.connections.jpa.support.EntityManagerProxy;
import org.keycloak.migration.ModelVersion;
import org.keycloak.migration.migrators.RealmMigration;
import org.keycloak.models.*;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.cache.infinispan.RealmCacheSession;
import org.keycloak.models.cache.infinispan.RealmCacheManager;
import org.keycloak.models.cache.infinispan.events.CacheKeyInvalidatedEvent;

/** Boundary unit proof using the actual RealmMigration and actual five-map clear method. */
public final class ManagedModelsProof {
    static final List<String> MAPS=List.of("managedRealms","managedApplications","managedClientScopes","managedRoles","managedGroups");
    static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static Object ordinary(Object self,Method method,Object[] args){
        return switch(method.getName()){case "hashCode"->System.identityHashCode(self);case "equals"->self==args[0];case "toString"->"fixture";default->method.getReturnType()==boolean.class?false:null;};
    }
    static void field(Object target,String name,Object value)throws Exception{Field f=RealmCacheSession.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object target,String name)throws Exception{Field f=RealmCacheSession.class.getDeclaredField(name);f.setAccessible(true);return (Map<String,Object>)f.get(target);}
    public static final class ManagerHarness extends RealmCacheManager {
        int ended;
        public ManagerHarness(){super(null,null);}
        @Override public void endRevisionBatch(){ended++;}
    }
    public static final class CacheHarness extends RealmCacheSession {
        int dispatched;
        public CacheHarness(){super(null,null);}
        @Override protected void runInvalidations(){
            require(invalidations.size()==1&&listInvalidations.size()==1&&invalidationEvents.size()==1,"pending_notifications_lost_before_completion");
            dispatched++;
        }
    }
    static RealmCacheSession cache()throws Exception{
        Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        sun.misc.Unsafe allocator=(sun.misc.Unsafe)f.get(null);
        RealmCacheSession cache=(RealmCacheSession)allocator.allocateInstance(CacheHarness.class);
        field(cache,"cache",allocator.allocateInstance(ManagerHarness.class));
        for(String name:MAPS)field(cache,name,new HashMap<String,Object>());
        field(cache,"invalidations",new HashSet<>(Set.of("pending-key")));
        field(cache,"listInvalidations",new HashSet<>(Set.of("pending-list")));
        field(cache,"invalidationEvents",new HashSet<>(Set.of(new CacheKeyInvalidatedEvent("pending-event"))));
        field(cache,"transactionActive",true);field(cache,"setRollbackOnly",true);
        return cache;
    }
    static void preserved(RealmCacheSession cache)throws Exception{
        for(String name:List.of("invalidations","listInvalidations","invalidationEvents")){Field f=RealmCacheSession.class.getDeclaredField(name);f.setAccessible(true);require(((Set<?>)f.get(cache)).size()==1,"pending_invalidation_lost");}
        for(String name:List.of("transactionActive","setRollbackOnly")){Field f=RealmCacheSession.class.getDeclaredField(name);f.setAccessible(true);require(f.getBoolean(cache),"transaction_flag_changed");}
    }
    static void completion(boolean rollback)throws Exception{
        CacheHarness cache=(CacheHarness)cache();
        for(String name:MAPS)map(cache,name).put("primed",new Object());
        RealmCacheSession.class.getMethod("clearManagedModels").invoke(cache);
        Method method=RealmCacheSession.class.getDeclaredMethod("getAfterTransaction");method.setAccessible(true);
        KeycloakTransaction callback=(KeycloakTransaction)method.invoke(cache);
        if(rollback)callback.rollback();else callback.commit();
        require(cache.dispatched==1,"completion_did_not_dispatch_pending_invalidations");
        require(((ManagerHarness)cache.getCache()).ended==1,"completion_did_not_end_revision_batch");
        require(!callback.isActive(),"completion_left_transaction_active");
        if(rollback)require(callback.getRollbackOnly(),"rollback_marker_lost");
        System.out.println("PASS pending_invalidations_survive_clear_and_"+(rollback?"rollback":"commit"));
    }
    static void runOrder(boolean patched,List<String> names,boolean noCache,boolean failMigration)throws Exception{
        RealmCacheSession realCache=cache();AtomicInteger epoch=new AtomicInteger(),clears=new AtomicInteger(),writes=new AtomicInteger();
        Map<String,Object> attrs=new HashMap<>();RealmModel[] contextRealm={null};
        KeycloakContext context=proxy(KeycloakContext.class,(o,m,a)->{if(m.getName().equals("getRealm"))return contextRealm[0];if(m.getName().equals("setRealm")){contextRealm[0]=(RealmModel)a[0];return null;}return ordinary(o,m,a);});
        java.util.function.Function<String,RealmModel> realm=name->{int born=epoch.get();return proxy(RealmModel.class,(o,m,a)->switch(m.getName()){
            case "getName","getId"->name;
            case "getComponentsStream"->{if(born!=epoch.get())throw new LazyInitializationException("fixture detached component collection");yield Stream.empty();}
            default->ordinary(o,m,a);
        });};
        CacheRealmProvider cacheApi=proxy(CacheRealmProvider.class,(o,m,a)->{
            if(m.getName().equals("clearManagedModels")){RealmCacheSession.class.getMethod("clearManagedModels").invoke(realCache);clears.incrementAndGet();return null;}
            if(m.getName().equals("clear"))throw new IllegalStateException("global_cache_clear_forbidden");
            return ordinary(o,m,a);
        });
        RealmProvider realms=proxy(RealmProvider.class,(o,m,a)->{
            if(m.getName().equals("getRealmsStream"))return names.stream().map(realm);
            if(m.getName().equals("getRealmByName"))return noCache?realm.apply((String)a[0]):map(realCache,"managedRealms").computeIfAbsent((String)a[0],x->realm.apply(x));
            return ordinary(o,m,a);
        });
        KeycloakSession session=proxy(KeycloakSession.class,(o,m,a)->switch(m.getName()){
            case "realms"->realms;case "getContext"->context;
            case "getProvider"->a[0]==CacheRealmProvider.class&&!noCache?cacheApi:null;
            case "getAttribute"->attrs.get((String)a[0]);case "setAttribute"->{attrs.put((String)a[0],a[1]);yield null;}
            default->ordinary(o,m,a);
        });
        EntityManager em=proxy(EntityManager.class,(o,m,a)->switch(m.getName()){
            case "isOpen"->true;case "flush"->null;case "clear"->{epoch.incrementAndGet();yield null;}
            default->ordinary(o,m,a);
        });
        Constructor<EntityManagerProxy> ctor=EntityManagerProxy.class.getDeclaredConstructor(EntityManager.class,Set.class,boolean.class,int.class);ctor.setAccessible(true);
        Set<EntityManagerProxy> proxies=new HashSet<>();proxies.add(ctor.newInstance(em,proxies,false,1));attrs.put("ENTITY_MANAGER_PROXIES",proxies);
        for(String name:names)map(realCache,"managedRealms").put(name,realm.apply(name));
        for(String name:MAPS.subList(1,MAPS.size()))map(realCache,name).put("primed",new Object());
        boolean stale=false,expectedFailure=false;
        RealmMigration migration=new RealmMigration(){
            public ModelVersion getVersion(){return new ModelVersion("999.0.0");}
            public void migrateRealm(KeycloakSession s,RealmModel current){
                current.getComponentsStream().count();s.realms().getRealmByName("master").getComponentsStream().count();
                writes.incrementAndGet();if(failMigration)throw new IllegalStateException("fixture_after_model_write");
            }
        };
        try{migration.migrate(session);}catch(LazyInitializationException e){stale=true;}catch(IllegalStateException e){if(!e.getMessage().equals("fixture_after_model_write"))throw e;expectedFailure=true;}
        if(failMigration){require(expectedFailure,"migration_failure_not_propagated");require(contextRealm[0]==null,"context_not_restored_after_failure");}
        else if(patched||noCache){require(!stale&&writes.get()==names.size(),"stale_adapter_after_flush");}
        else require(stale&&writes.get()==0,"baseline_did_not_reproduce_detached_adapter");
        if(patched&&!noCache){require(clears.get()>0,"actual_clear_not_called");for(String name:MAPS.subList(1,MAPS.size()))require(map(realCache,name).isEmpty(),"related_managed_map_not_cleared");}
        preserved(realCache);
        System.out.println("PASS "+(patched?"patched":"baseline")+"_order_"+String.join("_",names)+(noCache?"_without_cache":"")+(failMigration?"_failure_propagates":""));
    }
    public static void main(String[] args)throws Exception{
        boolean patched=args[0].equals("patched");
        runOrder(patched,List.of("master","application"),false,false);
        runOrder(patched,List.of("application","master"),false,false);
        runOrder(patched,List.of("application","master"),true,false);
        if(patched){runOrder(true,List.of("application","master"),false,true);completion(false);completion(true);}
        System.out.println("PASS boundary unit checks complete; actual database integration is separate");
    }
}
