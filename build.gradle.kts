import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    // Minecraft 26.x ships unobfuscated, which stock Loom's mapping lookup does not handle on its
    // own; this supplies the official Mojang mappings for it.
    id("dev.kikugie.loom-back-compat")
    id("org.jetbrains.kotlin.jvm")
}

version = "${property("mod_version")}+${property("minecraft_version")}"
group = property("maven_group")!!

base {
    archivesName = property("archives_base_name") as String
}

repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com/releases/") {
        name = "Terraformers"
        content { includeGroupAndSubgroups("com.terraformersmc") }
    }
    // Litematica and MaLiLib, for compiling the block substitution hooks against: CurseForge's files,
    // the same ones a CurseForge profile installs.
    maven("https://cursemaven.com") {
        name = "CurseMaven"
        content { includeGroup("curse.maven") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // Only for the settings screen behind Mod Menu's cog. Fabric loads a `modmenu` entrypoint
    // solely when Mod Menu asks for it, so nothing here is needed at runtime without it.
    modCompileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}")
    // Compile-time only: the block substitution hooks name Litematica's classes. Litematica is
    // required at runtime anyway, and every hook is optional, so a Litematica that has moved on
    // only turns substitution off.
    modCompileOnly("curse.maven:litematica-${property("litematica_curse_project")}:${property("litematica_curse_file")}")
    modCompileOnly("curse.maven:malilib-${property("malilib_curse_project")}:${property("malilib_curse_file")}")
}

// Minecraft 26.3 is built for Java 25, and the mixins here weave into its classes, so the mod has
// to be compiled for the same release rather than an older one.
val javaVersion = JavaVersion.VERSION_25

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion.majorVersion.toInt()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    withSourcesJar()
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

// Resolved here rather than inside the task, where `property` would look at the task itself.
val resourceProperties = mapOf(
    "version" to version,
    "minecraft_version" to property("minecraft_version"),
    "loader_version" to property("loader_version"),
    "fabric_kotlin_version" to property("fabric_kotlin_version"),
    "java_version" to javaVersion.majorVersion,
    "litematica_version" to property("litematica_version"),
)

tasks.processResources {
    val properties = resourceProperties
    inputs.properties(properties)
    filesMatching("fabric.mod.json") { expand(properties) }
    filesMatching("aboutfaceeasyplace.mixins.json") { expand(properties) }
}

tasks.jar {
    from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }
}

// In-game test: `gradlew runClientGameTest` builds a small schematic in a test world with
// Litematica loaded, and checks block substitution in its verifier, its overlay and both of its
// Easy Place paths.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "aboutfaceeasyplace-test"
        enableGameTests = false
        eula = true
    }
}

dependencies {
    val litematica = "curse.maven:litematica-${property("litematica_curse_project")}:${property("litematica_curse_file")}"
    val malilib = "curse.maven:malilib-${property("malilib_curse_project")}:${property("malilib_curse_file")}"
    "gametestCompileOnly"(litematica)
    "gametestCompileOnly"(malilib)
    modLocalRuntime(litematica)
    modLocalRuntime(malilib)
}
