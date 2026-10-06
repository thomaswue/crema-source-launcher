import com.sun.tools.javac.launcher.SourceLauncher;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Native entry point for Java source files, executable jars, and class files. */
public final class CremaLauncher {
    public static void main(String[] args) throws Throwable {
        // Native Image can retain an empty module-path property. The JDK treats
        // that as the current directory and scans it during service discovery.
        if ("".equals(System.getProperty("jdk.module.path"))) {
            System.clearProperty("jdk.module.path");
        }

        if (args.length == 0) {
            System.err.println("Usage: crema <file.java|file.jar|file.class|ClassName> [args...]");
            System.exit(1);
        }

        String input = args[0];
        if (input.endsWith(".java")) {
            SourceLauncher.main(args);
            return;
        }

        try {
            Path file = Path.of(input).toAbsolutePath().normalize();
            String[] programArgs = Arrays.copyOfRange(args, 1, args.length);
            if (input.endsWith(".jar") && Files.isRegularFile(file)) {
                launchJar(file, programArgs);
            } else if (input.endsWith(".class") && Files.isRegularFile(file)) {
                launchClass(file, programArgs);
            } else if (!input.endsWith(".jar") && !input.endsWith(".class")) {
                Path classFile = findClassFile(input);
                if (classFile == null) {
                    throw new LaunchException("Class file not found: " + input);
                }
                launchClass(classFile, programArgs);
            } else {
                throw new LaunchException("File not found: " + input);
            }
        } catch (LaunchException e) {
            System.err.println("crema: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Resolves both a path without its .class suffix and a Java binary name. */
    private static Path findClassFile(String input) {
        Path pathCandidate = Path.of(input + ".class").toAbsolutePath().normalize();
        if (Files.isRegularFile(pathCandidate)) {
            return pathCandidate;
        }
        if (input.indexOf('/') >= 0 || input.indexOf('\\') >= 0) {
            return null;
        }
        Path nameCandidate = Path.of(input.replace('.', '/') + ".class")
                .toAbsolutePath().normalize();
        return Files.isRegularFile(nameCandidate) ? nameCandidate : null;
    }

    private static void launchJar(Path file, String[] args) throws Throwable {
        String mainClassName;
        try (JarFile jar = new JarFile(file.toFile())) {
            Manifest manifest = jar.getManifest();
            mainClassName = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        }
        if (mainClassName == null || mainClassName.isBlank()) {
            throw new LaunchException("Jar has no Main-Class manifest entry: " + file);
        }

        URLClassLoader loader = new URLClassLoader(new URL[] { file.toUri().toURL() },
                ClassLoader.getSystemClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> mainClass = Class.forName(mainClassName.trim(), false, loader);
        invokeMain(mainClass, args);
    }

    private static void launchClass(Path file, String[] args) throws Throwable {
        byte[] bytes = Files.readAllBytes(file);
        ClassFileLoader loader = new ClassFileLoader();
        Class<?> mainClass = loader.defineMainClass(bytes);
        loader.addClassPathRoot(classPathRoot(file, mainClass.getName()));
        Thread.currentThread().setContextClassLoader(loader);
        invokeMain(mainClass, args);
    }

    private static Path classPathRoot(Path file, String className) {
        Path root = file.getParent();
        int packageEnd = className.lastIndexOf('.');
        if (packageEnd < 0) {
            return root;
        }
        String[] packageParts = className.substring(0, packageEnd).split("\\.");
        Path candidate = root;
        for (int i = packageParts.length - 1; i >= 0; i--) {
            if (candidate == null || candidate.getFileName() == null
                    || !candidate.getFileName().toString().equals(packageParts[i])) {
                return root;
            }
            candidate = candidate.getParent();
        }
        return candidate;
    }

    private static void invokeMain(Class<?> mainClass, String[] args) throws Throwable {
        Method main = findMainMethod(mainClass, String[].class);
        boolean takesArguments = main != null;
        if (main == null) {
            main = findMainMethod(mainClass);
        }
        if (main == null) {
            throw new LaunchException("No valid main method in " + mainClass.getName());
        }
        if (!Modifier.isStatic(main.getModifiers())) {
            Constructor<?> constructor;
            try {
                constructor = mainClass.getDeclaredConstructor();
                constructor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new LaunchException("No default constructor in " + mainClass.getName());
            }
            main.setAccessible(true);
            Object target = constructor.newInstance();
            invoke(main, target, takesArguments ? new Object[] { args } : new Object[0]);
        } else {
            invoke(main, null, takesArguments ? new Object[] { args } : new Object[0]);
        }
    }

    private static Method findMainMethod(Class<?> type, Class<?>... parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("main", parameterTypes);
                if (method.getReturnType() == void.class
                        && !Modifier.isPrivate(method.getModifiers())) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Continue through the class hierarchy.
            }
        }
        return null;
    }

    private static void invoke(Method main, Object target, Object[] args) throws Throwable {
        try {
            main.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class ClassFileLoader extends URLClassLoader {
        ClassFileLoader() {
            super(new URL[0], ClassLoader.getSystemClassLoader());
        }

        Class<?> defineMainClass(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }

        void addClassPathRoot(Path root) throws IOException {
            addURL(root.toUri().toURL());
        }
    }

    private static final class LaunchException extends Exception {
        LaunchException(String message) {
            super(message);
        }
    }
}
