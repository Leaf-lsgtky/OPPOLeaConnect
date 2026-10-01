package leaconnect

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * LSPosed 认的模块元数据必须在 APK 根目录的 META-INF/xposed/ 下，AGP 没有地方能放它；
 * 而注入必须发生在签名之前，所以这里不走 AGP 的签名：
 * 未签名 APK -> 追加 META-INF/xposed 目录 -> zipalign -> apksigner(v1+v2+v3)。
 * 等价于旧 module/oppoemu/build.sh 的 [4/6]~[6/6] 三步。
 *
 * 放在 buildSrc 而不是 app/build.gradle.kts 里：Gradle 的 Kotlin DSL 脚本里一旦出现
 * 顶层 class 声明，该声明之后的语句就不再被执行（编译照样成功）。实测在
 * abstract class 之前加 println 会打印、之后就永远不打印。
 */
abstract class PackageModuleTask : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations

    @get:InputFile abstract val unsignedApk: RegularFileProperty

    @get:InputDirectory abstract val metaRoot: DirectoryProperty

    @get:InputFile abstract val keystore: RegularFileProperty

    @get:Input abstract val keyAlias: Property<String>

    @get:Input abstract val storePassword: Property<String>

    @get:Input abstract val keyPassword: Property<String>

    @get:InputDirectory abstract val buildTools: DirectoryProperty

    @get:OutputFile abstract val outputApk: RegularFileProperty

    @TaskAction
    fun run() {
        val unsigned = unsignedApk.get().asFile
        if (!unsigned.isFile) {
            throw org.gradle.api.GradleException(
                "找不到未签名 APK：$unsigned —— release 的签名/产物名若改过，这里的路径要同步")
        }
        val injected = File(temporaryDir, "injected.apk")
        val aligned = File(temporaryDir, "aligned.apk")
        val out = outputApk.get().asFile.also { it.parentFile?.mkdirs() }
        val meta = metaRoot.get().asFile

        ZipFile(unsigned).use { src ->
            ZipOutputStream(injected.outputStream().buffered()).use { dst ->
                val seen = HashSet<String>()
                src.entries().asSequence().forEach { e ->
                    // 旧签名块必须先剔掉，最后由 apksigner 重新签
                    if (e.name.startsWith("META-INF/") &&
                        (e.name.endsWith(".SF") || e.name.endsWith(".RSA") ||
                                e.name.endsWith(".EC") || e.name.endsWith(".MF"))
                    ) {
                        return@forEach
                    }
                    seen += e.name
                    val bytes = src.getInputStream(e).readBytes()
                    val ne = ZipEntry(e.name)
                    ne.time = e.time
                    if (e.method == ZipEntry.STORED) {
                        // extractNativeLibs=false 时 lib/**/*.so 必须是未压缩存储的；
                        // 这里一旦重压缩，安装期就报 Failed to extract native libraries。
                        // STORED 条目还得自己给 size/compressedSize/crc，zip 才写得对。
                        ne.method = ZipEntry.STORED
                        ne.size = bytes.size.toLong()
                        ne.compressedSize = bytes.size.toLong()
                        ne.crc = crc32(bytes)
                    }
                    dst.putNextEntry(ne)
                    dst.write(bytes)
                    dst.closeEntry()
                }
                meta.walk().filter { it.isFile }.forEach { f ->
                    val name = f.relativeTo(meta).path.replace('\\', '/')
                    if (seen.add(name)) {
                        dst.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(dst) }
                        dst.closeEntry()
                    }
                }
            }
        }

        val bt = buildTools.get().asFile
        val win = AndroidSdk.isWindows()
        // -p：把 .so 页对齐，否则 extractNativeLibs=false 的包在部分设备上装不上
        execOps.exec {
            commandLine(
                File(bt, if (win) "zipalign.exe" else "zipalign").absolutePath,
                "-f", "-p", "4", injected.absolutePath, aligned.absolutePath,
            )
        }
        val javaBin = File(System.getProperty("java.home"), if (win) "bin/java.exe" else "bin/java")
        execOps.exec {
            commandLine(
                javaBin.absolutePath, "-cp", File(bt, "lib/apksigner.jar").absolutePath,
                "com.android.apksigner.ApkSignerTool", "sign",
                "--ks", keystore.get().asFile.absolutePath,
                "--ks-pass", "pass:" + storePassword.get(),
                "--ks-key-alias", keyAlias.get(),
                "--key-pass", "pass:" + keyPassword.get(),
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--out", out.absolutePath,
                aligned.absolutePath,
            )
        }
        logger.lifecycle("模块 APK: ${out.absolutePath}")
    }

    private fun crc32(bytes: ByteArray): Long {
        val c = java.util.zip.CRC32()
        c.update(bytes)
        return c.value
    }
}
