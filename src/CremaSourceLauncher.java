import com.sun.tools.javac.launcher.SourceLauncher;

/** Native entry point delegating compilation and execution to the JDK launcher. */
public final class CremaSourceLauncher {
    public static void main(String[] args) throws Throwable {
        // Native Image can retain an empty module-path property. The JDK treats
        // that as the current directory and scans it during service discovery.
        // An absent module path should remain absent; retain explicit paths.
        if ("".equals(System.getProperty("jdk.module.path"))) {
            System.clearProperty("jdk.module.path");
        }
        SourceLauncher.main(args);
    }
}
