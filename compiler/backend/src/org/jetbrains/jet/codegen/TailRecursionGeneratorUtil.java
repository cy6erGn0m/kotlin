/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.codegen;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.asm4.Type;
import org.jetbrains.asm4.commons.InstructionAdapter;
import org.jetbrains.jet.codegen.context.MethodContext;
import org.jetbrains.jet.codegen.state.GenerationState;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.calls.model.*;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.jetbrains.asm4.Opcodes.ACC_PRIVATE;
import static org.jetbrains.jet.codegen.AsmUtil.pushDefaultValueOnStack;
import static org.jetbrains.jet.codegen.CodegenUtil.isNullableType;
import static org.jetbrains.jet.lang.resolve.BindingContext.RESOLVED_CALL;
import static org.jetbrains.jet.lang.resolve.BindingContext.TAIL_RECURSION_CALL;

public class TailRecursionGeneratorUtil {

    private static final boolean IGNORE_ANNOTATION_ABSENCE = false;

    @NotNull
    private final MethodContext context;
    @NotNull
    private final ExpressionCodegen codegen;
    @NotNull
    private final InstructionAdapter v;
    @NotNull
    private final GenerationState state;

    public TailRecursionGeneratorUtil(
            @NotNull MethodContext context,
            @NotNull ExpressionCodegen codegen,
            @NotNull InstructionAdapter v,
            @NotNull GenerationState state
    ) {
        this.context = context;
        this.codegen = codegen;
        this.v = v;
        this.state = state;
    }

    public boolean isTailRecursion(@NotNull JetCallExpression expression) {
        return isRecursion(expression) && isTailRecursiveCall(expression);
    }

    public static boolean hasTailRecursiveAnnotation(DeclarationDescriptor descriptor) {
        if (IGNORE_ANNOTATION_ABSENCE) {
            return true;
        }

        ClassDescriptor tailRecursive = KotlinBuiltIns.getInstance().getBuiltInClassByName(FqName.fromSegments(Arrays.asList("jet", "TailRecursive")).shortName());
        for (AnnotationDescriptor annotation : descriptor.getOriginal().getAnnotations()) {
            ClassifierDescriptor declarationDescriptor = annotation.getType().getConstructor().getDeclarationDescriptor();
            if (declarationDescriptor != null && declarationDescriptor.equals(tailRecursive)) {
                return true;
            }
        }

        return false;
    }

    @NotNull
    public static List<JetCallExpression> findRecursiveCalls(@NotNull DeclarationDescriptor descriptor, @NotNull GenerationState state) {
        List<JetCallExpression> tailRecursionsFound = new ArrayList<JetCallExpression>();
        Collection<JetCallExpression> calls = state.getBindingTrace().getKeys(BindingContext.TAIL_RECURSION_CALL);
        for (JetCallExpression callExpression : calls) {
            ResolvedCall<? extends CallableDescriptor> resolvedCall = resolve(callExpression, state.getBindingContext());
            if (resolvedCall != null) {
                if (resolvedCall.getCandidateDescriptor().equals(descriptor)) {
                    tailRecursionsFound.add(callExpression);
                }
            }
        }

        return tailRecursionsFound;
    }

    @Nullable
    private static ResolvedCall<? extends CallableDescriptor> resolve(@NotNull JetCallExpression callExpression, BindingContext context) {
        JetExpression callee = callExpression.getCalleeExpression();
        return callee == null ? null : context.get(RESOLVED_CALL, callee);
    }

    private boolean isRecursion(@NotNull JetCallExpression callExpression) {
        ResolvedCall<? extends CallableDescriptor> resolvedCall = resolve(callExpression, state.getBindingContext());
        if (resolvedCall != null && context.getContextDescriptor().equals(resolvedCall.getCandidateDescriptor())) {
            return true;
        }

        return false;
    }

    private static boolean verifyRecursionAgainstTryFinally(JetCallExpression expression) {
        return traceToRoot(expression, TRY_CATCH_FINALLY_ONLY, new TraversalVisitor<Boolean>() {
            @Nullable
            @Override
            public Boolean visit(@NotNull PsiElement parent, @NotNull PsiElement element) {
                return testTryElement(getTry(parent), element) ? null : false;
            }
        }, true);
    }

    private static JetTryExpression getTry(PsiElement finallyOrCatch) {
        return (JetTryExpression) JetPsiUtil.getParentByTypeAndPredicate(finallyOrCatch, JetTryExpression.class, Predicates.<PsiElement>alwaysTrue(), false);
    }

