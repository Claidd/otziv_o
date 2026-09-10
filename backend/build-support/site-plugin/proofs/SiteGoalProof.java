/*
 * Copyright 2026 Otziv contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.cli.MavenCli;
public class SiteGoalProof {
 static int checks;
 static void require(boolean v,String name) { if(!v) throw new AssertionError(name);checks++;System.out.println("PROOF_CHECK "+name); }
 static HttpResponse<String> get(int port,String path) throws Exception { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(4)).build(),HttpResponse.BodyHandlers.ofString()); }
 public static void main(String[] a) {
  try { run(a);System.out.println("PROOF_COMPLETE checks="+checks);System.exit(0); }
  catch(Throwable e) {e.printStackTrace();System.exit(1);}
 }
 static void run(String[] a) throws Exception {
  Path fixture=Path.of(a[0]).toAbsolutePath();boolean fixed=Boolean.parseBoolean(a[1]);int port;
  try(ServerSocket p=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) {port=p.getLocalPort();}
  Files.writeString(fixture.resolve("src/site/markdown/index.md"),"# Site security fixture\n\nORIGINAL_CONTENT\n\n[Second document](second.html)\n");
  String coords=fixed?"org.apache.maven.plugins:maven-site-plugin:3.22.0-otziv-jetty12.0.39-2":"org.apache.maven.plugins:maven-site-plugin:3.22.0";
  AtomicInteger exit=new AtomicInteger(-99);System.setProperty("maven.multiModuleProjectDirectory",fixture.toString());
  Thread goal=new Thread(()->exit.set(new MavenCli().doMain(new String[]{"-B","-ntp","-X","-s",fixture.resolve("proof-settings.xml").toString(),"-gs",fixture.resolve("proof-settings.xml").toString(),"-Dhost=127.0.0.1","-Dport="+port,coords+":run"},fixture.toString(),System.out,System.err)),"actual-maven-site-run");goal.start();
  HttpResponse<String> page=null;long until=System.nanoTime()+Duration.ofSeconds(90).toNanos();
  while(System.nanoTime()<until&&goal.isAlive()) { try {page=get(port,"/");if(page.statusCode()==200)break;}catch(Exception ignored){}Thread.sleep(200); }
  require(page!=null&&page.statusCode()==200&&page.body().contains("ORIGINAL_CONTENT"),"real_site_run_renders_markdown");
  require(get(port,"/second.html").body().contains("INDEPENDENT_SECOND_DOCUMENT"),"second_document");
  require(get(port,"/check.txt").body().contains("STATIC_RESOURCE_CONTENT"),"static_resource");
  Thread.sleep(1100);Files.writeString(fixture.resolve("src/site/markdown/index.md"),"# Site security fixture\n\nCHANGED_LIVE_CONTENT\n");
  require(get(port,"/index.html").body().contains("CHANGED_LIVE_CONTENT"),"live_document_change");
  HttpResponse<String> missing=get(port,"/missing-document.html");require(missing.statusCode()==404,"unknown_resource_404");
  String payload="POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1;a=\"\r\nX\r\n0\r\n\r\nGET /second.html HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\n\r\n\"\r\nY\r\n0\r\n\r\n";
  String response; boolean eof = true;
  try(Socket sock=new Socket("127.0.0.1",port)) {sock.setSoTimeout(2000);sock.getOutputStream().write(payload.getBytes(StandardCharsets.ISO_8859_1));var out=new java.io.ByteArrayOutputStream();try {sock.getInputStream().transferTo(out);}catch(SocketTimeoutException expected){eof=false;}response=out.toString(StandardCharsets.ISO_8859_1);}
  int responses=response.split("HTTP/1.1",-1).length-1;System.out.println("PROOF_CHUNK responses="+responses+" eof="+eof+" firstStatus="+response.lines().findFirst().orElse("NONE"));
  if(fixed) require(responses==1&&eof&&!response.contains("INDEPENDENT_SECOND_DOCUMENT"),"cve_2026_2332_rejected_before_second_request");
  else require(responses>=2,"baseline_cve_2026_2332_accepts_second_request");
  require(goal.isAlive()&&exit.get()==-99,"maven_goal_active_until_jvm_shutdown");
 }
}
