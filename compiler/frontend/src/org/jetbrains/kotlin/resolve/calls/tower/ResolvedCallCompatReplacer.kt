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

package org.jetbrains.kotlin.resolve.calls.tower

import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.synthetic.SyntheticMemberDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.annotations.argumentValue
import org.jetbrains.kotlin.resolve.calls.inference.returnTypeOrNothing
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.MutableResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tower.NewResolutionOldInference.MyCandidate
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.SyntheticScopes
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import java.lang.IllegalStateException

internal object ResolvedCallCompatReplacer {
    fun replace(
            syntheticScopes: SyntheticScopes,
            candidates: Collection<MyCandidate>
    ): Collection<MyCandidate> = candidates.map { replaceCandidate(it, syntheticScopes) }

    private fun replaceCandidate(
            candidate: MyCandidate,
            syntheticScopes: SyntheticScopes): MyCandidate {
        val call = candidate.resolvedCall
        val receiver = call.dispatchReceiver ?: return candidate
        val resolvedCall = candidate.resolvedCall
        val callDescriptor = resolvedCall.candidateDescriptor ?: return candidate
        val receiverDescriptor = receiver.type.constructor.declarationDescriptor as? ClassDescriptor ?: return candidate
        val compatClasses = findCompatClasses(receiverDescriptor)

        // Find appropriate compat class/method and replace the call
        return compatClasses
                       .mapNotNull { (origin, compat) -> findCompatMethod(compat, callDescriptor, syntheticScopes, origin, receiver) }
                       .map { MyCandidate(candidate.diagnostics, createResolvedCall(callDescriptor, it, resolvedCall, receiver)) }
                       .singleOrNull() ?: candidate
    }

    private fun findCompatAnnotationOnType(t: KotlinType): AnnotationDescriptor? =
            t.constructor.declarationDescriptor?.annotations?.firstOrNull { it.fqName == annotationFqName }

    // Find all compat annotations on the type and its supertypes
    private fun findCompatAnnotations(origin: ClassDescriptor): HashMap<ClassDescriptor, AnnotationDescriptor> {
        val type = origin.defaultType
        val allTypes = type.constructor.supertypes + type
        val res = hashMapOf<ClassDescriptor, AnnotationDescriptor>()
        for (t in allTypes) {
            val annotation = findCompatAnnotationOnType(t)
            val cd = t.constructor.declarationDescriptor as? ClassDescriptor ?: throw IllegalStateException("$t must be class or interface")
            if (annotation != null) res[cd] = annotation
        }
        return res
    }

    // Find all compat classes of the type and its supertypes including interfaces
    // TODO: Replace IllegalStateException with error reporting
    private fun findCompatClasses(origin: ClassDescriptor): Map<ClassDescriptor, ClassDescriptor> {
        val res = hashMapOf<ClassDescriptor, ClassDescriptor>()
        for ((t, annotation) in findCompatAnnotations(origin)) {
            val annotationValue = annotation.argumentValue("value") ?: throw IllegalStateException("Compat annotation must have value")
            val valueString = annotationValue as? String ?: throw IllegalStateException("$annotationValue must be string")

            var packageName = ""
            var className = valueString
            if (valueString.contains('.')) {
                packageName = valueString.substring(0, valueString.lastIndexOf('.'))
                className = valueString.substring(valueString.lastIndexOf('.') + 1)
            }

            res[t] = t.module.getPackage(FqName(packageName))
                             .memberScope
                             .getContributedClassifier(
                                     Name.identifier(className),
                                     NoLookupLocation.WHEN_FIND_BY_FQNAME
            ) as? ClassDescriptor ?: throw IllegalStateException("Compat must be a class")
        }
        return res
    }

    private fun functionSignaturesEqual(
            call: CallableDescriptor,
            candidate: CallableDescriptor,
            scope: MemberScope,
            syntheticScopes: SyntheticScopes,
            originType: KotlinType? = null,
            receiverType: KotlinType? = null
    ): Boolean {
        if (call.name != candidate.name) return false
        if (!KotlinTypeChecker.DEFAULT.equalTypes(call.returnTypeOrNothing, candidate.returnTypeOrNothing)) return false
        if (call.typeParameters.size != candidate.typeParameters.size) return false
        if (call.typeParameters.indices.any { call.typeParameters[it] != candidate.typeParameters[it] }) return false
        if (call.valueParameters.size + (if (originType != null) 1 else 0) != candidate.valueParameters.size) return false
        if (originType != null && !KotlinTypeChecker.DEFAULT.equalTypes(originType, candidate.valueParameters[0].type)) return false

        for (i in call.valueParameters.indices) {
            val j = if (originType != null) i + 1 else i
            if (KotlinTypeChecker.DEFAULT.equalTypes(candidate.valueParameters[j].type, call.valueParameters[i].type)) continue
            if (call.valueParameters[i].type.isFunctionType) {
                val synthetics = syntheticScopes.scopes.flatMap {
                    if (receiverType == null) it.getSyntheticStaticFunctions(scope)
                    else it.getSyntheticMemberFunctions(listOf(receiverType))
                }

                val originFun = synthetics.singleOrNull {
                    functionSignaturesEqual(call, it, scope, syntheticScopes)
                } as? SyntheticMemberDescriptor<*> ?: return false

                val realParam = (originFun.baseDescriptorForSynthetic as? FunctionDescriptor)?.valueParameters?.get(i) ?: return false
                if (!KotlinTypeChecker.DEFAULT.equalTypes(candidate.valueParameters[j].type, realParam.type)) return false
            }
            else return false
        }
        return true
    }

