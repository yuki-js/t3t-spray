plugins {
    // Plugin versions are declared per-module.
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
