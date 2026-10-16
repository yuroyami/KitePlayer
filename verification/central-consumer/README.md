# Central consumer

A consumer project that builds the README's install lines against Maven Central and Google's
repository only. It never uses artifacts built from this checkout. It only shares the plugin
versions.

Run it through the script, which reads the install lines from the README and uses an empty Gradle
cache:

```bash
./scripts/verify-central-consumer.sh
```
