import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.util.Locale

plugins {
    application
    alias(libs.plugins.gitSemVer)
    alias(libs.plugins.collektive)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.qa)
    alias(libs.plugins.multiJvmTesting)
    alias(libs.plugins.taskTree)
}

plugins.withType<DetektPlugin> {
    val detektTasks =
        tasks
            .withType<Detekt>()
            .matching { task ->
                task.name.let { it.endsWith("Main") || it.endsWith("Test") } &&
                    !task.name.contains("Baseline")
            }
    val check by tasks.getting
    val detektAll by tasks.registering {
        group = "verification"
        check.dependsOn(this)
        dependsOn(detektTasks)
    }
}
// Enforce the use of the Kotlin version in all subprojects
configurations.matching { it.name != "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(
                project.libs.versions.kotlin
                    .get(),
            )
        }
    }
}

repositories {
    mavenCentral()
}
/*
 * Only required if you plan to use Protelis, remove otherwise
 */
sourceSets {
    main {
        dependencies {
            implementation(libs.bundles.alchemist)
            implementation(libs.bundles.collektive)
        }
        resources {
            srcDir("src/main/yaml")
        }
    }
}

multiJvm {
    jvmVersionForCompilation.set(17)
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    if (!GraphicsEnvironment.isHeadless()) {
        implementation("it.unibo.alchemist:alchemist-swingui:${libs.versions.alchemist.get()}")
    }
}

// Heap size estimation for batches
val maxHeap: Long? by project
val heap: Long =
    maxHeap ?: if (System.getProperty("os.name").lowercase().contains("linux")) {
        ByteArrayOutputStream()
            .use { output ->
                exec {
                    executable = "bash"
                    args = listOf("-c", "cat /proc/meminfo | grep MemAvailable | grep -o '[0-9]*'")
                    standardOutput = output
                }
                output.toString().trim().toLong() / 1024
            }.also { println("Detected ${it}MB RAM available.") } * 9 / 10
    } else {
        // Guess 16GB RAM of which 2 used by the OS
        14 * 1024L
    }
val taskSizeFromProject: Int? by project
val taskSize = taskSizeFromProject ?: 512
val threadCount = maxOf(1, minOf(Runtime.getRuntime().availableProcessors(), heap.toInt() / taskSize))
val alchemistGroupBatch = "Run batch simulations"
val alchemistGroupGraphic = "Run graphic simulations with Alchemist"

/*
 * This task is used to run all experiments in sequence
 */
val runAllGraphic by tasks.register<DefaultTask>("runAllGraphic") {
    group = alchemistGroupGraphic
    description = "Launches all simulations with the graphic subsystem enabled"
}
val runAllBatch by tasks.register<DefaultTask>("runAllBatch") {
    group = alchemistGroupBatch
    description = "Launches all experiments"
}

fun String.capitalizeString(): String =
    this.replaceFirstChar {
        if (it.isLowerCase()) {
            it.titlecase(
                Locale.getDefault(),
            )
        } else {
            it.toString()
        }
    }

/*
 * Scan the folder with the simulation files, and create a task for each one of them.
 */
File(rootProject.rootDir.path + "/src/main/yaml")
    .listFiles()
    ?.filter { it.extension == "yml" }
    ?.sortedBy { it.nameWithoutExtension }
    ?.forEach {
        fun basetask(
            name: String,
            additionalConfiguration: JavaExec.() -> Unit = {},
        ) = tasks.register<JavaExec>(name) {
            description = "Launches graphic simulation ${it.nameWithoutExtension}"
            mainClass.set("it.unibo.alchemist.Alchemist")
            classpath = sourceSets["main"].runtimeClasspath
            args("run", it.absolutePath)
            if (System.getenv("CI") == "true") {
                args("--override", "terminate: { type: AfterTime, parameters: [2] } ")
            } else {
                this.additionalConfiguration()
            }
        }
        val capitalizedName = it.nameWithoutExtension.capitalizeString()
        val graphic by basetask("run${capitalizedName}Graphic") {
            group = alchemistGroupGraphic
            args(
                "--override",
                "monitors: { type: SwingGUI, parameters: { graphics: effects/${it.nameWithoutExtension}.json } }",
                "--override",
                "launcher: { parameters: { batch: [], autoStart: false } }",
                "--verbosity",
                "error",
            )
        }
        runAllGraphic.dependsOn(graphic)
        val batch by basetask("run${capitalizedName}Batch") {
            group = alchemistGroupBatch
            description = "Launches batch experiments for $capitalizedName"
            maxHeapSize = "${minOf(heap.toInt(), Runtime.getRuntime().availableProcessors() * taskSize)}m"
            File("data").mkdirs()
            args(
                "--verbosity",
                "error",
            )
        }
        runAllBatch.dependsOn(batch)
    }

tasks.withType(KotlinCompile::class).all {
    compilerOptions {
        allWarningsAsErrors = false
    }
}
