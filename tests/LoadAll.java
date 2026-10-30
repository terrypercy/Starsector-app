import java.util.*; import java.util.zip.*;
public class LoadAll { public static void main(String[] a) throws Exception {
  int ok=0, bad=0;
  try (ZipFile z = new ZipFile(a[0])) { for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
    String n = e.nextElement().getName(); if (!n.endsWith(".class")) continue;
    String cn = n.replace('/', '.').substring(0, n.length()-6);
    try { Class<?> c = Class.forName(cn, false, LoadAll.class.getClassLoader()); c.getDeclaredMethods(); c.getDeclaredFields(); ok++; }
    catch (Throwable t) { bad++; System.out.println("cannot load " + cn + ": " + t); } } }
  System.out.println("loaded " + ok + ", failed " + bad); } }
