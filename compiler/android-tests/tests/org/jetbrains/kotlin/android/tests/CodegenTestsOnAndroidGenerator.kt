/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.tests

import com.google.common.collect.Lists
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.cli.common.output.outputUtils.writeAllTo
import org.jetbrains.kotlin.cli.jvm.compiler.CliLightClassGenerationSupport
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.CodegenTestCase
import org.jetbrains.kotlin.codegen.CodegenTestFiles
import org.jetbrains.kotlin.codegen.GenerationUtils
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.name.NameUtils
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.*
import org.jetbrains.kotlin.utils.Printer
import org.junit.Assert
import org.junit.Ignore
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.*

data class ConfigurationKey(val kind: ConfigurationKind, val jdkKind: TestJdkKind, val configuration: String)

@Ignore
class CodegenTestsOnAndroidGenerator private constructor(private val pathManager: PathManager) : CodegenTestCase() {
    private var writtenFilesCount = 0

    private var currentModuleIndex = 1

    private val generatedTestNames = Lists.newArrayList<String>()

    private fun generateOutputFiles() {
        prepareAndroidModule()
        generateAndSave()
    }

    private fun prepareAndroidModule() {
        println("Copying kotlin-runtime.jar and kotlin-reflect.jar in android module...")
        copyKotlinRuntimeJars()

        println("Check 'libs' folder in tested android module...")
        val libsFolderInTestedModule = File(pathManager.libsFolderInAndroidTestedModuleTmpFolder)
        if (!libsFolderInTestedModule.exists()) {
            libsFolderInTestedModule.mkdirs()
        }
    }

    private fun copyKotlinRuntimeJars() {
        FileUtil.copy(
            ForTestCompileRuntime.runtimeJarForTests(),
            File(pathManager.libsFolderInAndroidTmpFolder + "/kotlin-runtime.jar")
        )
        FileUtil.copy(
            ForTestCompileRuntime.reflectJarForTests(),
            File(pathManager.libsFolderInAndroidTmpFolder + "/kotlin-reflect.jar")
        )

        FileUtil.copy(
            ForTestCompileRuntime.kotlinTestJarForTests(),
            File(pathManager.libsFolderInAndroidTmpFolder + "/kotlin-test.jar")
        )
    }

    private fun generateAndSave() {
        println("Generating test files...")
        val testSourceFilePath =
            pathManager.srcFolderInAndroidTmpFolder + "/" + testClassPackage.replace(".", "/") + "/" + testClassName + ".java"

        FileWriter(File(testSourceFilePath)).use {
            val p = Printer(it)
            p.print(FileUtil.loadFile(File("license/LICENSE.txt")))
            p.println(
                """package $testClassPackage;
                |
                |import $baseTestClassPackage.$baseTestClassName;
                |
                |/* This class is generated by $generatorName. DO NOT MODIFY MANUALLY */
                |public class $testClassName extends $baseTestClassName {
                |
            """.trimMargin()
            )
            p.pushIndent()

            generateTestMethodsForDirectories(p, File("compiler/testData/codegen/box"), File("compiler/testData/codegen/boxInline"))

            p.popIndent()
            p.println("}")
        }
    }

    private fun generateTestMethodsForDirectories(p: Printer, vararg dirs: File) {
        val holders = mutableMapOf<ConfigurationKey, FilesWriter>()

        for (dir in dirs) {
            val files = dir.listFiles() ?: error("Folder with testData is empty: ${dir.absolutePath}")
            processFiles(p, files, holders)
        }

        holders.values.forEach {
            it.writeFilesOnDisk()
        }
    }

    internal inner class FilesWriter(
        private val configuration: CompilerConfiguration
    ) {
        private val rawFiles: MutableList<Pair<String, String>> = ArrayList()

        private fun shouldWriteFilesOnDisk(): Boolean = rawFiles.size > 300

        fun writeFilesOnDiskIfNeeded() {
            if (shouldWriteFilesOnDisk()) {
                writeFilesOnDisk()
            }
        }

        fun writeFilesOnDisk() {
            val disposable = TestDisposable()

            val environment = KotlinCoreEnvironment.createForTests(
                disposable,
                configuration.copy().apply { put(CommonConfigurationKeys.MODULE_NAME, "android-module-" + currentModuleIndex++) },
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            )

            writeFiles(
                rawFiles.map {
                    CodegenTestFiles.create(it.first, it.second, environment.project).psiFile
                }, environment
            )
            Disposer.dispose(disposable)
            rawFiles.clear()
        }

        fun addFile(name: String, content: String) {
            rawFiles.add(name to content)
        }

        private fun writeFiles(filesToCompile: List<KtFile>, environment: KotlinCoreEnvironment) {
            if (filesToCompile.isEmpty()) return

            //1000 files per folder, each folder would be jared by build.gradle script
            // We can't create one big jar with all test cause dex has problem with memory on teamcity
            writtenFilesCount += filesToCompile.size
            val outputDir = File(pathManager.getOutputForCompiledFiles(writtenFilesCount / 1000))

            println("Generating ${filesToCompile.size} files into ${outputDir.name}, configuration: '${environment.configuration}'...")

            val outputFiles = GenerationUtils.compileFiles(
                filesToCompile,
                environment,
                trace = CliLightClassGenerationSupport.NoScopeRecordCliBindingTrace()
            ).run { destroy(); factory }

            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            Assert.assertTrue("Cannot create directory for compiled files", outputDir.exists())

            outputFiles.writeAllTo(outputDir)
        }
    }

