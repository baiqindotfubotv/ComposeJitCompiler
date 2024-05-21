package com.example.embeddedkotlin

import org.intellij.lang.annotations.Language

class MyComposeViewer : AbstractIrTransformTest() {

    @Language("kotlin")
    val source = """
            import androidx.compose.runtime.*
            
            @NonRestartableComposable @Composable
            fun Example(x: Int) {
                if (x > 0) {
                    NA()
                }
            }

            @Composable
            fun NA() {
                
            }
        """

    fun printGolden() {
        val transformed = transform(
            source = source
        )
        println("Test $transformed")
    }
}
