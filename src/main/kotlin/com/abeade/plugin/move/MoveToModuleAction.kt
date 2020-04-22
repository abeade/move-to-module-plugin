package com.abeade.plugin.move

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandler
import java.util.*

internal class MoveToModuleAction : BaseAction() {

    private companion object {

        private const val ERROR_HINT_TITLE = "Move Failed"
    }

    private var moveProcessor: CachedMoveProcessor? = null

    override fun isEnable(event: AnActionEvent): Boolean {
        // If every single PsiElement can move, then return true
        val elements = BaseRefactoringAction.getPsiElementArray(event.dataContext)
        return elements.all { MoveHandler.canMove(arrayOf(it), null) }
    }

    override fun provideDialogTitleText(): String = "Move resources to module"

    override fun provideDialogActionOkText(): String = "Move"

    override fun onDialogActionOkInvoked(
        project: Project,
        queue: Queue<List<VirtualFile>>,
        currentModulePath: String,
        targetModulePath: String
    ) {
        val currentVirtualFiles = queue.poll() ?: return
        val targetDirectoryPath = currentVirtualFiles.first().parent.path.replace(currentModulePath, targetModulePath)
        val targetPsiDirectory = mkdirs(project, targetDirectoryPath)
        if (targetPsiDirectory == null) {
            showErrorHint(
                project,
                ERROR_HINT_TITLE,
                "targetPsiDirectory null, path: " + currentVirtualFiles.first().path
            )
            onDialogActionOkInvoked(project, queue, currentModulePath, targetModulePath)
            return
        }
        val currentPsiElement: Array<PsiElement?> = if (currentVirtualFiles.first().isDirectory) {
            arrayOf(findPsiDirectory(project, currentVirtualFiles.first()))
        } else {
            findPsiFiles(project, currentVirtualFiles).toTypedArray()
        }
        if (currentPsiElement.contains(null)) {
            showErrorHint(
                project,
                ERROR_HINT_TITLE,
                "currentPsiElement null, path: " + currentVirtualFiles.first().path
            )
            onDialogActionOkInvoked(project, queue, currentModulePath, targetModulePath)
            return
        }
        moveProcessor = CachedMoveProcessor(
            project,
            currentPsiElement,
            targetPsiDirectory,
                true,
                true,
                true,
            MoveCallback {
                invokeLater(project, Runnable { onDialogActionOkInvoked(project, queue, currentModulePath, targetModulePath) })
            }
        ).apply {
            setPrepareSuccessfulSwingThreadCallback(null)
            setPreviewUsages(true)
            run()
        }
    }
}
