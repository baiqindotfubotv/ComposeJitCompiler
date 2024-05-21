package com.example.embeddedkotlin

import androidx.compose.compiler.plugins.kotlin.ComposeConfiguration
import androidx.compose.compiler.plugins.kotlin.lower.dumpSrc
import com.example.embeddedkotlin.facade.SourceFile
import java.io.File
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.util.dump

abstract class AbstractIrTransformTest(useFir: Boolean = false) : AbstractCodegenTest(useFir) {
    override fun CompilerConfiguration.updateConfiguration() {
        put(ComposeConfiguration.SOURCE_INFORMATION_ENABLED_KEY, true)
    }

    fun verifyCrossModuleComposeIrTransform(
        @Language("kotlin")
        dependencySource: String,
        @Language("kotlin")
        source: String,
        expectedTransformed: String,
        dumpTree: Boolean = false,
        dumpClasses: Boolean = false,
        validator: (element: IrElement) -> Unit = {},
    ) {
        val dependencyFileName = "Test_REPLACEME_${uniqueNumber++}"

        classLoader(dependencySource, dependencyFileName, dumpClasses)
            .allGeneratedFiles

        verifyComposeIrTransform(
            source,
            expectedTransformed,
            "",
            validator = validator,
            dumpTree = dumpTree,
            additionalPaths = listOf()
        )
    }

    fun verifyGoldenCrossModuleComposeIrTransform(
        @Language("kotlin")
        dependencySource: String,
        @Language("kotlin")
        source: String,
        dumpTree: Boolean = false,
        dumpClasses: Boolean = false,
        validator: (element: IrElement) -> Unit = {},
    ) {
        val dependencyFileName = "Test_REPLACEME_${uniqueNumber++}"

        classLoader(dependencySource, dependencyFileName, dumpClasses)
            .allGeneratedFiles

        verifyGoldenComposeIrTransform(
            source,
            "",
            validator = validator,
            dumpTree = dumpTree,
            additionalPaths = listOf()
        )
    }

    fun transform(
        @Language("kotlin")
        source: String,
        @Language("kotlin")
        extra: String = "",
        validator: (element: IrElement) -> Unit = {},
        dumpTree: Boolean = false,
        truncateTracingInfoMode: TruncateTracingInfoMode = TruncateTracingInfoMode.TRUNCATE_KEY,
        additionalPaths: List<File> = listOf(),
    ): String {
        val files = listOf(SourceFile("Test.kt", source), SourceFile("Extra.kt", extra))
        val irModule = compileToIr(files, additionalPaths)
        val keySet = mutableListOf<Int>()
        fun IrElement.validate(): IrElement = this.also { validator(it) }
        val actualTransformed = irModule
            .files[0]
            .validate()
            .dumpSrc(useFir)
            .replace('$', '%')
            // replace source keys for start group calls
            .replace(
                Regex(
                    "(%composer\\.start(Restart|Movable|Replaceable|Replace)" +
                            "Group\\()-?((0b)?[-\\d]+)"
                )
            ) {
                val stringKey = it.groupValues[3]
                val key = if (stringKey.startsWith("0b"))
                    Integer.parseInt(stringKey.drop(2), 2)
                else
                    stringKey.toInt()
                if (key in keySet) {
                    "${it.groupValues[1]}<!DUPLICATE KEY: $key!>"
                } else {
                    keySet.add(key)
                    "${it.groupValues[1]}<>"
                }
            }
            .replace(
                Regex("(sourceInformationMarkerStart\\(%composer, )([-\\d]+)")
            ) {
                "${it.groupValues[1]}<>"
            }
            // replace traceEventStart values with a token
            // TODO(174715171): capture actual values for testing
            .replace(
                Regex(
                    "traceEventStart\\(-?\\d+, (%dirty|%changed|-1), (%dirty1|%changed1|-1), (.*)"
                )
            ) {
                when (truncateTracingInfoMode) {
                    TruncateTracingInfoMode.TRUNCATE_KEY ->
                        "traceEventStart(<>, ${it.groupValues[1]}, ${it.groupValues[2]}, <>)"

                    TruncateTracingInfoMode.KEEP_INFO_STRING ->
                        "traceEventStart(<>, ${it.groupValues[1]}, ${it.groupValues[2]}, " +
                                it.groupValues[3]
                }
            }
            // replace source information with source it references
            .replace(
                Regex(
                    "(%composer\\.start(Restart|Movable|Replaceable|Replace)Group\\" +
                            "([^\"\\n]*)\"(.*)\"\\)"
                )
            ) {
                "${it.groupValues[1]}\"${generateSourceInfo(it.groupValues[4], source)}\")"
            }
            .replace(
                Regex("(sourceInformation(MarkerStart)?\\(.*)\"(.*)\"\\)")
            ) {
                "${it.groupValues[1]}\"${generateSourceInfo(it.groupValues[3], source)}\")"
            }
            .replace(
                Regex(
                    "(composableLambda[N]?\\" +
                            "([^\"\\n]*)\"(.*)\"\\)"
                )
            ) {
                "${it.groupValues[1]}\"${generateSourceInfo(it.groupValues[2], source)}\")"
            }
            .replace(
                Regex("(rememberComposableLambda[N]?)\\((-?\\d+)")
            ) {
                "${it.groupValues[1]}(<>"
            }
            // replace source keys for joinKey calls
            .replace(
                Regex(
                    "(%composer\\.joinKey\\()([-\\d]+)"
                )
            ) {
                "${it.groupValues[1]}<>"
            }
            // composableLambdaInstance(<>, true)
            .replace(
                Regex(
                    "(composableLambdaInstance\\()([-\\d]+, (true|false))"
                )
            ) {
                val callStart = it.groupValues[1]
                val tracked = it.groupValues[3]
                "$callStart<>, $tracked"
            }
            // composableLambda(%composer, <>, true)
            .replace(
                Regex(
                    "(composableLambda\\(%composer,\\s)([-\\d]+)"
                )
            ) {
                "${it.groupValues[1]}<>"
            }
            .trimIndent()

        if (dumpTree) {
            println(irModule.dump())
        }

        return actualTransformed
    }

