// Count logical lines and whitespace-separated words in UTF-8 text.
void main(String[] args) throws Exception {
    if (args.length > 1) {
        System.err.println("Usage: WordCount.java [file] (otherwise reads stdin)");
        System.exit(2);
    }
    var words = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);
    long lineCount = 0;
    long wordCount = 0;
    try (var reader = args.length == 0
            ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
            : Files.newBufferedReader(Path.of(args[0]))) {
        for (String line; (line = reader.readLine()) != null; ) {
            lineCount++;
            wordCount += words.matcher(line).results().count();
        }
    }
    System.out.printf("%d lines, %d words%n", lineCount, wordCount);
}
