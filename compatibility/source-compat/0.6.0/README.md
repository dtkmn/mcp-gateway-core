# 0.6.0 Source Compatibility Fixture

This fixture is a frozen external-consumer source file from the `0.6.0`
public-preview API surface. It must compile against newly staged artifacts
without importing project source, test helpers, package-private types, or local
build output.

Run it through:

```bash
./bin/java17-source-compat-0.6-consumer.sh
```

The script copies this source into a clean temporary Gradle project and resolves
`io.github.dtkmn` artifacts exclusively from the staged publication repository.
