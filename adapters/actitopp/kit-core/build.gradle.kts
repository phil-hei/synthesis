plugins { kotlin("jvm") version "2.3.0"; kotlin("plugin.serialization") version "2.3.0" }
repositories { mavenLocal(); mavenCentral(); maven("https://packages.jetbrains.team/maven/p/kds/kotlin-ds-maven") }
kotlin { jvmToolchain(25); compilerOptions { freeCompilerArgs.add("-Xcontext-parameters") } }
val kitSource = providers.gradleProperty("kitSourceDir").get()
sourceSets.main { kotlin.setSrcDirs(listOf("$kitSource/src/main/kotlin")); resources.setSrcDirs(listOf("$kitSource/src/main/resources")) }
dependencies {
 api("edu.kit.ifv.mobitopp:kotlin-units:1.0.0")
 api("edu.kit.ifv.mobitopp:discrete-choice:1.1.0")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

java { withSourcesJar() }
tasks.processResources { from("$kitSource/LICENSE") { into("META-INF/actiToppNG") } }
