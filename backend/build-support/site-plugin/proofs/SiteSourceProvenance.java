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
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
public class SiteSourceProvenance {
 private static final String ARCHIVE_SHA512 = "6d997e3c9c7c08e5d921e2d423c44c9dac428ba39ebb28b1ea2f7f57206c5b938194f31386e86fdd909a2695220f6696814710cc0067408dc938b63912109bb6";
 private static String hash(byte[] b,String alg) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(b));}
 public static void main(String[] args) throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("archive_and_module_required");
  Path archive=Path.of(args[0]).toAbsolutePath().normalize(),root=Path.of(args[1]).toAbsolutePath().normalize();
  if(!hash(Files.readAllBytes(archive),"SHA-512").equals(ARCHIVE_SHA512))throw new IllegalStateException("upstream_archive_hash_mismatch");
  Set<String> seen=new HashSet<>();int unchanged=0,changed=0;
  try(ZipFile zip=new ZipFile(archive.toFile())) {
   for(String line:Files.readAllLines(root.resolve("UPSTREAM-SOURCES.tsv"))) {
    if(line.startsWith("#")||line.isBlank())continue;
    String[] row=line.split("\t",-1);if(row.length!=3||!seen.add(row[2]))throw new IllegalStateException("source_manifest_invalid");
    Path file=root.resolve(row[2]).normalize();if(!file.startsWith(root)||!Files.isRegularFile(file))throw new IllegalStateException("source_path_invalid");
    var entry=zip.getEntry("maven-site-plugin-3.22.0/"+row[2]);if(entry==null)throw new IllegalStateException("source_not_from_archive");
    if(!hash(zip.getInputStream(entry).readAllBytes(),"SHA-256").equals(row[0]))throw new IllegalStateException("upstream_source_hash_mismatch");
    if(!hash(Files.readAllBytes(file),"SHA-256").equals(row[1]))throw new IllegalStateException("downstream_source_hash_mismatch");
    if(row[0].equals(row[1]))unchanged++;else changed++;
   }
  }
  try(var paths=Files.walk(root.resolve("src"))) {
   for(Path p:paths.filter(Files::isRegularFile).toList())if(!seen.contains(root.relativize(p).toString().replace('\\','/')))throw new IllegalStateException("unmanifested_source");
  }
  System.out.println("PROVENANCE_COMPLETE files="+seen.size()+" unchanged="+unchanged+" changed="+changed);
 }
}
