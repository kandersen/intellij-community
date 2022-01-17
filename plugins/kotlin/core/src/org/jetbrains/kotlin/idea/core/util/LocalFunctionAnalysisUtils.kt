// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.core.util

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.idea.caches.project.NotUnderContentRootModuleInfo.project
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedSimpleFunctionDescriptor
import java.util.concurrent.Callable

fun analyzeLocalFunctions(
    resolutionFacade: ResolutionFacade,
    file: KtFile,
    bindingContext: BindingContext
): Pair<BindingContext, List<KtFile>> {
    val localFunctions: Set<KtElement> = discoverLocalFunctionsInFragment(resolutionFacade, bindingContext, file)

    val files = localFunctions.map { it.containingKtFile }.toSet()

    val finalContext = resolutionFacade.analyzeWithAllCompilerChecks(files).bindingContext

    return Pair(finalContext, files.toList())
}

fun discoverLocalFunctionsInFragment(resolutionFacade: ResolutionFacade, bindingContext: BindingContext, element: KtFile): Set<KtElement> {
    val result: MutableSet<KtElement> = mutableSetOf()

    val innerContexts = mutableSetOf(bindingContext)

    element.accept(object : KtTreeVisitorVoid() {

        override fun visitExpression(expression: KtExpression) {
            super.visitExpression(expression)

            if (bindingContext.get(BindingContext.EXPRESSION_TYPE_INFO, expression) != null) return

            val bindingContext =  resolutionFacade.analyze(expression)
            innerContexts.add(bindingContext)

            val call = bindingContext.get(BindingContext.CALL, expression) ?: return

            val resolvedCall = bindingContext.get(BindingContext.RESOLVED_CALL, call) ?: return

            if (resolvedCall.resultingDescriptor.visibility == DescriptorVisibilities.LOCAL) {
                result.add((resolvedCall.resultingDescriptor.source as KotlinSourceElement).psi)
            }
        }
    })

    return result
}