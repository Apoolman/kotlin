/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.test

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.translate.callTranslator.CallTranslator
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.reference.ReferenceTranslator
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope

class JSTestGenerator(val context: TranslationContext) {

    fun generateTestCalls(moduleDescriptor: ModuleDescriptor) {
        generateTestCalls(moduleDescriptor, FqName.ROOT)
    }

    private fun generateTestCalls(moduleDescriptor: ModuleDescriptor, packageName: FqName) {
        val packageFunction = JsFunction(context.scope(), JsBlock(), "${packageName.asString()} package suite function")

        for (packageDescriptor in moduleDescriptor.getPackage(packageName).fragments) {
            if (DescriptorUtils.getContainingModule(packageDescriptor) !== moduleDescriptor) continue

            packageDescriptor.getMemberScope().getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS, MemberScope.ALL_NAME_FILTER).forEach {
                if (it is ClassDescriptor) {
                    generateTestFunctions(it, packageFunction)
                }
            }
        }

        if (!packageFunction.body.isEmpty) {
            val suiteName = JsStringLiteral(packageName.asString())
            context.addTopLevelStatement(JsInvocation(suiteRef, suiteName, JsBooleanLiteral(false), packageFunction).makeStmt())
        }

        for (subpackageName in moduleDescriptor.getSubPackagesOf(packageName, MemberScope.ALL_NAME_FILTER)) {
            generateTestCalls(moduleDescriptor, subpackageName)
        }
    }

    private fun generateTestFunctions(classDescriptor: ClassDescriptor, parentFun: JsFunction) {
        if (classDescriptor.modality === Modality.ABSTRACT) return

        val suiteFunction = JsFunction(context.scope(), JsBlock(), "suite function")

        val descriptors = classDescriptor.unsubstitutedMemberScope
                .getContributedDescriptors(DescriptorKindFilter.FUNCTIONS, MemberScope.ALL_NAME_FILTER)
                .filterIsInstance<FunctionDescriptor>()

        val beforeFunctions = descriptors.filter { it.isBefore }
        val afterFunctions = descriptors.filter { it.isAfter }

        descriptors.forEach {
            if (it.isTest) {
                generateCodeForTestMethod(it, beforeFunctions, afterFunctions, classDescriptor, suiteFunction)
            }
        }

        if (!suiteFunction.body.isEmpty) {
            val suiteName = JsStringLiteral(classDescriptor.name.toString())

            parentFun.body.statements += JsInvocation(suiteRef, suiteName, JsBooleanLiteral(classDescriptor.isIgnored), suiteFunction).makeStmt()
        }
    }

    private fun generateCodeForTestMethod(functionDescriptor: FunctionDescriptor,
                                          beforeDescriptors: List<FunctionDescriptor>,
                                          afterDescriptors: List<FunctionDescriptor>,
                                          classDescriptor: ClassDescriptor,
                                          parentFun: JsFunction) {
        val functionToTest = generateTestFunction(functionDescriptor, beforeDescriptors, afterDescriptors, classDescriptor, parentFun.scope)

        val testName = JsStringLiteral(functionDescriptor.name.toString())
        parentFun.body.statements += JsInvocation(testRef, testName, JsBooleanLiteral(functionDescriptor.isIgnored), functionToTest).makeStmt()
    }

    private fun generateTestFunction(functionDescriptor: FunctionDescriptor,
                                     beforeDescriptors: List<FunctionDescriptor>,
                                     afterDescriptors: List<FunctionDescriptor>,
                                     classDescriptor: ClassDescriptor,
                                     scope: JsScope): JsFunction {
        val functionToTest = JsFunction(scope, JsBlock(), "test function")
        val innerContext = context.contextWithScope(functionToTest)

        val expression = ReferenceTranslator.translateAsTypeReference(classDescriptor, innerContext)
        val testClass = JsNew(expression)
        val classVal = innerContext.defineTemporary(testClass)

        fun FunctionDescriptor.buildCall() = CallTranslator.buildCall(context, this, emptyList(), classVal).makeStmt()

        functionToTest.body.statements += beforeDescriptors.map { it.buildCall() }

        functionToTest.body.statements += if (afterDescriptors.isEmpty()) {
            functionDescriptor.buildCall()
        }
        else {
            JsTry(JsBlock(functionDescriptor.buildCall()), listOf(), JsBlock(afterDescriptors.map { it.buildCall() }))
        }

        return functionToTest
    }

    private val suiteRef: JsExpression by lazy { findFunction("suite") }
    private val testRef: JsExpression by lazy { findFunction("test") }

    private fun findFunction(name: String): JsExpression {
        val descriptor = DescriptorUtils.getFunctionByNameOrNull(
                context.currentModule.getPackage(FqNameUnsafe("kotlin.test").toSafe()).memberScope,
                Name.identifier(name)) ?: return JsNameRef(name, JsNameRef("Kotlin"))
        return ReferenceTranslator.translateAsValueReference(descriptor, context)
    }

    private val FunctionDescriptor.isTest
        get() = annotationFinder("Test", "kotlin.test")

    private val DeclarationDescriptor.isIgnored
        get() = annotationFinder("Ignore", "kotlin.test")

    private val FunctionDescriptor.isBefore
        get() = annotationFinder("Before", "kotlin.test")

    private val FunctionDescriptor.isAfter
        get() = annotationFinder("After", "kotlin.test")

    private fun DeclarationDescriptor.annotationFinder(shortName: String, vararg packages: String) = packages.any { packageName ->
        annotations.hasAnnotation(FqName("$packageName.$shortName"))
    }
}
