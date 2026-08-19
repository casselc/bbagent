# A1 TUI runtime spike

Reproduces the bounded compatibility and native proof behind
`docs/architecture/0002-a1-tui-runtime.md`. This is milestone evidence, not product
code; the A1 TUI itself lives in `src/bbagent/tui`.

`deps.edn` pins bb4t's exact JLine `4.3.1` and core.async `1.8.741` rather than the
versions charm.clj requests, because the question is whether charm works against the
runtime bb4t actually compiles.

## JVM

```
cd spike
clojure -M -m bbagent.tui.spike            # interactive; press q to quit
clojure -Spath > cp.txt
python3 ../script/pty-proof.py "$PWD" java --enable-native-access=ALL-UNNAMED \
    -cp "$(cat cp.txt)" clojure.main -m bbagent.tui.spike
```

## Native

```
cd spike
mkdir -p classes
clojure -M -e '(binding [*compile-path* "classes"] (compile (quote bbagent.tui.spike)))'
clojure -Spath > cp.txt
"$GRAALVM_HOME/bin/native-image" \
  -cp "classes:../resources:$(cat cp.txt)" \
  -H:Name=spike --no-fallback --enable-preview \
  -H:+UnlockExperimentalVMOptions -H:+ForeignAPISupport -H:+SharedArenaSupport \
  --enable-native-access=ALL-UNNAMED -march=compatibility -O1 \
  --initialize-at-build-time='org.jline.utils.InfoCmp$Capability' \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  bbagent.tui.spike
python3 ../script/pty-proof.py "$PWD" ./spike
```

`script/pty-proof.py` allocates a real PTY, waits for the first frame, writes
keystrokes, resizes with `TIOCSWINSZ` plus `SIGWINCH`, sends the quit key, and exits
non-zero unless all five gates pass and the child exits `0`.

Finding every reflective call site (the native run-time failure mode) is a one-liner:

```
clojure -M -e '(binding [*warn-on-reflection* true *compile-path* "classes"] (compile (quote bbagent.tui.spike)))' 2>&1 | grep -i reflection
```