    private static boolean testTryElement(@NotNull JetTryExpression tryExpression, @NotNull PsiElement element) {
        JetFinallySection finallyBlock = tryExpression.getFinallyBlock();
        if (finallyBlock != null && isChildrenOf(finallyBlock, element)) {
            return true;
        }
        for (JetCatchClause catchClause : tryExpression.getCatchClauses()) {
            if (isChildrenOf(catchClause, element)) {
                return finallyBlock == null;
            }
        }
        return false;
    }

    private static boolean isChildrenOf(@NotNull final PsiElement possibleParent, @NotNull PsiElement element) {
        return element == possibleParent || traceToRoot(element, Predicates.<PsiElement>alwaysTrue(), new TraversalVisitor<Boolean>() {
            @Nullable
            @Override
            public Boolean visit(@NotNull PsiElement parent, @NotNull PsiElement e) {
                return parent == possibleParent ? true : null;
            }
        }, false);
    }

    private static boolean isTailRecursiveCall(@NotNull final JetCallExpression expression) {
        return traceToRoot(expression, Predicates.<PsiElement>alwaysTrue(), new TraversalVisitor<Boolean>() {
            @Nullable
            @Override
            public Boolean visit(@NotNull PsiElement parent, @NotNull PsiElement element) {
                if (parent instanceof JetFunction) {
                    return true;
                } else if (parent instanceof JetQualifiedExpression && element == expression) {
                    JetQualifiedExpression qualifiedExpression = (JetQualifiedExpression) parent;
                    if (qualifiedExpression.getReceiverExpression() instanceof JetThisExpression) {
                        return null;
                    } else {
                        return false;
                    }
                } else if (parent instanceof JetIfExpression && element instanceof JetContainerNode) {
                    JetIfExpression ifExpression = (JetIfExpression) parent;
                    JetContainerNode me = (JetContainerNode) element;
                    if (!isThenOrElse(ifExpression, me.getLastChild())) {
                        return false;
                    }
                } else if (parent instanceof JetWhenEntry) {
                    JetWhenEntry whenEntry = (JetWhenEntry) parent;
                    JetExpression entryExpression = whenEntry.getExpression();
                    if (entryExpression == null) {
                        return false;
                    }
                    if (!isChildrenOf(entryExpression, element)) {
                        return false;
                    }
                } else if (parent instanceof JetWhenExpression) {
                    JetWhenExpression when = (JetWhenExpression) parent;
                    JetExpression subjectExpression = when.getSubjectExpression();
                    if (subjectExpression != null && isChildrenOf(subjectExpression, element)) {
                        return false;
                    }
                } else if (parent instanceof JetBlockExpression) {
                    JetElement last = findLastJetElement(parent.getLastChild());
                    if (last == element) { // if last statement
                        // do nothing, continue trace
                    } else if (last instanceof JetReturnExpression) { // check if last statement before void return
                        JetReturnExpression returnExpression = (JetReturnExpression) last;

                        if (returnExpression.getReturnedExpression() != null) {
                            return false; // if there is return expression then no tail recursion here
                        }

                        return findLastJetElement(returnExpression.getPrevSibling()) == element; // our branch is exact before void return
                    } else { // our branch is not last in the block so there is no tail recursion here
                        return false;
                    }
                } else if (parent instanceof JetCatchClause || parent instanceof JetFinallySection || parent instanceof JetTryExpression) {
                    if (!testTryElement(getTry(parent), element)) {
                        return false;
                    }
                } else if (parent instanceof JetContainerNode) {
                    // do nothing, skip it
                } else if (parent instanceof JetReturnExpression) {
                    JetReturnExpression returnExpression = (JetReturnExpression) parent;
                    return returnExpression.getReturnedExpression() == expression;
                } else { // including any jet loops/etc, return and etc
                    return false; // other node types
                }

                return null;
            }
        }, true) && verifyRecursionAgainstTryFinally(expression);
    }

    private static boolean isFunctionElement(PsiElement element) {
        return element instanceof JetFunction;
    }

    private static boolean isThenOrElse(JetIfExpression ifExpression, PsiElement child) {
        return ifExpression.getThen() == child || ifExpression.getElse() == child;
    }

