package com.github.kusa233.nekoj

import com.intellij.icons.AllIcons
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import javax.swing.Icon

/**
 * Contributes Neko class methods and Java platform standard library methods to IDEA's
 * "Navigate | Symbol" action (Ctrl+Alt+Shift+N / Search Everywhere "Symbols" tab).
 */
class NekoGotoSymbolContributor : ChooseByNameContributorEx, ChooseByNameContributor {

    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?,
    ) {
        val project = scope.project
        val names = NekoMethodIndex.allMethodNames(project)
        for (name in names) {
            if (!processor.process(name)) break
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val project = parameters.project
        val methods = NekoMethodIndex.findMethodsByName(name, project)
        for (method in methods) {
            val item = NekoMethodNavigationItem(method, project)
            if (!processor.process(item)) break
        }
    }

    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
        NekoMethodIndex.allMethodNames(project, includeJava = includeNonProjectItems).toTypedArray()

    override fun getItemsByName(
        name: String,
        pattern: String,
        project: Project,
        includeNonProjectItems: Boolean,
    ): Array<NavigationItem> =
        NekoMethodIndex.findMethodsByName(name, project, includeJava = includeNonProjectItems)
            .map { NekoMethodNavigationItem(it, project) }
            .toTypedArray()
}

/**
 * A navigation item wrapping an [IndexedMethod] for "Navigate | Symbol".
 */
class NekoMethodNavigationItem(
    val method: IndexedMethod,
    val project: Project,
) : NavigationItem {

    override fun getName(): String = method.name

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = method.presentableText

        override fun getLocationString(): String = method.locationString

        override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.Method
    }

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = method.isNekoMethod

    override fun navigate(requestFocus: Boolean) {
        if (method.isNekoMethod) {
            val path = method.nekoSpan?.file?.path
            if (path != null) {
                val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path)
                if (virtualFile != null) {
                    val descriptor = OpenFileDescriptor(project, virtualFile, method.nekoSpan.start)
                    descriptor.navigate(requestFocus)
                    return
                }
            }
        } else {
            // For Java platform methods: navigate to source using JavaPsiFacade if the Java module is loaded
            try {
                val facadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
                val getInstance = facadeClass.getMethod("getInstance", Project::class.java)
                val facade = getInstance.invoke(null, project)
                val findClass = facadeClass.getMethod("findClass", String::class.java, GlobalSearchScope::class.java)
                val psiClass = findClass.invoke(facade, method.ownerQualifiedName, GlobalSearchScope.allScope(project))
                if (psiClass != null && psiClass is Navigatable && psiClass.canNavigate()) {
                    psiClass.navigate(requestFocus)
                    return
                }
            } catch (_: Throwable) {
                // Java PSI not available or navigation failed
            }
        }
    }
}
