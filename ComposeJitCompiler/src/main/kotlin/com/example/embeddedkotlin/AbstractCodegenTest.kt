package com.example.embeddedkotlin

import com.example.embeddedkotlin.facade.SourceFile
import java.io.File
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.codegen.GeneratedClassLoader
var uniqueNumber = 0

abstract class AbstractCodegenTest(useFir: Boolean) : AbstractCompilerTest(useFir) {
    private fun dumpClasses(loader: GeneratedClassLoader) {
        for (
        file in loader.allGeneratedFiles.filter {
            it.relativePath.endsWith(".class")
        }
        ) {
            println("------\nFILE: ${file.relativePath}\n------")
            println(file.asText())
        }
    }

    protected fun validateBytecode(
        @Language("kotlin")
        src: String,
        dumpClasses: Boolean = false,
        validate: (String) -> Unit
    ) {
        val className = "Test_REPLACEME_${uniqueNumber++}"
        val fileName = "$className.kt"

        val loader = classLoader(
            """
           @file:OptIn(
             InternalComposeApi::class,
           )
           package test

           import androidx.compose.runtime.*

           $src

            fun used(x: Any?) {}
        """,
            fileName, dumpClasses
        )

        val apiString = loader
            .allGeneratedFiles
            .filter { it.relativePath.endsWith(".class") }.joinToString("\n") {
                it.asText().replace('$', '%').replace(className, "Test")
            }

        validate(apiString)
    }

    protected fun classLoader(
        @Language("kotlin")
        source: String,
        fileName: String,
        dumpClasses: Boolean = false
    ): GeneratedClassLoader {
        val loader = createClassLoader(listOf(SourceFile(fileName, source)))
        if (dumpClasses) dumpClasses(loader)
        return loader
    }

    protected fun classLoader(
        sources: Map<String, String>,
        dumpClasses: Boolean = false
    ): GeneratedClassLoader {
        val loader = createClassLoader(
            sources.map { (fileName, source) -> SourceFile(fileName, source) }
        )
        if (dumpClasses) dumpClasses(loader)
        return loader
    }

    protected fun classLoader(
        platformSources: Map<String, String>,
        commonSources: Map<String, String>,
        dumpClasses: Boolean = false
    ): GeneratedClassLoader {
        val loader = createClassLoader(
            platformSources.map { (fileName, source) -> SourceFile(fileName, source) },
            commonSources.map { (fileName, source) -> SourceFile(fileName, source) }
        )
        if (dumpClasses) dumpClasses(loader)
        return loader
    }

    protected fun classLoader(
        sources: Map<String, String>,
        additionalPaths: List<File>,
        dumpClasses: Boolean = false,
        forcedFirSetting: Boolean? = null
    ): GeneratedClassLoader {
        val loader = createClassLoader(
            sources.map { (fileName, source) -> SourceFile(fileName, source) },
            additionalPaths = additionalPaths,
            forcedFirSetting = forcedFirSetting
        )
        if (dumpClasses) dumpClasses(loader)
        return loader
    }

    protected fun testCompile(@Language("kotlin") source: String, dumpClasses: Boolean = false) {
        classLoader(source, "Test.kt", dumpClasses)
    }
}
