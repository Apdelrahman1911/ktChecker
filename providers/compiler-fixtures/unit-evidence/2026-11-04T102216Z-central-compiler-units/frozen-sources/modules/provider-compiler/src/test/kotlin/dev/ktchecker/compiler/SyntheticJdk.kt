package dev.ktchecker.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Tiny layout/identity input only. Its text launcher must never be executed. */
internal object SyntheticJdk {
    fun create(home: Path, osName: String = hostOsName()): Path {
        Files.createDirectories(home)
        val javaName = if (osName == "Windows") "java.exe" else "java"
        val vm = when (osName) {
            "Linux" -> "lib/server/libjvm.so"
            "Darwin" -> "lib/server/libjvm.dylib"
            else -> "bin/server/jvm.dll"
        }
        val files = mapOf(
            "release" to "JAVA_VERSION=\"21.0.9\"\nOS_NAME=\"$osName\"\nOS_ARCH=\"x86_64\"\n",
            "bin/$javaName" to "NON-EXECUTED synthetic Java launcher input\n",
            "lib/modules" to "non-executed synthetic modules\n",
            "lib/jvm.cfg" to "non-executed synthetic VM selection\n",
            "conf/security/java.security" to "non-executed synthetic security configuration\n",
            vm to "non-executed synthetic VM library\n",
        )
        files.forEach { (relative, text) ->
            val path = home.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            if (home.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    if (relative == "bin/$javaName") "rwx------" else "rw-------"))
            }
        }
        val launcher = home.resolve("bin/$javaName")
        if (!home.fileSystem.supportedFileAttributeViews().contains("posix")) launcher.toFile().setExecutable(true, true)
        return home.toRealPath().resolve("bin/$javaName")
    }

    fun hostOsName(): String = when {
        System.getProperty("os.name").startsWith("Windows") -> "Windows"
        System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it } -> "Darwin"
        else -> "Linux"
    }
}
