// Stream files or stdin and print SHA-256 checksums.
void main(String[] args) throws Exception {
    if (args.length == 0) {
        System.out.println(sha256(System.in) + "  -");
    } else {
        for (var name : args) {
            System.out.println(sha256(Files.newInputStream(Path.of(name))) + "  " + name);
        }
    }
}

String sha256(InputStream source) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var input = new DigestInputStream(source, digest)) {
        input.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
}
