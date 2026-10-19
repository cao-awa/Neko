package com.github.kusa233.nekoj.clickToRun

import com.github.kusa233.nekoj.NekoRunConfiguration
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class NekoRunConfigurationProducer : LazyRunConfigurationProducer<NekoRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory {
        return NekoRunConfigurationType.getInstance().configurationFactories[0]
    }

    override fun setupConfigurationFromContext(
        configuration: NekoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val element = context.psiLocation ?: return false
        val file = element.containingFile ?: return false

        if (file.virtualFile?.extension != "neko") return false

        configuration.name = file.name
        configuration.scriptPath = file.virtualFile.path
        sourceElement.set(element)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: NekoRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val file = context.psiLocation?.containingFile?.virtualFile ?: return false
        return configuration.scriptPath == file.path
    }
}