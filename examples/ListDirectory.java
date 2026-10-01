// List entries, including hidden files, without following symbolic links.
void main(String[] args) throws Exception {
    if (args.length > 1) {
        System.err.println("Usage: ListDirectory.java [directory]");
        System.exit(2);
    }
    var directory = Path.of(args.length == 0 ? "." : args[0]);
    try (var entries = Files.list(directory)) {
        for (var entry : entries.sorted().toList()) {
            var attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var type = attributes.isDirectory() ? "dir" : attributes.isSymbolicLink() ? "link" : "file";
            var size = attributes.isDirectory() ? "-" : Long.toString(attributes.size());
            System.out.printf("%-4s %10s  %s%n", type, size, entry.getFileName());
        }
    }
}