    @Throws(IOException::class)
    private fun processFiles(
        printer: Printer,
        files: Array<File>,
        holders: MutableMap<ConfigurationKey, FilesWriter>
    ) {
        holders.values.forEach {
            it.writeFilesOnDiskIfNeeded()
        }

        for (file in files) {
            if (SpecialFiles.getExcludedFiles().contains(file.name)) {
                continue
            }
            if (file.isDirectory) {
                val listFiles = file.listFiles()
                if (listFiles != null) {
                    processFiles(printer, listFiles, holders)
                }
            } else if (FileUtilRt.getExtension(file.name) != KotlinFileType.EXTENSION) {
                // skip non kotlin files
            } else {
                if (!InTextDirectivesUtils.isPassingTarget(TargetBackend.JVM, file)) {
                    continue
                }

                val fullFileText = FileUtil.loadFile(file, true)
                //TODO support JvmPackageName
                if (fullFileText.contains("@file:JvmPackageName(")) continue

                if (hasBoxMethod(fullFileText)) {
                    val testFiles = createTestFiles(file, fullFileText)
                    val kind = extractConfigurationKind(testFiles)
                    val jdkKind = getJdkKind(testFiles)
                    val keyConfiguration = CompilerConfiguration()
                    updateConfigurationByDirectivesInTestFiles(testFiles, keyConfiguration)

                    val key = ConfigurationKey(kind, jdkKind, keyConfiguration.toString())
                    val filesHolder = holders.getOrPut(key) {
                        FilesWriter(KotlinTestUtils.newConfiguration(kind, jdkKind, KotlinTestUtils.getAnnotationsJar()).apply {
                            println("Creating new configuration by $key")
                            updateConfigurationByDirectivesInTestFiles(testFiles, this)
                        })
                    }

                    val classWithBoxMethod = patchFiles(file, testFiles, filesHolder) ?: continue

                    val generatedTestName = generateTestName(file.name)
                    generateTestMethod(
                        printer,
                        generatedTestName,
                        classWithBoxMethod.asString(),
                        StringUtil.escapeStringCharacters(file.path)
                    )
                }
            }
        }
    }

    private fun createTestFiles(file: File, expectedText: String): List<CodegenTestCase.TestFile> =
        KotlinTestUtils.createTestFiles(
            file.name,
            expectedText,
            object : KotlinTestUtils.TestFileFactoryNoModules<CodegenTestCase.TestFile>() {
                override fun create(fileName: String, text: String, directives: Map<String, String>): CodegenTestCase.TestFile {
                    return CodegenTestCase.TestFile(fileName, text)
                }
            })


    private fun generateTestName(fileName: String): String {
        var result = NameUtils.sanitizeAsJavaIdentifier(FileUtil.getNameWithoutExtension(StringUtil.capitalize(fileName)))

        var i = 0
        while (generatedTestNames.contains(result)) {
            result += "_" + i++
        }
        generatedTestNames.add(result)
        return result
    }

    companion object {
        private const val testClassPackage = "org.jetbrains.kotlin.android.tests"
        private const val testClassName = "CodegenTestCaseOnAndroid"
        private const val baseTestClassPackage = "org.jetbrains.kotlin.android.tests"
        private const val baseTestClassName = "AbstractCodegenTestCaseOnAndroid"
        private const val generatorName = "CodegenTestsOnAndroidGenerator"


        @JvmStatic
        @Throws(Throwable::class)
        fun generate(pathManager: PathManager) {
            CodegenTestsOnAndroidGenerator(pathManager).generateOutputFiles()
        }


        private fun hasBoxMethod(text: String): Boolean {
            return text.contains("fun box()")
        }

        private fun generateTestMethod(p: Printer, testName: String, className: String, filePath: String) {
            p.println("public void test$testName() throws Exception {")
            p.pushIndent()
            p.println("invokeBoxMethod($className.class, \"$filePath\", \"OK\");")
            p.popIndent()
            p.println("}")
            p.println()
        }
    }
}
