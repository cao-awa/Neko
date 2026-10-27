package com.github.kusa233.nekoj

import com.github.kusa233.neko.Compiler
import com.github.kusa233.neko.source.SourceFile
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import org.jdom.Element
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import java.awt.BorderLayout
import java.io.File

class NekoRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var scriptPath: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return NekoSettingsEditor()
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(environment) {
            override fun startProcess(): com.intellij.execution.process.ProcessHandler {
                val workDir = File(project.basePath!!)
                val sources = listOf(SourceFile.of(scriptPath.substringAfterLast("."), File(scriptPath).readText()))

                val compilingResult = Compiler.compileProgram(sources, File("$workDir/build").toPath())
                val outputDir = compilingResult.outputDirectory
                if (compilingResult.hasErrors) {
                    throw RuntimeException("Compilation failed:\n${compilingResult.renderDiagnostics()}")
                }

                // TODO: Get the main class name in a better way
                val mainClass = compilingResult.files
                    .flatMap { it.classes }.find { it.functions.any { it.name == "main" } }!!.name

                val cmd = GeneralCommandLine(
                    "java",
                    "-cp", outputDir.toString(),
                    mainClass
                )
                cmd.workDirectory = workDir
                cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

                val handler = OSProcessHandler(cmd)
                ProcessTerminatedListener.attach(handler)
                return handler
            }
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "scriptPath", scriptPath)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        scriptPath = JDOMExternalizerUtil.readField(element, "scriptPath") ?: ""
    }
}

class NekoSettingsEditor : SettingsEditor<NekoRunConfiguration>() {
    private val pathField = JTextField()

    override fun resetEditorFrom(s: NekoRunConfiguration) {
        pathField.text = s.scriptPath
    }

    override fun applyEditorTo(s: NekoRunConfiguration) {
        s.scriptPath = pathField.text
    }

    override fun createEditor(): JComponent {
        return JPanel(BorderLayout()).apply {
            add(pathField, BorderLayout.CENTER)
        }
    }
}