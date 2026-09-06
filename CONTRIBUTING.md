# Contributing

Bug fixes, focused improvements, and documentation corrections are welcome.
For a substantial API or scope change, open an issue to discuss the use case
before investing in an implementation. Report vulnerabilities through
[SECURITY.md](SECURITY.md).

## Make a change

Fork the repository, create a branch from `main`, and keep your pull request
focused on one problem. Explain the behavior being changed and how you checked
it. Add a focused regression test when changing behavior. The `build` CI check
must pass before merging into `main`.

Keep `core/` JDK-only and framework integration in `adapters/`. The WebFlux
adapter connects framework requests to core contracts; product-specific tools,
storage, and application wiring belong in consuming projects. See the
[module map](docs/MODULES.md) for the existing boundaries.

Update Javadocs and relevant examples when changing the public API. Include
consumer-visible changes and migration guidance in the
[release notes](docs/RELEASE_NOTES.md) when needed.

## Build and test

Install JDK 25 for the build and JDK 17 for the external consumer smoke test.
Use the checked-in Gradle wrapper. The published libraries target Java 17.

Run these Bash commands from the repository root, replacing the JDK paths with
your installations. Each subshell restores your previous environment afterward.

```bash
(
  export JAVA_HOME="/path/to/jdk-25"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew verifyGatewayDevelopment --no-daemon --stacktrace --warning-mode fail
)
```

This runs tests and library checks, then stages both Maven publications locally.
Run the consumer smoke test against those staged artifacts:

```bash
(
  export JAVA_HOME="/path/to/jdk-17"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./bin/java17-consumer-smoke.sh
)
```

On Windows, use `gradlew.bat` for the Gradle command. The smoke script requires
Bash, such as Git Bash, with JDK 17 selected as above.

## Documentation

For documentation or site changes, use Node.js 24 and run from the repository root:

```bash
npm --prefix docs-site ci
npm --prefix docs-site run build
```

The site build syncs source pages from `docs/` and `SECURITY.md`; edit those
source files rather than their generated copies.

## Working together

Keep discussions respectful, specific, and constructive.
Contributions use the same [Apache License 2.0](LICENSE) as the project.
