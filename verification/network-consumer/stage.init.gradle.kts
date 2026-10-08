import org.gradle.api.publish.PublishingExtension

// A separate file repository exercises published metadata without changing consumer resolution
// or writing to Maven Local or Central. Only the probe's five library modules need this target.
gradle.beforeProject {
    if (path in setOf(":kiteplayer-core", ":kiteplayer-network", ":kiteplayer-adaptive", ":kiteplayer-subtitles", ":kiteplayer-rt")) {
        val verificationDirectory = rootProject.layout.buildDirectory.dir("verification-maven")
        pluginManager.withPlugin("maven-publish") {
            extensions.configure(PublishingExtension::class.java) {
                repositories.maven {
                    name = "Verification"
                    url = verificationDirectory.get().asFile.toURI()
                }
            }
        }
    }
}
