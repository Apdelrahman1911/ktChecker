@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package dev.ktchecker.semantic.adapters.kotlin2421

import dev.ktchecker.contracts.Digests
import dev.ktchecker.semantic.*
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.ControlFlowException
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.sourceElement
import org.jetbrains.kotlin.fir.java.VirtualFileBasedSourceElement
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.load.kotlin.VirtualFileKotlinClass
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.isJvm
import java.util.concurrent.CancellationException

/**
 * Exact 2.4.21 compiler-object edges only. No class loading, reflection, FQN lookup, classpath
 * search, assumed jar layout, or ownership grant. One instance/bounded read budget per invocation.
 * Enhanced/override wrappers are deliberately unknown: their missing original-member edge is
 * NOT recovered by resolving a class name or using a copied containing-class lookup tag.
 */
internal class FirJvmBinaryOrigins {
    private var readBytes = 0L

    fun observe(symbol: FirBasedSymbol<*>, id: String, platform: TargetPlatform?): JvmBinaryOriginObservation {
        fun unknown(reason: String) = unknownJvmBinaryOrigin(id, reason)
        if (!platform.isJvm()) return unknown("non_jvm_compiler_context")
        try {
            checkInterrupted()
            if (symbol.origin.fromSource) return unknown("source_declaration_not_binary")
            val declaration = symbol.fir as? FirCallableDeclaration ?: return unknown("non_callable_symbol")
            if (declaration.isLocal) return unknown("local_declaration_not_binary")
            if (declaration !is FirNamedFunction) return unknown("callable_kind_unsupported")
            val route: String
            val binaryClassId: String
            val file: VirtualFile
            if (declaration is FirJavaMethod) {
                val owner = declaration.containingClassSymbol
                val source = (owner.fir as? FirClassLikeDeclaration)?.sourceElement as? VirtualFileBasedSourceElement
                    ?: return unknown("java_declaring_class_source_unavailable")
                if (owner.origin.fromSource) return unknown("source_declaration_not_binary")
                route = "java_method_class"
                binaryClassId = owner.classId.asString()
                file = source.virtualFile
            } else if (declaration.origin == FirDeclarationOrigin.Library) {
                val binary = when (val source = declaration.containerSource) {
                    is JvmPackagePartSource -> {
                        route = "kotlin_package_part"
                        source.knownJvmBinaryClass
                    }
                    is KotlinJvmBinarySourceElement -> {
                        route = "kotlin_class"
                        source.binaryClass
                    }
                    else -> return unknown("kotlin_container_source_unavailable")
                } as? VirtualFileKotlinClass ?: return unknown("kotlin_binary_vfs_unavailable")
                binaryClassId = binary.classId.asString()
                file = binary.file
            } else {
                return unknown(if (declaration.origin == FirDeclarationOrigin.Enhancement)
                    "java_enhancement_origin_unsupported" else "callable_wrapper_unsupported")
            }
            val protocol = file.fileSystem.protocol
            val path = file.path
            val location = jvmBinaryLocation(protocol, path) ?: return unknown("vfs_kind_or_path_unsupported")
            if (!boundedOriginText(binaryClassId) || !file.isValid || file.isDirectory) return unknown("vfs_class_not_readable")
            val size = file.length
            if (size !in 8..JVM_ORIGIN_MAX_CLASS_BYTES.toLong()) return unknown("class_byte_limit")
            val reservation = size + 1 // Include the single EOF probe even for an oversized stream.
            if (readBytes + reservation > JVM_ORIGIN_MAX_READ_BYTES) return unknown("observation_byte_budget_exhausted")
            // Count failed reads too. A failed observation never renews the invocation's budget.
            // This bounds observer-level consumption, NOT allocations inside compiler VFS calls:
            // FastJarVirtualFile.getInputStream eagerly materializes its own byte array first.
            readBytes += reservation
            val bytes = ByteArray(size.toInt())
            file.inputStream.use { stream ->
                var count = 0
                while (count < bytes.size) {
                    checkInterrupted()
                    val read = stream.read(bytes, count, bytes.size - count)
                    if (read < 0) return unknown("vfs_class_changed_during_observation")
                    if (read == 0) {
                        val one = stream.read()
                        if (one < 0) return unknown("vfs_class_changed_during_observation")
                        bytes[count++] = one.toByte()
                    } else count += read
                }
                if (stream.read() != -1) return unknown("vfs_class_changed_during_observation")
            }
            checkInterrupted()
            if (file.path != path || file.fileSystem.protocol != protocol || file.length != size ||
                !file.isValid || file.isDirectory) return unknown("vfs_class_changed_during_observation")
            if ((bytes[0].toInt() and 255) != 0xca || (bytes[1].toInt() and 255) != 0xfe ||
                (bytes[2].toInt() and 255) != 0xba || (bytes[3].toInt() and 255) != 0xbe) {
                return unknown("class_bytes_not_classfile")
            }
            return JvmBinaryOriginObservation(id, "observed", route,
                JvmBinaryContainerObservation(protocol, path, location.containerPath, location.entryPath,
                    binaryClassId, size, Digests.sha256(bytes), location.jdkModule))
        } catch (failure: Exception) {
            // Compiler cancellation is control flow, not evidence that a symbol has no origin.
            if (failure is CancellationException || failure is ControlFlowException) throw failure
            checkInterrupted()
            return unknown("binary_origin_observation_failed")
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("JVM binary-origin observation interrupted")
    }
}
