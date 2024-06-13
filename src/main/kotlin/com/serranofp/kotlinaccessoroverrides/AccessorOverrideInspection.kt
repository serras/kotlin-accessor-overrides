package com.serranofp.kotlinaccessoroverrides

import ai.grazie.utils.capitalize
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.fir.declarations.builder.buildSimpleFunction
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.propertyVisitor
import org.jetbrains.kotlin.psi.stubs.elements.KtTokenSets
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlinx.serialization.compiler.resolve.toClassDescriptor

class AccessorOverrideInspection: AbstractKotlinInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        propertyVisitor { property ->
            val context = property.analyze(BodyResolveMode.FULL)

            val propertyName = property.name?.capitalize() ?: return@propertyVisitor
            val identifiers = listOf("get$propertyName", "is$propertyName", "set$propertyName").map(Name::identifier).toSet()
            val modifierElement = property.modifierList?.getModifier(KtTokens.OVERRIDE_KEYWORD) ?: return@propertyVisitor

            val hasProblems = context.diagnostics.forElement(property).any { it.factoryName == "NOTHING_TO_OVERRIDE" }
            if (!hasProblems) return@propertyVisitor

            val parent = property.parent.parent as? KtClassOrObject ?: return@propertyVisitor
            for (superTypeEntry in parent.superTypeListEntries) {
                val superType = context[BindingContext.TYPE, superTypeEntry.typeReference] ?: continue
                val scope = superType.toClassDescriptor?.unsubstitutedMemberScope ?: continue

                if ((scope.getFunctionNames() intersect identifiers).isNotEmpty()) {
                    val propertyType = context[BindingContext.VARIABLE, property]?.type ?: continue
                    holder.registerProblem(
                        modifierElement,
                        "Overriding Java accessors using property syntax is not allowed",
                        ProblemHighlightType.GENERIC_ERROR,
                        PropertyToAccessors(property, propertyType),
                    )
                    break
                }
            }
        }

    class PropertyToAccessors(val property: KtProperty, val propertyType: KotlinType): LocalQuickFix {
        override fun getName(): String = "Override accessor functions instead"
        override fun getFamilyName(): String = "Override accessor functions instead"

        override fun applyFix(project: Project, problem: ProblemDescriptor) {
            val propertyName = property.name?.capitalize() ?: return
            val factory = KtPsiFactory(project)

            val setter = property.setter
            if (setter != null) {
                val body = setter.bodyBlockExpression ?: setter.bodyExpression?.text?.let { ": Unit = $it" }
                val newSetter = factory.createFunction("${property.modifierList?.text.orEmpty()} fun set$propertyName(value: $propertyType) $body")
                property.parent.addAfter(newSetter, property)
            }

            val getter = property.getter
            if (getter != null) {
                val body = getter.bodyBlockExpression ?: getter.bodyExpression?.text?.let { "= $it" }
                val newGetter = factory.createFunction("${property.modifierList?.text.orEmpty()} fun get$propertyName(): $propertyType $body")
                property.parent.addAfter(newGetter, property)
            }

            property.delete()
        }

    }
}