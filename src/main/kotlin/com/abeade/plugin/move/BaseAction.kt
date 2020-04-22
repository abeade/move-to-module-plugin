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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.util.CommonRefactoringUtil
import java.util.*
import javax.swing.JPanel

internal abstract class BaseAction : AnAction() {

    private companion object {

        private const val RES_DIRECTORY = "res"
    }

    private var currentModule: Module? = null
    private var targetModule: Module? = null

    abstract fun isEnable(event: AnActionEvent): Boolean

    abstract fun provideDialogTitleText(): String

    abstract fun provideDialogActionOkText(): String

    abstract fun onDialogActionOkInvoked(
            project: Project,
            queue: Queue<List<VirtualFile>>,
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
        val moduleList = project.moduleList
        if (moduleList.size < 2) {
            presentation.isEnabled = false
            return
        }
        // Disable when no selected data
        val selectedFileQueue = VIRTUAL_FILE_ARRAY.getData(event.dataContext)
        if (selectedFileQueue.isNullOrEmpty()) {
            presentation.isEnabled = false
            return
        }
        // Disable when any directory not in resources folder
        if (selectedFileQueue.all { it.isDirectory } && selectedFileQueue.any { it.parent?.name != RES_DIRECTORY }) {
            presentation.isEnabled = false
            return
        }
        // Disable when any file not in resources folder
        if (selectedFileQueue.all { !it.isDirectory } && selectedFileQueue.any { it.parent?.parent?.name != RES_DIRECTORY }) {
            presentation.isEnabled = false
            return
        }
        // Disable when mixing directories and files
        if (selectedFileQueue.any { !it.isDirectory } && selectedFileQueue.any { it.isDirectory }) {
            presentation.isEnabled = false
            return
        }
        // Disable when selected file not located in same module
        for (module in moduleList) {
            var hasMatch = false
            for (file in selectedFileQueue) {
                if (file.path.startsWith(module.modulePath)) {
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
        val moduleList = project.moduleList
        val selectedFileQueue = getSelectedFileQueue(event.dataContext)
        val filePath = selectedFileQueue.first().first().path
        currentModule = moduleList.first { filePath.startsWith(it.modulePath) }
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
                        currentModule!!.modulePath,
                        targetModule!!.modulePath
                    )
                }
                show()
            }
    }

    private fun buildComboBoxOfModuleList(project: Project): ComboBox<String> {
        val moduleList = project.moduleList.sortedBy { it.name }.toMutableList()
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

    private fun getSelectedFileQueue(context: DataContext): Queue<List<VirtualFile>> {
        val files = VIRTUAL_FILE_ARRAY.getData(context)
        val filesByFolder = mutableMapOf<VirtualFile, MutableSet<VirtualFile>>()
        return if (files?.first()?.isDirectory == false) {
            files.forEach {
                filesByFolder.addFile(it)
            }
            filesByFolder.keys.forEach { folder ->
                // Add same resource in qualified folders
                val noQualifiedName = folder.name.substringBefore('-')
                val childs = VfsUtil.getChildren(folder.parent).filter { child ->
                            child != folder && child.name.substringBefore('-') == noQualifiedName
                        }
                filesByFolder[folder]!!.forEach { file ->
                    childs.forEach { childFolder ->
                        val childFile = childFolder.findChild(file.name)
                        childFile?.let { filesByFolder.addFile(it) }
                    }
                }
            }
            LinkedList<List<VirtualFile>>().apply {
                filesByFolder.forEach { this.add(it.value.toList()) }
            }
        } else {
            LinkedList<List<VirtualFile>>().apply {
                add(LinkedList(if (files != null) listOf(*files) else emptyList()))
            }
        }
    }

    private fun MutableMap<VirtualFile, MutableSet<VirtualFile>>.addFile(file: VirtualFile) {
        if (containsKey(file.parent)) {
            this[file.parent]?.add(file)
        } else {
            this[file.parent] = mutableSetOf(file)
        }
    }

    fun findPsiFile(project: Project, file: VirtualFile): PsiFile? =
        PsiManager.getInstance(project).findFile(file)

    fun findPsiFiles(project: Project, files: List<VirtualFile>): List<PsiFile?> =
        files.map { findPsiFile(project, it) }

    fun findPsiDirectory(project: Project, file: VirtualFile): PsiDirectory? =
        PsiManager.getInstance(project).findDirectory(file)

    fun mkdirs(project: Project, path: String): PsiDirectory? =
        WriteAction.compute<PsiDirectory, RuntimeException> {
            try {
                return@compute DirectoryUtil.mkdirs(PsiManager.getInstance(project), path)
            } catch (_: Exception) {
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

    private val Module.modulePath: String
        get() = "${project.basePath}/${name}"

    private val Project.moduleList: MutableList<Module>
        get() = ModuleManager.getInstance(this).modules.filterNot { it.name == name }.toMutableList()
}