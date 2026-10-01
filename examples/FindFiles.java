// Find files recursively by a filename glob, without following symbolic links.
void main(String[] args) throws Exception {
    if (args.length > 2) {
        System.err.println("Usage: FindFiles.java [glob] [directory]");
        System.exit(2);
    }
    var matcher = FileSystems.getDefault().getPathMatcher("glob:" + (args.length == 0 ? "*" : args[0]));
    var directory = Path.of(args.length < 2 ? "." : args[1]);
    try (var files = Files.walk(directory)) {
        files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> matcher.matches(path.getFileName()))
                .sorted()
                .forEach(System.out::println);
    }
}
