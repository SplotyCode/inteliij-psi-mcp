package de.scandurra.inteliijpsimcp

import com.intellij.find.findUsages.FindUsagesManager
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class UsageItem(
    val filePath: String,
    val line: Int,
    val column: Int,
    val context: String
)

data class FindUsagesResult(
    val count: Int,
    val symbolText: String?,
    val symbolKind: String?,
    val items: List<UsageItem>,
    val timedOut: Boolean
)

class JuniePhpToolset : McpToolset {
    @McpTool
    @McpDescription(
        description = """
            Returns semantic usages for a PHP symbol at file:line:column.
            Scope is project-wide. Use for navigation, rankings and safe previews.
        """
    )
    suspend fun find_usages(
        @McpDescription(description = "Path relative to project root or absolute")
        filePath: String,
        @McpDescription(description = "1-based line number") line: Int,
        @McpDescription(description = "1-based column number") column: Int,
        @McpDescription(description = "Maximum results to return") maxResults: Int = 500,
        @McpDescription(description = "Timeout in milliseconds") timeoutMs: Int = 10_000
    ): FindUsagesResult = withContext(Dispatchers.Default) {
        val project: Project = ProjectManager.getInstance().getOpenProjects()[0];


        val usages: List<UsageItem>? = withTimeoutOrNull(timeoutMs.toLong()) {
            ReadAction.compute<List<UsageItem>?, RuntimeException> {
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                    ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
                    ?: return@compute null

                val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute null
                val doc = FileDocumentManager.getInstance().getDocument(vFile, project) ?: return@compute null

                val safeLine = (line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
                val offset = doc.getLineStartOffset(safeLine) + (column - 1).coerceAtLeast(0)

                val leaf = psiFile.findElementAt(offset) ?: return@compute null
                val target = PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false) ?: leaf

                val manager = FindUsagesManager(project)
                val handler = manager.getFindUsagesHandler(target, false) ?: return@compute emptyList()

                val options: FindUsagesOptions = handler.getFindUsagesOptions()
                val out = mutableListOf<UsageItem>()

                handler.processElementUsages(target, { usage: UsageInfo ->
                    val uFile = usage.virtualFile ?: return@processElementUsages true
                    val uDoc = FileDocumentManager.getInstance().getDocument(uFile, project) ?: return@processElementUsages true

                    val navOffset = usage.navigationOffset
                    val uLine = uDoc.getLineNumber(navOffset)
                    val uCol = navOffset - uDoc.getLineStartOffset(uLine)
                    val lineText = uDoc.charsSequence.subSequence(
                        uDoc.getLineStartOffset(uLine),
                        uDoc.getLineEndOffset(uLine)
                    ).toString().trim()

                    out += UsageItem(
                        filePath = uFile.path,
                        line = uLine + 1,
                        column = uCol + 1,
                        context = lineText
                    )
                    out.size < maxResults
                }, options)

                out
            }
        }

        val (symText, symKind) = ReadAction.compute<Pair<String?, String?>, RuntimeException> {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
                ?: return@compute null to null
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute null to null
            val doc = FileDocumentManager.getInstance().getDocument(vFile, project) ?: return@compute null to null
            val off = doc.getLineStartOffset((line - 1).coerceAtLeast(0)) + (column - 1).coerceAtLeast(0)
            val leaf = psiFile.findElementAt(off)
            val named = PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
            (named?.name ?: leaf?.text) to (named?.javaClass?.simpleName ?: leaf?.javaClass?.simpleName)
        }

        FindUsagesResult(
            count = usages?.size ?: 0,
            symbolText = symText,
            symbolKind = symKind,
            items = usages ?: emptyList(),
            timedOut = usages == null
        )
    }
}