    public StackValue generateTailRecursion(ResolvedCall<? extends CallableDescriptor> resolvedCall, JetCallExpression callExpression) {
        CallableDescriptor fd = resolvedCall.getResultingDescriptor();
        assert fd instanceof FunctionDescriptor;
        CallableMethod callable = (CallableMethod) codegen.resolveToCallable((FunctionDescriptor) fd, false);
        List<Type> types = callable.getValueParameterTypes();

        List<ResolvedValueArgument> valueArguments = resolvedCall.getValueArgumentsByIndex();

        boolean generateNullChecks = AsmUtil.getVisibilityAccessFlag((MemberDescriptor) fd) != ACC_PRIVATE;

        List<ValueParameterDescriptor> descriptorsStored = new ArrayList<ValueParameterDescriptor>(valueArguments.size());
        for (ValueParameterDescriptor parameterDescriptor : fd.getValueParameters()) {
            ResolvedValueArgument arg = valueArguments.get(parameterDescriptor.getIndex());
            Type type = types.get(parameterDescriptor.getIndex());

            if (arg instanceof ExpressionValueArgument) {
                ExpressionValueArgument ev = (ExpressionValueArgument) arg;
                ValueArgument argument = ev.getValueArgument();
                JetExpression argumentExpression = argument == null ? null : argument.getArgumentExpression();

                if (argumentExpression instanceof JetSimpleNameExpression) {
                    JetSimpleNameExpression nameExpression = (JetSimpleNameExpression) argumentExpression;
                    if (nameExpression.getReferencedNameAsName().equals(parameterDescriptor.getName())) {
                        // do nothing: we shouldn't store argument to itself again
                        continue;
                    }
                }

                codegen.gen(argumentExpression, type);
            } else if (arg instanceof DefaultValueArgument) { // what case is it?
                pushDefaultValueOnStack(type, v);
            } else if (arg instanceof VarargValueArgument) {
                VarargValueArgument valueArgument = (VarargValueArgument) arg;
                codegen.genVarargs(parameterDescriptor, valueArgument);
            }
            else {
                throw new UnsupportedOperationException();
            }

            descriptorsStored.add(parameterDescriptor);
        }

        // we can't store values to the variables in the loop above because it will affect expressions evaluation
        for (ValueParameterDescriptor parameterDescriptor : Lists.reverse(descriptorsStored)) {
            JetType type = parameterDescriptor.getReturnType();
            Type asmType = types.get(parameterDescriptor.getIndex());
            int index = getParameterVariableIndex(parameterDescriptor, callExpression);

            if (type != null && !isNullableType(type)) {
                if (asmType.getSort() == Type.OBJECT || asmType.getSort() == Type.ARRAY) {
                    v.dup();
                    v.visitLdcInsn(parameterDescriptor.getName().asString());
                    v.invokestatic("jet/runtime/Intrinsics", "checkParameterIsNotNull", "(Ljava/lang/Object;Ljava/lang/String;)V");
                }
            }

            v.store(index, asmType);
        }

        v.goTo(context.getMethodStartLabel());


        state.getBindingTrace().record(TAIL_RECURSION_CALL, callExpression);

        return StackValue.none();
    }

    private int getParameterVariableIndex(ValueParameterDescriptor parameterDescriptor, PsiElement node) {
        int index = codegen.lookupLocalIndex(parameterDescriptor);
        if (index == -1) {
            index = codegen.lookupLocalIndex(parameterDescriptor.getOriginal());
        }

        if (index == -1) {
            throw new CompilationException("Failed to obtain parameter index: " + parameterDescriptor.getName(), null, node);
        }

        return index;
    }

    @Nullable
    private static JetElement findLastJetElement(@Nullable PsiElement rightNode) {
        PsiElement node = rightNode;
        while (node != null) {
            if (node instanceof JetElement) {
                return (JetElement) node;
            }
            node = node.getPrevSibling();
        }

        return null;
    }

    @NotNull
    private static <T> T traceToRoot(@NotNull PsiElement element, @NotNull Predicate<PsiElement> filterParent, @NotNull TraversalVisitor<T> visitor, @NotNull T def) {
        do {
            PsiElement parent = element.getParent();
            if (parent == null) {
                return def;
            }

            if (filterParent.apply(parent)) {
                T result = visitor.visit(parent, element);
                if (result != null) {
                    return result;
                }
            }

            element = parent;
        } while (!isFunctionElement(element));

        return def;
    }

    private interface TraversalVisitor<T> {
        @Nullable
        T visit(@NotNull PsiElement parent, @NotNull PsiElement element);
    }

    private static final Predicate<PsiElement> TRY_CATCH_FINALLY_ONLY = new Predicate<PsiElement>() {
        @Override
        public boolean apply(@Nullable PsiElement element) {
            return element instanceof JetTryExpression || element instanceof JetCatchClause || element instanceof JetFinallySection;
        }
    };
}
