package org.bvnk.slackbot.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

public class NativeFeature implements Feature {
  private static final String[] packagesToInclude = {
    "com.slack.api.methods.request", "com.slack.api.methods.response", "com.slack.api.model"
  };

  private static final List<String> classesToExclude =
      List.of(
          "com.slack.api.methods.response.admin.analytics.AdminAnalyticsGetFileResponse",
          "com.slack.api.methods.response.admin.users.unsupported_versions.AdminUsersUnsupportedVersionsExportResponse");

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    Stream.of(packagesToInclude)
        .forEach(
            packageName -> {
              scanPackage(packageName).stream()
                  .filter(pkg -> !classesToExclude.contains(pkg))
                  .forEach(
                      className -> {
                        try {
                          Class<?> clazz = Class.forName(className);
                          RuntimeReflection.register(clazz);
                          RuntimeReflection.register(clazz.getDeclaredMethods());
                          RuntimeReflection.register(clazz.getDeclaredFields());
                          RuntimeReflection.register(clazz.getDeclaredConstructors());
                        } catch (ClassNotFoundException e) {
                          System.err.println(e.getMessage());
                        }
                      });
            });
  }

  @SneakyThrows
  public static Set<String> scanPackage(String pkg) {
    String pkgPath = pkg.replace('.', '/');
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    Enumeration<URL> urls = cl.getResources(pkgPath);
    Set<String> classes = new HashSet<>();

    while (urls.hasMoreElements()) {
      URL url = urls.nextElement();
      String protocol = url.getProtocol();
      if ("file".equals(protocol)) {
        Path dir = Paths.get(url.toURI());
        try (Stream<Path> stream = Files.walk(dir)) {
          stream
              .filter(p -> p.toString().endsWith(".class"))
              .forEach(
                  p -> {
                    Path rel = dir.relativize(p);
                    String className =
                        rel.toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
                    classes.add(pkg + "." + className);
                  });
        } catch (Exception e) {
          throw new IOException(e);
        }
      } else if ("jar".equals(protocol)) {
        String path = url.getPath(); // e.g. file:/.../your.jar!/com/example
        String jarPath = path.substring(5, path.indexOf("!")); // strip "file:" and "!/..."
        try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))) {
          Enumeration<JarEntry> entries = jar.entries();
          while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(pkgPath) && name.endsWith(".class") && !entry.isDirectory()) {
              String className = name.replace('/', '.').replaceAll("\\.class$", "");
              classes.add(className);
            }
          }
        }
      } else {
        // fallback: try to open stream and scan (rare)
        try (InputStream is = url.openStream()) {
          // not practical for classes; skip
        } catch (Exception ignored) {
        }
      }
    }
    return classes;
  }
}
