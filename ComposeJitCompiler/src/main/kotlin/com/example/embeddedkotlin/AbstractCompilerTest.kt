package com.example.embeddedkotlin

import androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar
import com.example.embeddedkotlin.facade.AnalysisResult
import com.example.embeddedkotlin.facade.KotlinCompilerFacade
import com.example.embeddedkotlin.facade.SourceFile
import java.io.File
import java.net.URLClassLoader
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.output.OutputFile
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.codegen.GeneratedClassLoader
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

abstract class AbstractCompilerTest(val useFir: Boolean) {

    val defaultClassPath by lazy {
        System.getProperty("java.class.path")!!.split(
            System.getProperty("path.separator")!!
        ).map { File(it) }
    }

    val defaultClassPathRoots by lazy {
        defaultClassPath.filter {
            !it.path.contains("robolectric") && it.extension != "xml"
        }.toList()
    }

    protected open fun CompilerConfiguration.updateConfiguration() {}

    private val testRootDisposable = Disposer.newDisposable()

    private fun createCompilerFacade(
        additionalPaths: List<File> = listOf(),
        forcedFirSetting: Boolean? = null,
        registerExtensions: (Project.(CompilerConfiguration) -> Unit)? = null
    ) = KotlinCompilerFacade.create(
        testRootDisposable,
        updateConfiguration = {
            val enableFir = forcedFirSetting ?: useFir
            val languageVersion =
                if (enableFir) {
                    LanguageVersion.KOTLIN_2_0
                } else {
                    LanguageVersion.KOTLIN_1_9
                }
            // For tests, allow unstable artifacts compiled with a pre-release compiler
            // as input to stable compilations.
            val analysisFlags: Map<AnalysisFlag<*>, Any?> = mapOf(
                AnalysisFlags.allowUnstableDependencies to true,
                AnalysisFlags.skipPrereleaseCheck to true
            )
            languageVersionSettings = LanguageVersionSettingsImpl(
                languageVersion,
                ApiVersion.createByLanguageVersion(languageVersion),
                analysisFlags
            )
            updateConfiguration()
            addJvmClasspathRoots(additionalPaths)
            addJvmClasspathRoots(defaultClassPathRoots)
            if (!getBoolean(JVMConfigurationKeys.NO_JDK) &&
                get(JVMConfigurationKeys.JDK_HOME) == null) {
                // We need to set `JDK_HOME` explicitly to use JDK 17
                put(JVMConfigurationKeys.JDK_HOME, File(System.getProperty("java.home")!!))
            }
            configureJdkClasspathRoots()
        },
        registerExtensions = registerExtensions ?: { configuration ->
            ComposePluginRegistrar.registerCommonExtensions(this)
            IrGenerationExtension.registerExtension(
                this,
                ComposePluginRegistrar.createComposeIrExtension(configuration)
            )
        }
    )

    protected fun analyze(
        platformSources: List<SourceFile>,
        commonSources: List<SourceFile> = listOf()
    ): AnalysisResult =
        createCompilerFacade().analyze(platformSources, commonSources)

    protected fun compileToIr(
        sourceFiles: List<SourceFile>,
        additionalPaths: List<File> = listOf(),
        registerExtensions: (Project.(CompilerConfiguration) -> Unit)? = null
    ): IrModuleFragment =
        createCompilerFacade(additionalPaths, registerExtensions = registerExtensions)
            .compileToIr(sourceFiles)

    protected fun createClassLoader(
        platformSourceFiles: List<SourceFile>,
        commonSourceFiles: List<SourceFile> = listOf(),
        additionalPaths: List<File> = listOf(),
        forcedFirSetting: Boolean? = null
    ): GeneratedClassLoader {
        val classLoader = URLClassLoader(
            (additionalPaths + defaultClassPath).map {
                it.toURI().toURL()
            }.toTypedArray(),
            this.javaClass.classLoader
        )
        return GeneratedClassLoader(
            createCompilerFacade(additionalPaths, forcedFirSetting)
                .compile(platformSourceFiles, commonSourceFiles).factory,
            classLoader
        )
    }
}

fun printPublicApi(classDump: String, name: String): String {
    return classDump
        .splitToSequence("\n")
        .filter {
            if (it.contains("INVOKESTATIC kotlin/internal/ir/Intrinsic")) {
                // if instructions like this end up in our generated code, it means something
                // went wrong. Usually it means that it just can't find the function to call,
                // so it transforms it into this intrinsic call instead of failing. If this
                // happens, we want to hard-fail the test as the code is definitely incorrect.
                error(
                    buildString {
                        append("An unresolved call was found in the generated bytecode of '")
                        append(name)
                        append("'")
                        appendLine()
                        appendLine()
                        appendLine("Call was: $it")
                        appendLine()
                        appendLine("Entire class file output:")
                        appendLine(classDump)
                    }
                )
            }
            if (it.startsWith("  ")) {
                if (it.startsWith("   ")) false
                else it[2] != '/' && it[2] != '@'
            } else {
                it == "}" || it.endsWith("{")
            }
        }
        .joinToString(separator = "\n")
        .replace('$', '%') // replace $ to % to make comparing it to kotlin string literals easier
}

fun OutputFile.writeToDir(directory: File) =
    FileUtil.writeToFile(File(directory, relativePath), asByteArray())

fun Collection<OutputFile>.writeToDir(directory: File) = forEach { it.writeToDir(directory) }
