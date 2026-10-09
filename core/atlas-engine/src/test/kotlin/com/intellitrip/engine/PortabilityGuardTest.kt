package com.intellitrip.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the SDK portability rule: reusable engine modules must never import
 * Android framework types. Violations are caught here rather than by a host that
 * cannot link.
 */
class PortabilityGuardTest {

    private val portableModules = listOf(
        "core/atlas-domain",
        "core/atlas-contracts",
        "core/atlas-scope",
        "core/atlas-routing",
        "core/atlas-map",
        "core/atlas-weather",
        "core/atlas-offline",
        "core/atlas-gtfs-static",
        "core/atlas-gtfs-realtime",
        "core/atlas-engine",
    )

    private fun repositoryRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    @Test
    fun portableModulesExist() {
        val root = repositoryRoot()
        portableModules.forEach { module ->
            assertTrue(
                File(root, "$module/build.gradle.kts").exists(),
                "expected module directory $module",
            )
        }
    }

    @Test
    fun portableModulesDoNotImportAndroid() {
        val root = repositoryRoot()
        // The forbidden prefix is assembled at runtime so this guard does not
        // trip over its own source.
        val androidImport = "import " + "android."
        val offenders = portableModules.flatMap { module ->
            val sources = File(root, "$module/src")
            if (!sources.exists()) return@flatMap emptyList()
            sources.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .filter { file -> file.name != "PortabilityGuardTest.kt" }
                .filter { file ->
                    file.readLines().any { line ->
                        line.trimStart().startsWith(androidImport) ||
                            line.contains("com.google." + "android")
                    }
                }
                .map { it.relativeTo(root).path }
                .toList()
        }
        assertEquals(emptyList(), offenders, "reusable modules must not depend on the Android framework")
    }

    @Test
    fun reusableModulesDoNotDeclareAndroidPlugins() {
        val root = repositoryRoot()
        val offenders = portableModules.filter { module ->
            val buildFile = File(root, "$module/build.gradle.kts")
            val text = buildFile.readText()
            text.contains("com.android.application") || text.contains("com.android.library")
        }
        assertEquals(emptyList(), offenders, "reusable modules must be plain Kotlin/JVM")
    }
}