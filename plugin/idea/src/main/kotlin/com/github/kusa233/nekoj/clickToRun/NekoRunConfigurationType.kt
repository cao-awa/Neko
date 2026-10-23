package com.github.kusa233.nekoj.clickToRun

import com.github.kusa233.nekoj.NekoRunConfiguration
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons

class NekoRunConfigurationType : ConfigurationTypeBase(
    "NekoRunConfiguration",
    "Neko",
    "Run Neko script",
    AllIcons.RunConfigurations.Application
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project): RunConfiguration {
                return NekoRunConfiguration(project, this, "Neko")
            }

            override fun getId(): String = "NekoRunConfigurationFactory"
        })
    }

    companion object {
        fun getInstance(): NekoRunConfigurationType =
            ConfigurationType.CONFIGURATION_TYPE_EP.findExtensionOrFail(NekoRunConfigurationType::class.java)
    }
}