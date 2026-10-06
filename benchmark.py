#!/usr/bin/env python3
"""Validate and benchmark every example, saving timings, environment, and a chart."""

import argparse
import datetime
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import sys
import tempfile


def capture(command):
    result = subprocess.run(command, text=True, capture_output=True, check=True)
    return (result.stdout + result.stderr).strip()


def prepare_workloads(directory):
    tree = directory / "directory"
    tree.mkdir()
    for index in range(90):
        suffix = "txt" if index % 2 == 0 else "log"
        (tree / f"file-{index:03d}.{suffix}").write_text("example\n", encoding="utf-8")
    for index in range(10):
        child = tree / f"sub-{index:02d}"
        child.mkdir()
        for file_index in range(10):
            suffix = "txt" if file_index % 2 == 0 else "log"
            (child / f"file-{file_index:02d}.{suffix}").write_text("nested example\n", encoding="utf-8")
    text = directory / "text.txt"
    text.write_text("one two three four five six seven eight nine ten\n" * 1000, encoding="utf-8")
    data = bytes(range(256)) * 4096
    binary = directory / "data.bin"
    binary.write_bytes(data)
    listing = []
    for entry in sorted(tree.iterdir()):
        kind, size = ("dir", "-") if entry.is_dir() else ("file", str(entry.stat().st_size))
        listing.append(f"{kind:<4} {size:>10}  {entry.name}\n")
    return [
        ("HelloWorld", "greeting", [], "Hello, world!\n"),
        ("ListDirectory", "100 entries", [str(tree)], "".join(listing)),
        ("WordCount", f"1,000 lines / 10,000 words / {text.stat().st_size:,} bytes",
         [str(text)], "1000 lines, 10000 words\n"),
        ("FindFiles", "190 files / 11 directories / 95 matches", ["*.txt", str(tree)],
         "".join(str(path) + "\n" for path in sorted(tree.rglob("*.txt")))),
        ("Sha256", "1 MiB", [str(binary)], hashlib.sha256(data).hexdigest() + f"  {binary}\n"),
    ]


def plot_results(results, output, runs, warmup):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    labels = ["Hello world\nGreeting", "List directory\n100 entries",
              "Word count\n1,000 lines", "Find files\n190 files", "SHA-256\n1 MiB"]
    figure, axes = plt.subplots(figsize=(10.5, 5.4), layout="constrained")
    for offset, launcher, color in [(-0.18, "Crema native", "#0f766e"),
                                    (0.18, "OpenJDK", "#475569")]:
        selected = results[0::2] if offset < 0 else results[1::2]
        means = [result["mean"] * 1000 for result in selected]
        deviations = [result["stddev"] * 1000 for result in selected]
        positions = [index + offset for index in range(len(labels))]
        axes.barh(positions, means, height=0.3, color=color, label=launcher,
                  xerr=deviations, error_kw={"capsize": 3, "elinewidth": 1, "ecolor": "#17212b"})
        for position, mean, deviation in zip(positions, means, deviations):
            axes.text(mean + deviation + 5, position, f"{mean:.1f} ms", va="center",
                      fontsize=9, color="#17212b")
    maximum = max((result["mean"] + result["stddev"]) * 1000 for result in results)
    axes.set_xlim(0, math.ceil(maximum * 1.2 / 50) * 50)
    axes.set_yticks(range(len(labels)), labels=labels, fontsize=10)
    axes.invert_yaxis()
    axes.set_xlabel("Source compilation + task execution (ms) · lower is faster", fontsize=10)
    axes.set_title(f"Java source script launch times\n"
                   f"{runs} fresh processes after {warmup} warmups · mean ± standard deviation",
                   loc="left", fontsize=13, pad=18)
    axes.legend(loc="lower right", frameon=False, fontsize=10)
    axes.set_axisbelow(True)
    axes.grid(axis="x", color="#e2e8f0", linewidth=0.7)
    axes.tick_params(axis="both", length=0)
    for spine in axes.spines.values():
        spine.set_visible(False)
    figure.savefig(output, dpi=180, facecolor="white")
    plt.close(figure)


