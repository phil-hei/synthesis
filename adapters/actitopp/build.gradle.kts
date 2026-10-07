plugins { kotlin("jvm") version "2.3.0"; application }
repositories { mavenCentral() }
kotlin { jvmToolchain(25); compilerOptions { freeCompilerArgs.add("-Xcontext-parameters") } }
dependencies { implementation(project(":kit-core")) }
application { mainClass.set("de.synthesis.kit.actitopp.HouseholdRunnerKt") }

distributions.main { contents { from("${providers.gradleProperty("kitSourceDir").get()}/LICENSE") { into("licenses/actiToppNG") } } }
