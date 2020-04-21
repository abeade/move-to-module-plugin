package com.abeade.plugin.move

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiReference
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandler
import java.util.*

internal class MoveToModuleAction : BaseAction() {

    private companion object {

        private const val ERROR_HINT_TITLE = "Move Failed"
    }

    private var moveProcessor: CachedMoveProcessor? = null
    private var cachedReferenceList: List<PsiReference> = emptyList()

    override fun isEnable(event: AnActionEvent): Boolean {
        // If every single PsiElement can move, then return true
        val elements = BaseRefactoringAction.getPsiElementArray(event.dataContext)
        return elements.all { MoveHandler.canMove(arrayOf(it), null) }
    }

    override fun provideDialogTitleText(): String = "Move resources to module"

    override fun provideDialogActionOkText(): String = "Move"

    override fun onDialogActionOkInvoked(
        project: Project,
        queue: Queue<VirtualFile>,
        currentModulePath: String,
        targetModulePath: String
    ) {
        val currentVirtualFile = queue.poll() ?: return
        val targetDirectoryPath = currentVirtualFile.parent.path.replace(currentModulePath, targetModulePath)
        val targetPsiDirectory = mkdirs(project, targetDirectoryPath)
        if (targetPsiDirectory == null) {
            showErrorHint(
                project,
                ERROR_HINT_TITLE,
                "targetPsiDirectory null, path: " + currentVirtualFile.path
            )
            onDialogActionOkInvoked(project, queue, currentModulePath, targetModulePath)
            return
        }
        val currentPsiElement = if (currentVirtualFile.isDirectory) {
            findPsiDirectory(project, currentVirtualFile)
        } else {
            findPsiFile(project, currentVirtualFile)
        }
        if (currentPsiElement == null) {
            showErrorHint(
                project,
                ERROR_HINT_TITLE,
                "currentPsiElement null, path: " + currentVirtualFile.path
            )
            onDialogActionOkInvoked(project, queue, currentModulePath, targetModulePath)
            return
        }

        // Little trick, avoid redundant check
        val shouldCheckReferences: Boolean
        if (cachedReferenceList.isEmpty()) {
            shouldCheckReferences = true
        } else {
            var matchAllCacheReferences = true
            for (reference in cachedReferenceList) {
                try {
                    if (!reference.isReferenceTo(currentPsiElement)) {
                        matchAllCacheReferences = false
                        break
                    }
                } catch (_: Throwable) {
                    matchAllCacheReferences = false
                    break
                }
            }
            shouldCheckReferences = !matchAllCacheReferences
        }
        moveProcessor = CachedMoveProcessor(
            project,
            arrayOf(currentPsiElement),
            targetPsiDirectory,
            shouldCheckReferences,
            shouldCheckReferences,
            shouldCheckReferences,
            MoveCallback {
                if (shouldCheckReferences) {
                    cachedReferenceList = moveProcessor?.cachedReferenceList ?: emptyList()
                }
                invokeLater(project, Runnable { onDialogActionOkInvoked(project, queue, currentModulePath, targetModulePath) })
            }
        ).apply {
            setPrepareSuccessfulSwingThreadCallback(null)
            setPreviewUsages(shouldCheckReferences && currentVirtualFile.isWritable)
            run()
        }
    }
}