    private fun createCall(
            resolvedCall: MutableResolvedCall<*>,
            callDescriptor: CallableDescriptor,
            receiver: ReceiverValue
    ): Pair<ValueArgument, Call> {
        // Replace the call
        val psiFactory = KtPsiFactory(resolvedCall.call.callElement, markGenerated = false)
        val calleeExpression = psiFactory.createSimpleName(callDescriptor.name.asString())

        val receiverExpr: KtExpression =
                if (receiver is ExpressionReceiver) receiver.expression
                else {
                    val implicitThisDescriptor = (receiver as? ImplicitReceiver)?.declarationDescriptor ?: error("Implicit this might be either class or closure")
                    when (implicitThisDescriptor) {
                        is CallableDescriptor -> psiFactory.createImplicitThisExpression(implicitThisDescriptor)
                        is ClassDescriptor -> psiFactory.createImplicitThisExpression(implicitThisDescriptor)
                        else -> error("Implicit this might be either class or closure")
                    }
                }

        val originValue = CallMaker.makeValueArgument(receiverExpr)
        val compatCall = CallMaker.makeCall(
                resolvedCall.call.callElement,
                null,
                resolvedCall.call.callOperationNode,
                calleeExpression,
                listOf(originValue) + resolvedCall.call.valueArguments
        )
        return Pair(originValue, compatCall)
    }

    private fun createResolvedCall(
            callDescriptor: CallableDescriptor,
            compatMethod: CallableDescriptor,
            resolvedCall: MutableResolvedCall<*>,
            receiver: ReceiverValue
    ): ResolvedCallImpl<CallableDescriptor> {
        val (originValue, compatCall) = createCall(resolvedCall, callDescriptor, receiver)
        val compatResolverCall = ResolvedCallImpl(
                compatCall,
                compatMethod,
                null,
                null,
                ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
                resolvedCall.knownTypeParametersSubstitutor,
                resolvedCall.trace,
                resolvedCall.tracingStrategy,
                resolvedCall.dataFlowInfoForArguments
        )

        compatResolverCall.recordValueArgument(compatMethod.valueParameters.first(), ExpressionValueArgument(originValue))

        resolvedCall.valueArguments.forEach {
            compatResolverCall.recordValueArgument(compatMethod.valueParameters[it.key.index + 1], it.value)
        }

        compatResolverCall.setStatusToSuccess()
        return compatResolverCall
    }

    private fun findCompatMethod(
            compat: ClassDescriptor,
            callDescriptor: CallableDescriptor,
            syntheticScopes: SyntheticScopes,
            origin: ClassDescriptor,
            receiver: ReceiverValue
    ): CallableDescriptor? {
        var compatMethod: CallableDescriptor? = null
        // Find method in compat class
        for (compatMethodDescriptor in compat.staticScope.getDescriptorsFiltered { it == callDescriptor.name }) {
            if (compatMethodDescriptor !is FunctionDescriptor) continue
            val equalMethods = functionSignaturesEqual(
                    callDescriptor,
                    compatMethodDescriptor,
                    compat.staticScope,
                    syntheticScopes,
                    origin.defaultType,
                    receiver.type
            )
            if (!equalMethods) continue
            compatMethod = syntheticScopes.scopes.flatMap { it.getSyntheticStaticFunctions(compat.staticScope) }.firstOrNull {
                functionSignaturesEqual(callDescriptor, it, compat.staticScope, syntheticScopes, origin.defaultType)
            } ?: compatMethodDescriptor
        }
        return compatMethod
    }

    private fun KtPsiFactory.createImplicitThisExpression(descriptor: CallableDescriptor) = KtImplicitThisExpression(createThisExpression().node, descriptor)
    private fun KtPsiFactory.createImplicitThisExpression(descriptor: ClassDescriptor) = KtImplicitThisExpression(createThisExpression().node, descriptor)

    private val annotationFqName = FqName("kotlin.annotations.jvm.internal.Compat")
}