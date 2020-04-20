package com.abeade.plugin.move

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo

internal class MoveProcessor(
    project: Project?,
    elements: Array<PsiElement?>?,
    newParent: PsiDirectory?,
    searchForReferences: Boolean,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean,
    moveCallback: MoveCallback?
) : MoveFilesOrDirectoriesProcessor(
    project, elements, newParent, searchForReferences, searchInComments,
    searchInNonJavaFiles, moveCallback, null
) {

    var cachedReferenceList: List<PsiReference> = emptyList()
        private set

    override fun findUsages(): Array<UsageInfo> = super.findUsages().also {
        cachedReferenceList = it.mapNotNull { item -> item.reference }
    }
}