    fun verifyComposeIrTransform(
        @Language("kotlin")
        source: String,
        expectedTransformed: String,
        @Language("kotlin")
        extra: String = "",
        validator: (element: IrElement) -> Unit = {},
        dumpTree: Boolean = false,
        truncateTracingInfoMode: TruncateTracingInfoMode = TruncateTracingInfoMode.TRUNCATE_KEY,
        additionalPaths: List<File> = listOf(),
    ) {
        val actualTransformed =
            transform(source, extra, validator, dumpTree, truncateTracingInfoMode, additionalPaths)
    }

    fun verifyGoldenComposeIrTransform(
        @Language("kotlin")
        source: String,
        @Language("kotlin")
        extra: String = "",
        validator: (element: IrElement) -> Unit = {},
        dumpTree: Boolean = false,
        truncateTracingInfoMode: TruncateTracingInfoMode = TruncateTracingInfoMode.TRUNCATE_KEY,
        additionalPaths: List<File> = listOf(),
    ) {
        val actualTransformed =
            transform(source, extra, validator, dumpTree, truncateTracingInfoMode, additionalPaths)
    }

    private fun MatchResult.isNumber() = groupValues[1].isNotEmpty()
    private fun MatchResult.number() = groupValues[1].toInt()
    private val MatchResult.text get() = groupValues[0]
    private fun MatchResult.isChar(c: String) = text == c
    private fun MatchResult.isFileName() = groups[4] != null

    private fun generateSourceInfo(sourceInfo: String, source: String): String {
        val r = Regex("(\\d+)|([,])|([*])|([:])|C(\\(.*\\))?|L|(P\\(*\\))|@")
        var current = 0
        var currentResult = r.find(sourceInfo, current)
        var result = ""

        fun next(): MatchResult? {
            currentResult?.let {
                current = it.range.last + 1
                currentResult = it.next()
            }
            return currentResult
        }

        // A location has the format: [<line-number>]['@' <offset> ['L' <length>]]
        // where the named productions are numbers
        fun parseLocation(): String? {
            var mr = currentResult
            if (mr != null && mr.isNumber()) {
                // line number, we ignore the value in during testing.
                mr = next()
            }
            if (mr != null && mr.isChar("@")) {
                // Offset
                mr = next()
                if (mr == null || !mr.isNumber()) {
                    return null
                }
                val offset = mr.number()
                mr = next()
                var ellipsis = ""
                val maxFragment = 6
                val rawLength = if (mr != null && mr.isChar("L")) {
                    mr = next()
                    if (mr == null || !mr.isNumber()) {
                        return null
                    }
                    mr.number().also { next() }
                } else {
                    maxFragment
                }
                val eol = source.indexOf('\n', offset).let {
                    if (it < 0) source.length else it
                }
                val space = source.indexOf(' ', offset).let {
                    if (it < 0) source.length else it
                }
                val maxEnd = offset + maxFragment
                if (eol > maxEnd && space > maxEnd) ellipsis = "..."
                val length = minOf(maxEnd, minOf(offset + rawLength, space, eol)) - offset
                return "<${source.substring(offset, offset + length)}$ellipsis>"
            }
            return null
        }

        while (currentResult != null) {
            val mr = currentResult!!
            if (mr.range.first != current) {
                return "invalid source info at $current: '$sourceInfo'"
            }
            when {
                mr.isNumber() || mr.isChar("@") -> {
                    val fragment = parseLocation()
                        ?: return "invalid source info at $current: '$sourceInfo'"
                    result += fragment
                }

                mr.isFileName() -> {
                    return result + ":" + sourceInfo.substring(mr.range.last + 1)
                }

                else -> {
                    result += mr.text
                    next()
                }
            }
            require(mr != currentResult) { "regex didn't advance" }
        }
        if (current != sourceInfo.length)
            return "invalid source info at $current: '$sourceInfo'"
        return result
    }

    enum class TruncateTracingInfoMode {
        TRUNCATE_KEY, // truncates only the `key` parameter
        KEEP_INFO_STRING, // truncates everything except for the `info` string
    }
}