def main():
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk-home", default=os.environ.get("JDK_HOME"),
                        help="regular JDK installation (or set JDK_HOME)")
    parser.add_argument("--runs", type=int, default=50)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--output", type=Path, default=root / "benchmarks" / "latest")
    args = parser.parse_args()
    args.output = args.output.resolve()
    if not args.jdk_home:
        parser.error("set JDK_HOME or pass --jdk-home for the regular JDK baseline")
    if args.runs < 2 or args.warmup < 0:
        parser.error("--runs must be at least 2 and --warmup must be nonnegative")
    hyperfine = shutil.which("hyperfine")
    if not hyperfine:
        parser.error("install hyperfine (macOS: brew install hyperfine)")
    if importlib.util.find_spec("matplotlib") is None:
        parser.error("install plotting dependencies: python -m pip install -r requirements.txt")
    home_file = root / "build" / "graalvm-home.txt"
    if not home_file.is_file():
        parser.error("build the launcher first with ./build.sh")
    graalvm = Path(home_file.read_text().strip())
    jdk = Path(args.jdk_home).resolve()
    # Prevent pre-existing class files from accidentally changing the workload.
    if list((root / "examples").glob("*.class")):
        parser.error("remove .class files from examples/ before benchmarking")
    # Each run gets a fresh, fixed-size dataset outside the measured commands.
    inputs = root / "build" / "benchmark-inputs"
    inputs.mkdir(parents=True, exist_ok=True)
    workloads = prepare_workloads(Path(tempfile.mkdtemp(prefix="run-", dir=inputs)))
    launchers = [
        ("Crema", [str(root / "build" / "crema"), f"-Djava.home={graalvm}"]),
        ("OpenJDK", [str(jdk / "bin" / "java")]),
    ]
    commands = []
    for script, _, arguments, expected in workloads:
        for launcher, prefix in launchers:
            name = f"{script} / {launcher}"
            command = prefix + [str(root / "examples" / f"{script}.java")] + arguments
            result = subprocess.run(command, text=True, capture_output=True, check=True, timeout=30)
            if result.stdout != expected or result.stderr:
                raise RuntimeError(f"Unexpected output from {name}: {result.stdout!r} {result.stderr!r}")
            commands.append((name, command))
        print(f"Validated {script} on Crema and OpenJDK", flush=True)
    args.output.mkdir(parents=True, exist_ok=True)
    benchmark = [hyperfine, "--shell=none", "--warmup", str(args.warmup),
                 "--runs", str(args.runs), "--time-unit", "millisecond",
                 "--export-json", str(args.output / "timings.json")]
    for name, command in commands:
        benchmark += ["--command-name", name, shlex.join(command)]
    metadata = {
        "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "platform": platform.platform(),
        "architecture": platform.machine(),
        "logical_cpus": os.cpu_count(),
        "graalvm": capture([str(graalvm / "bin" / "java"), "-version"]),
        "regular_jdk": capture([str(jdk / "bin" / "java"), "-version"]),
        "native_image": capture([str(graalvm / "bin" / "native-image"), "--version"]),
        "hyperfine": capture([hyperfine, "--version"]),
        "warmup_runs_per_command": args.warmup,
        "measured_runs_per_command": args.runs,
        "commands": {name: command for name, command in commands},
        "workloads": {script: description for script, description, _, _ in workloads},
        "binary_bytes": (root / "build" / "crema").stat().st_size,
        "method": "Fresh processes, warm filesystem caches, no shell; wall time through process exit, including compilation and execution.",
    }
    subprocess.run(benchmark, check=True, cwd=root)
    results = json.loads((args.output / "timings.json").read_text())["results"]
    (args.output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    plot_results(results, args.output / "benchmark.png", args.runs, args.warmup)
    print("\n| Script | Input | Crema mean ± σ | OpenJDK mean ± σ | Speedup |")
    print("| --- | --- | ---: | ---: | ---: |")
    for index, (script, description, _, _) in enumerate(workloads):
        native, regular = results[index * 2:index * 2 + 2]
        print(f"| {script} | {description} | {native['mean'] * 1000:.2f} ± {native['stddev'] * 1000:.2f} ms | "
              f"{regular['mean'] * 1000:.2f} ± {regular['stddev'] * 1000:.2f} ms | "
              f"{regular['mean'] / native['mean']:.2f}× |")
    print(f"\nTimings, environment, and chart saved to {args.output}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        sys.exit(str(error))
