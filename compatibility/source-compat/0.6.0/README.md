# 0.6.0 Core Source Compatibility Fixture

This fixture preserves the framework-neutral core usage from the `0.6.0`
external-consumer source. It must compile against each newly staged core
artifact without importing project source, test helpers, package-private types,
or local build output.

The old WebFlux portion is intentionally excluded as of `0.8.0`: the accepted
Jackson 2 `ObjectMapper` to Jackson 3 `JsonMapper` constructor change is not
source-compatible. Current adapter wiring is instead compiled and executed by
`bin/java17-consumer-smoke.sh`, while the API snapshot gate accounts for every
approved constructor removal and replacement.

Run it through:

```bash
./bin/java17-source-compat-0.6-consumer.sh
```

The script copies this source into a clean temporary Gradle project and resolves
`mcp-gateway-core` exclusively from the staged publication repository.
