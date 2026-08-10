plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // CompileKiteRtTask's test. buildSrc's own tests are not part of the main build's graph, so the
    // verification gate calls them explicitly with :buildSrc:test.
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
