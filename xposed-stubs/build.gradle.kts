plugins { `java-library` }

// 桩类里 XC_LoadPackage.LoadPackageParam 引用了 android.content.pm.ApplicationInfo，
// 编译期需要 android.jar；它只是编译期依赖，产物 jar 也永远不进 APK。
val androidJar = AndroidSdk.platformJar(project)

dependencies { compileOnly(files(androidJar)) }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
