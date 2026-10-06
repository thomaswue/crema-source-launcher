# Crema launcher

A GraalVM native executable named `crema` that runs Java source files,
executable jars, and class files. It includes `javac` for source files; Crema
loads the resulting bytecode and bytecode from jars or class files at runtime.

Preserves all of `java.base` (`-H:Preserve=module=java.base`). Other JDK classes
can be loaded dynamically. Classes already included in the image may require
preservation if runtime-loaded code accesses members removed during the build.

## Build

Requires a C toolchain (Xcode Command Line Tools on macOS).

```sh
sdk use java 25.4.4+1-graal
./build.sh
./run.sh examples/HelloWorld.java
```

Prints `Hello, world!`. Keep `build/`, including its companion libraries, and
the selected JDK available at runtime.

## Launch files

The first argument is a `.java`, `.jar`, or `.class` file, or a class name whose
compiled file is on the current directory class path. Remaining arguments are
passed to its main method.

```sh
./run.sh examples/HelloWorld.java

# Compile a class file, then launch it directly.
javac -d build/classes examples/HelloWorld.java
./run.sh build/classes/HelloWorld.class

# The native executable can also be called directly for jars and class files.
build/crema build/classes/HelloWorld.class

# A bare class name resolves from the current directory (or its package path).
(cd examples && ../run.sh FindFiles '*.java' .)

# The jar manifest must name its entry point with Main-Class.
jar --create --file build/hello.jar --main-class HelloWorld -C build/classes HelloWorld.class
./run.sh build/hello.jar
```

For a packaged class file, keep it in its package directory (for example,
`build/classes/com/example/Main.class`). The launcher uses the containing class
path root to find sibling classes and resources. Jar manifest `Class-Path`
entries can name additional jars. Jar and class entry points may use a public
static main method or Java's compact instance main form.

## Examples

```sh
./run.sh examples/ListDirectory.java .
./run.sh examples/WordCount.java README.md
./run.sh examples/FindFiles.java '*.java' examples
./run.sh examples/Sha256.java README.md
```

`WordCount` and `Sha256` also read stdin when no file is given.

## Benchmark

Requires Python 3 and [hyperfine](https://github.com/sharkdp/hyperfine). Runs all
five examples against OpenJDK and regenerates the chart and raw measurements.

```sh
python3 -m venv build/benchmark-venv
build/benchmark-venv/bin/python -m pip install -r requirements.txt
build/benchmark-venv/bin/python benchmark.py --jdk-home "$HOME/.sdkman/candidates/java/25.0.1-open"
```

Crema measured **5.47–7.30× faster** on macOS ARM64: 50 fresh processes per script
and launcher after 5 warmups. Times include source compilation and task execution
with warm filesystem caches.

![Source script benchmark: Crema versus OpenJDK](benchmarks/latest/benchmark.png)

[Raw timings](benchmarks/latest/timings.json) · [Environment](benchmarks/latest/environment.json)
