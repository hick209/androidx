/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.build.clang

import androidx.build.getKonanPrebuiltsFolder
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.LinkerOutputKind
import org.jetbrains.kotlin.konan.target.Platform
import org.jetbrains.kotlin.konan.target.PlatformManager

/**
 * A Gradle BuildService that provides access to Konan Compiler (clang, linker, ar etc) to
 * build native sources for multiple targets.
 *
 * You can obtain the instance via [obtain].
 *
 * @see ClangArchiveTask
 * @see ClangCompileTask
 * @see ClangSharedLibraryTask
 */
abstract class KonanBuildService @Inject constructor(
    private val execOperations: ExecOperations
) : BuildService<KonanBuildService.Parameters> {
    private val dist by lazy {
        Distribution(
            konanHome = parameters.konanHome.get().asFile.absolutePath,
            onlyDefaultProfiles = false,
            propertyOverrides = mapOf(
                "dependenciesUrl" to "file://${parameters.prebuilts.get().asFile}"
            )
        )
    }

    private val platformManager by lazy {
        PlatformManager(distribution = dist)
    }

    /**
     * @see ClangCompileTask
     */
    fun compile(parameters: ClangCompileParameters) {
        val outputDir = parameters.output.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val platform = getPlatform(parameters.konanTarget)
        val additionalArgs = buildList {
            addAll(parameters.freeArgs.get())
            add("--compile")
            parameters.includes.files.forEach { includeDirectory ->
                check(includeDirectory.isDirectory) {
                    "Include parameter for clang must be a directory"
                }
                add("-I${includeDirectory.canonicalPath}")
            }
            addAll(parameters.sources.regularFilePaths())
        }

        val clangCommand = platform.clang.clangC(
            *additionalArgs.toTypedArray()
        )
        execOperations.executeSilently { execSpec ->
            execSpec.executable = clangCommand.first()
            execSpec.args(clangCommand.drop(1))
            execSpec.workingDir = parameters.output.get().asFile
        }
    }

    /**
     * @see ClangArchiveTask
     */
    fun archiveLibrary(parameters: ClangArchiveParameters) {
        val outputFile = parameters.outputFile.get().asFile
        outputFile.delete()
        outputFile.parentFile.mkdirs()

        val platform = getPlatform(parameters.konanTarget)
        val llvmArgs = buildList {
            add("rc")
            add(parameters.outputFile.get().asFile.canonicalPath)
            addAll(parameters.objectFiles.regularFilePaths())
        }
        val commands = platform.clang.llvmAr(
            *llvmArgs.toTypedArray()
        )
        execOperations.executeSilently { execSpec ->
            execSpec.executable = commands.first()
            execSpec.args(commands.drop(1))
        }
    }

    /**
     * @see ClangSharedLibraryTask
     */
    fun createSharedLibrary(parameters: ClangSharedLibraryParameters) {
        val outputFile = parameters.outputFile.get().asFile
        outputFile.delete()
        outputFile.parentFile.mkdirs()

        val platform = getPlatform(parameters.konanTarget)
        val objectFiles = parameters.objectFiles.regularFilePaths()
        val linkedObjectFiles = parameters.linkedObjects.regularFilePaths()
        val linkCommands = platform.linker.finalLinkCommands(
            objectFiles = objectFiles,
            executable = outputFile.canonicalPath,
            libraries = linkedObjectFiles,
            linkerArgs = emptyList(),
            optimize = true,
            debug = false,
            kind = LinkerOutputKind.DYNAMIC_LIBRARY,
            outputDsymBundle = "unused",
            needsProfileLibrary = false,
            mimallocEnabled = false,
            sanitizer = null
        )
        linkCommands.map { it.argsWithExecutable }.forEach { args ->
            execOperations.executeSilently { execSpec ->
                execSpec.executable = args.first()
                args.drop(1).filterNot {
                    // TODO b/305804211 Figure out if we would rather pass all args manually
                    // We use the linker that konan uses to be as similar as possible but that
                    // linker also has konan demangling, which we don't need and not even available
                    // in the default distribution. Hence we remove that parameters.
                    // In the future, we can consider not using the `platform.linker` but then
                    // we would need to parse the konan.properties file to get the relevant
                    // necessary parameters like sysroot etc.
                    // https://github.com/JetBrains/kotlin/blob/master/kotlin-native/build-tools/src/main/kotlin/org/jetbrains/kotlin/KotlinNativeTest.kt#L536
                    it.contains("--defsym") ||
                        it.contains("Konan_cxa_demangle")
                }.forEach {
                    execSpec.args(it)
                }
            }
        }
    }

    private fun FileCollection.regularFilePaths(): List<String> {
        return files.flatMap {
            it.walkTopDown().filter {
                it.isFile
            }.map { it.canonicalPath }
        }.distinct()
    }

    private fun getPlatform(
        serializableKonanTarget: Property<SerializableKonanTarget>
    ): Platform {
        val konanTarget = serializableKonanTarget.get().asKonanTarget
        check(platformManager.enabled.contains(konanTarget)) {
            "cannot find enabled target with name ${serializableKonanTarget.get()}"
        }
        val platform = platformManager.platform(konanTarget)
        platform.downloadDependencies()
        return platform
    }

    /**
     * Execute the command without logs unless it fails.
     */
    private fun <T> ExecOperations.executeSilently(block: (ExecSpec) -> T) {
        val outputStream = ByteArrayOutputStream()
        val errorStream = ByteArrayOutputStream()
        val execResult = exec {
            block(it)
            it.setErrorOutput(errorStream)
            it.setStandardOutput(outputStream)
            it.isIgnoreExitValue = true // we'll check it below
        }
        if (execResult.exitValue != 0) {
            throw GradleException(
                """
                Compilation failed:
                ==== output:
                ${outputStream.toString(Charsets.UTF_8)}
                ==== error:
                ${errorStream.toString(Charsets.UTF_8)}
            """.trimIndent()
            )
        }
    }

    interface Parameters : BuildServiceParameters {
        val konanHome: DirectoryProperty
        val prebuilts: DirectoryProperty
    }

    companion object {
        internal const val KEY = "konanBuildService"
        fun obtain(
            project: Project
        ): Provider<KonanBuildService> {
            return project.gradle.sharedServices.registerIfAbsent(
                KEY,
                KonanBuildService::class.java
            ) {
                val nativeCompilerDownloader = NativeCompilerDownloader(project)
                nativeCompilerDownloader.downloadIfNeeded()

                it.parameters.konanHome.set(
                    nativeCompilerDownloader.compilerDirectory
                )
                it.parameters.prebuilts.set(
                    project.getKonanPrebuiltsFolder()
                )
            }
        }
    }
}
