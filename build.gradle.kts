import io.github.yuroyami.kiteplayer.buildtools.CheckKitertCouplingTask
import io.github.yuroyami.kiteplayer.buildtools.CheckPublicationReadinessTask
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.publish.maven.tasks.GenerateMavenPom

plugins {
    // Declared here with apply false so the publish plugin's shared build service is loaded by
    // one classloader for the whole build. Without this, applying it to sibling modules makes
    // publishAndReleaseToMavenCentral fail.
    alias(libs.plugins.vanniktech.publish).apply(false)
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // The plain application plugin, for :kiteplayer-sample-android only (S1.c.6 step 1).
    alias(libs.plugins.android.application).apply(false)
    // Applied at the root so dokkaGenerate aggregates every library module into one API site
    // at build/dokka/html.
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.0.1"
}

val kitertProjects = subprojects
    .filterNot { it.path in CheckKitertCouplingTask.EXCLUDED_MODULE_PATHS }
    .sortedBy { it.path }

tasks.register<CheckKitertCouplingTask>("checkKitertCoupling") {
    repositoryRoot.set(layout.projectDirectory)
    includedModulePaths.set(kitertProjects.map { it.path })
    sourceFiles.from(
        kitertProjects.map { project ->
            project.fileTree("src") {
                include("**/*.kt")
            }
        },
    )
    baselineFile.set(layout.projectDirectory.file("kitert-coupling-baseline.txt"))
    baselineInputs.from(
        fileTree(layout.projectDirectory) {
            include("kitert-coupling-baseline.txt")
        },
    )
}

val publicationReadiness = tasks.register<CheckPublicationReadinessTask>("checkPublicationReadiness") {
    repositoryRoot.set(layout.projectDirectory)
}

subprojects {
    val publishingProject = this
    val publishingPath = path
    val publishingDirectory = projectDir.relativeTo(rootProject.projectDir).invariantSeparatorsPath

    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        val pomTasks = publishingProject.tasks.withType(GenerateMavenPom::class.java)
        publicationReadiness.configure {
            publishingModules.add(publishingPath)
            publishingProjectDirectories.put(publishingPath, publishingDirectory)
            generatedPoms.from(pomTasks)
            generatedPoms.builtBy(pomTasks)
        }
        publishingProject.configurations.configureEach {
            dependencies.withType(ProjectDependency::class.java).configureEach {
                val siblingPath = path
                if (siblingPath != publishingPath) {
                    publicationReadiness.configure {
                        projectDependencyEdges.add(
                            CheckPublicationReadinessTask.encodeEdge(publishingPath, siblingPath),
                        )
                    }
                }
            }
        }
    }
}

dependencies {
    dokka(project(":kiteplayer-core"))
    dokka(project(":kiteplayer-subtitles"))
}

dokka {
    moduleName.set("KitePlayer")
}

// Shared Kite theme. Sources live in ../_kite-docs; docs/sync.sh copies them here, so this repo
// still builds standalone from a fresh clone.
//
// This has to be applied to every project that has Dokka, not just the root: under aggregation
// the root only renders the "all modules" landing page, and each module renders its own pages
// from its own configuration.
allprojects {
    plugins.withId("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            pluginsConfiguration.html {
                val themeCss = rootProject.layout.projectDirectory.file("docs/api-theme/kite.css")
                if (themeCss.asFile.exists()) {
                    customStyleSheets.from(themeCss)
                }
                val templates = rootProject.layout.projectDirectory.dir("dokka-templates")
                if (templates.asFile.exists()) {
                    templatesDir.set(templates)
                }
                footerMessage.set("Apache-2.0 · KitePlayer is part of the Kite family.")
            }

            dokkaSourceSets.configureEach {
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
            }
        }
    }
}
