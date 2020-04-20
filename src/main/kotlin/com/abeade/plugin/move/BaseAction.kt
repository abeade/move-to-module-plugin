package com.abeade.plugin.move

import com.intellij.ide.util.DirectoryUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE_ARRAY
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.util.CommonRefactoringUtil
import java.util.*
import javax.swing.JPanel

internal abstract class BaseAction : AnAction() {

    private var currentModule: Module? = null
    private var targetModule: Module? = null

    abstract fun isEnable(event: AnActionEvent): Boolean

    abstract fun provideDialogTitleText(): String

    abstract fun provideDialogActionOkText(): String

    abstract fun onDialogActionOkInvoked(
        project: Project,
        queue: Queue<VirtualFile>,
        currentModulePath: String,
        targetModulePath: String
    )

    override fun update(event: AnActionEvent) {
        // Disable when no project
        val project = event.project
        val presentation = event.presentation
        if (project == null) {
            presentation.isEnabled = false
            return
        }
        // Disable when less than 2 modules
        val moduleList = getModuleList(project)
        if (moduleList.size < 2) {
            presentation.isEnabled = false
            return
        }
        // Disable when no selected file
        val selectedFileQueue = getSelectedFileQueue(event.dataContext)
        if (selectedFileQueue.isEmpty()) {
            presentation.isEnabled = false
            return
        }
        // Disable when selected file not located in same module
        for (module in moduleList) {
            var hasMatch = false
            for (file in selectedFileQueue) {
                if (file.path.startsWith(getModulePath(module))) {
                    hasMatch = true
                } else if (hasMatch) {
                    presentation.isEnabled = false
                    return
                }
            }
        }
        presentation.isEnabled = isEnable(event)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val moduleList = getModuleList(project)
        val selectedFileQueue = getSelectedFileQueue(event.dataContext)
        for (module in moduleList) {
            val modulePath = getModulePath(module)
            selectedFileQueue.firstOrNull { it.path.startsWith(modulePath) }?.let {
                currentModule = module
            }
        }
        DialogBuilder()
            .title(provideDialogTitleText())
            .centerPanel(
                JPanel().apply {
                    add(buildComboBoxOfModuleList(project))
                }
            )
            .apply {
                addCancelAction()
                addOkAction().setText(provideDialogActionOkText())
                setOkOperation {
                    dialogWrapper.close(DialogWrapper.OK_EXIT_CODE)
                    onDialogActionOkInvoked(
                        project,
                        selectedFileQueue,
                        getModulePath(currentModule!!),
                        getModulePath(targetModule!!)
                    )
                }
                show()
            }
    }

    private fun getModuleList(project: Project): MutableList<Module> {
        val modules = ModuleManager.getInstance(project).modules
        return modules.filterNot { it.name == project.name }.toMutableList()
    }

    private fun buildComboBoxOfModuleList(project: Project): ComboBox<String> {
        val moduleList = getModuleList(project)
        moduleList.remove(currentModule)
        val comboBox = ComboBox<String>()
        moduleList
            .map { "$it" }
            .forEach { comboBox.addItem(it) }
        // Remember latest selection
        if (!moduleList.contains(targetModule)) {
            targetModule = moduleList.first()
            comboBox.setSelectedIndex(0)
        } else {
            comboBox.setSelectedIndex(moduleList.indexOf(targetModule))
        }
        comboBox.addActionListener {
            val index = (it.source as ComboBox<*>).selectedIndex
            targetModule = moduleList[index]
        }
        comboBox.setMinimumAndPreferredWidth(360)
        return comboBox
    }

    private fun getModulePath(module: Module): String = "${module.project.basePath}/${module.name}"

    private fun getSelectedFileQueue(context: DataContext): Queue<VirtualFile> {
        val files = VIRTUAL_FILE_ARRAY.getData(context)
        return LinkedList(if (files != null) listOf(*files) else emptyList())
    }

    fun findPsiFile(project: Project, file: VirtualFile): PsiFile? =
        PsiManager.getInstance(project).findFile(file)

    fun findPsiDirectory(project: Project, file: VirtualFile): PsiDirectory? =
        PsiManager.getInstance(project).findDirectory(file)

    fun mkdirs(project: Project, path: String): PsiDirectory? =
        WriteAction.compute<PsiDirectory, RuntimeException> {
            try {
                return@compute DirectoryUtil.mkdirs(PsiManager.getInstance(project), path)
            } catch (e: Exception) {
                return@compute null
            }
        }

    fun invokeLater(project: Project, runnable: Runnable) {
        ApplicationManager.getApplication().invokeLater(
            runnable,
            ModalityState.defaultModalityState(),
            project.disposed
        )
    }

    fun showErrorHint(project: Project, title: String, message: String) {
        CommonRefactoringUtil.showErrorHint(project, null, title, message, null)
    }
}