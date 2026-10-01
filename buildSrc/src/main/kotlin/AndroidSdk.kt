import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * SDK 位置：本机读 local.properties 的 sdk.dir，CI 上那个文件不入库，退到 ANDROID_HOME。
 * build-tools 取本机装着的最高版本 —— GitHub runner 预装的版本会随时间变。
 */
object AndroidSdk {

    fun dir(project: Project): File {
        val local = project.rootProject.file("local.properties")
        if (local.isFile) {
            val p = Properties().apply { local.inputStream().use { load(it) } }
            p.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        }
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        throw IllegalStateException(
            "找不到 Android SDK：local.properties 没有 sdk.dir，环境变量也没有 ANDROID_HOME"
        )
    }

    fun buildTools(project: Project): File {
        val root = File(dir(project), "build-tools")
        val versions = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }
        if (versions.isNullOrEmpty()) {
            throw IllegalStateException("没有可用的 build-tools：$root")
        }
        return versions.last()
    }

    /** 桩工程编译期要的 android.jar：取本机最高的那个 platform */
    fun platformJar(project: Project): File {
        val root = File(dir(project), "platforms")
        val jars = root.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.removePrefix("android-").substringBefore('.').toIntOrNull() ?: 0 }
        val last = jars?.lastOrNull()?.let { File(it, "android.jar") }
        if (last == null || !last.isFile) {
            throw IllegalStateException("找不到 android.jar：$root")
        }
        return last
    }

    fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")
}

/** 签名材料一律不入库：本机放 keystore.properties，CI 用同名环境变量注入 */
object Signing {

    fun file(project: Project): File {
        val p = Properties()
        val f = project.rootProject.file("keystore.properties")
        if (f.isFile) f.inputStream().use { p.load(it) }
        val path = p.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
            ?: throw IllegalStateException(
                "缺少签名库：写 keystore.properties 的 storeFile，或设置 KEYSTORE_FILE"
            )
        return project.rootProject.file(path)
    }

    fun prop(key: String, env: String): String {
        val f = File("keystore.properties")
        if (f.isFile) {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            p.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return System.getenv(env)
            ?: throw IllegalStateException("缺少签名参数：keystore.properties 的 $key 或环境变量 $env")
    }
}
