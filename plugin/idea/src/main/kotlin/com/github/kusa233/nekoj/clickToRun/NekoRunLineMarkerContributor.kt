package com.github.kusa233.nekoj.clickToRun

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement

class NekoRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        if (element.text != "main") return null
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Run Neko" }
    }
}