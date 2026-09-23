# Central consumer

A consumer project that builds the README's install lines from Maven Central and Google's
repository alone. It never sees this checkout's bytes, only its plugin versions.

Run it through the script, which reads the install lines from the README and uses an empty Gradle
cache:

```bash
./scripts/verify-central-consumer.sh
```
