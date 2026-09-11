package com.hunt.otziv.whatsapp;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Prevents new production flows from accidentally using the rejecting legacy send adapters. */
class WhatsAppProducerIdentityContractTest {
    @Test void everyBusinessProducerUsesAnExplicitOperationIdentity() throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=Files.walk(Path.of("src/main/java"));var manager=compiler.getStandardFileManager(null,null,null)) {
            var sources=files.filter(p->p.toString().endsWith(".java")).toList();
            JavacTask task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,
                    manager.getJavaFileObjectsFromPaths(sources));
            List<String> violations=new ArrayList<>();
            Map<String,Integer> callSites = new TreeMap<>();
            for(var unit:task.parse()) {
                String path=unit.getSourceFile().getName();
                if(path.endsWith("ClientChatMessageSender.java")||path.endsWith("WhatsAppServiceImpl.java"))continue;
                Set<String> whatsapp=new HashSet<>(),delivery=new HashSet<>();
                new TreeScanner<Void,Void>() {
                    @Override public Void visitVariable(VariableTree node,Void unused) {
                        String type=node.getType()==null?"":node.getType().toString();
                        if(type.endsWith("WhatsAppService"))whatsapp.add(node.getName().toString());
                        if(type.endsWith("ClientChatMessageSender")||type.endsWith("ClientMessageDelivery"))delivery.add(node.getName().toString());
                        return super.visitVariable(node,unused);
                    }
                }.scan(unit,null);
                new TreeScanner<Void,Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree node,Void unused) {
                        if(node.getMethodSelect() instanceof MemberSelectTree call) {
                            String receiver=call.getExpression().toString().replaceFirst("^this\\.","");
                            String name=call.getIdentifier().toString();int count=node.getArguments().size();
                            boolean low=whatsapp.contains(receiver)&&Set.of("sendMessage","sendMessageToGroup").contains(name);
                            boolean high=delivery.contains(receiver)&&Set.of("send","sendToPlatform","sendWithOperationId","sendToPlatformWithOperationId","sendPublicationProgressWithOperationId","deliverWithOperationId","deliverPublicationProgressWithOperationId").contains(name);
                            if (low || high) callSites.merge(path.substring(path.indexOf("com")) + ":" + name, 1, Integer::sum);
                            if((low&&count==3)||(high&&Set.of("send","sendToPlatform").contains(name))
                                    ||((low||high)&&count>0&&node.getArguments().getLast().getKind()==Tree.Kind.NULL_LITERAL))
                                violations.add(path+":"+name+" has no durable identity");
                        }
                        return super.visitMethodInvocation(node,unused);
                    }
                }.scan(unit,null);
            }
            assertThat(violations).isEmpty();
            System.out.println("Production keyed send callsites: " + callSites);
        }
    }
}
