package org.jetbrains.jet.lang.cfg;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.JetSemanticServices;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.types.BindingTrace;
import org.jetbrains.jet.lang.types.JetTypeInferrer;
import org.jetbrains.jet.lexer.JetTokens;

import java.util.*;

/**
* @author abreslav
*/
public class JetControlFlowProcessor {

    private final Map<String, Stack<JetElement>> labeledElements = new HashMap<String, Stack<JetElement>>();

    private final JetSemanticServices semanticServices;
    private final JetControlFlowBuilder builder;
    private final BindingTrace trace;

    public JetControlFlowProcessor(JetSemanticServices semanticServices, BindingTrace trace, JetControlFlowBuilder builder) {
        this.semanticServices = semanticServices;
        this.builder = builder;
        this.trace = trace;
    }

    public void generate(@NotNull JetElement subroutineElement, @NotNull JetExpression body) {
        generateSubroutineControlFlow(subroutineElement, Collections.singletonList(body), false);
    }

    public void generateSubroutineControlFlow(@NotNull JetElement subroutineElement, @NotNull List<? extends JetElement> body, boolean preferBlocks) {
        if (subroutineElement instanceof JetNamedDeclaration) {
            JetNamedDeclaration namedDeclaration = (JetNamedDeclaration) subroutineElement;
            enterLabeledElement(JetPsiUtil.safeName(namedDeclaration.getName()), namedDeclaration);
        }
        boolean functionLiteral = subroutineElement instanceof JetFunctionLiteralExpression;
        builder.enterSubroutine(subroutineElement, functionLiteral);
        for (JetElement statement : body) {
            statement.accept(new CFPVisitor(preferBlocks, false));
        }
        builder.exitSubroutine(subroutineElement, functionLiteral);
    }

    private void enterLabeledElement(@NotNull String labelName, @NotNull JetElement labeledElement) {
        Stack<JetElement> stack = labeledElements.get(labelName);
        if (stack == null) {
            stack = new Stack<JetElement>();
            labeledElements.put(labelName, stack);
        }
        stack.push(labeledElement);
    }

    private void exitElement(JetElement element) {
        // TODO : really suboptimal
        for (Iterator<Map.Entry<String, Stack<JetElement>>> mapIter = labeledElements.entrySet().iterator(); mapIter.hasNext(); ) {
            Map.Entry<String, Stack<JetElement>> entry = mapIter.next();
            Stack<JetElement> stack = entry.getValue();
            for (Iterator<JetElement> stackIter = stack.iterator(); stackIter.hasNext(); ) {
                JetElement recorded = stackIter.next();
                if (recorded == element) {
                    stackIter.remove();
                }
            }
            if (stack.isEmpty()) {
                mapIter.remove();
            }
        }
    }

    @Nullable
    private JetElement resolveLabel(@NotNull String labelName, @NotNull JetSimpleNameExpression labelExpression) {
        Stack<JetElement> stack = labeledElements.get(labelName);
        if (stack == null || stack.isEmpty()) {
            semanticServices.getErrorHandler().unresolvedReference(labelExpression);
            return null;
        }
        else if (stack.size() > 1) {
            semanticServices.getErrorHandler().genericWarning(labelExpression.getNode(), "There is more than one label with such a name in this scope");
        }

        JetElement result = stack.peek();
        trace.recordLabelResolution(labelExpression, result);
        return result;
    }

    private class CFPVisitor extends JetVisitor {
        private final boolean preferBlock;
        private final boolean inCondition;

        private CFPVisitor(boolean preferBlock, boolean inCondition) {
            this.preferBlock = preferBlock;
            this.inCondition = inCondition;
        }

        private void value(@NotNull JetElement element, boolean preferBlock, boolean inCondition) {
            CFPVisitor visitor;
            if (this.preferBlock == preferBlock && this.inCondition == inCondition) {
                visitor = this;
            }
            else {
                visitor = new CFPVisitor(preferBlock, inCondition);
            }
            element.accept(visitor);
            exitElement(element);
        }

        @Override
        public void visitParenthesizedExpression(JetParenthesizedExpression expression) {
            JetExpression innerExpression = expression.getExpression();
            if (innerExpression != null) {
                value(innerExpression, false, inCondition);
            }
        }

        @Override
        public void visitThisExpression(JetThisExpression expression) {
            builder.readNode(expression);
        }

        @Override
        public void visitConstantExpression(JetConstantExpression expression) {
            builder.readNode(expression);
        }

        @Override
        public void visitSimpleNameExpression(JetSimpleNameExpression expression) {
            builder.readNode(expression);
        }

        @Override
        public void visitLabelQualifiedExpression(JetLabelQualifiedExpression expression) {
            String labelName = expression.getLabelName();
            JetExpression labeledExpression = expression.getLabeledExpression();
            if (labelName != null && labeledExpression != null) {
                visitLabeledExpression(labelName, labeledExpression);
            }
        }

        private void visitLabeledExpression(@NotNull String labelName, @NotNull JetExpression labeledExpression) {
            JetExpression deparenthesized = JetPsiUtil.deparenthesize(labeledExpression);
            if (deparenthesized != null) {
                enterLabeledElement(labelName, deparenthesized);
                value(labeledExpression, false, inCondition);
            }
        }

        @Override
        public void visitBinaryExpression(JetBinaryExpression expression) {
            IElementType operationType = expression.getOperationReference().getReferencedNameElementType();
            JetExpression right = expression.getRight();
            if (operationType == JetTokens.ANDAND) {
                value(expression.getLeft(), false, true);
                Label resultLabel = builder.createUnboundLabel();
                builder.jumpOnFalse(resultLabel);
                if (right != null) {
                    value(right, false, true);
                }
                builder.bindLabel(resultLabel);
                if (!inCondition) {
                    builder.readNode(expression);
                }
            }
            else if (operationType == JetTokens.OROR) {
                value(expression.getLeft(), false, true);
                Label resultLabel = builder.createUnboundLabel();
                builder.jumpOnTrue(resultLabel);
                if (right != null) {
                    value(right, false, true);
                }
                builder.bindLabel(resultLabel);
                if (!inCondition) {
                    builder.readNode(expression);
                }
            }
            else if (operationType == JetTokens.EQ) {
                JetExpression left = expression.getLeft();
                if (right != null) {
                    value(right, false, false);
                }
                if (left instanceof JetSimpleNameExpression) {
                    builder.writeNode(expression, left);
                }
                else {
                    builder.unsupported(expression);
                }
            }
            else if (JetTypeInferrer.assignmentOperationNames.containsKey(operationType)) {
                JetExpression left = expression.getLeft();
                value(left, false, false);
                if (right != null) {
                    value(right, false, false);
                }
                if (left instanceof JetSimpleNameExpression) {
                    builder.writeNode(expression, left);
                }
                else {
                    builder.unsupported(expression);
                }
            }
            else {
                value(expression.getLeft(), false, false);
                if (right != null) {
                    value(right, false, false);
                }
                value(expression.getOperationReference(), false, false);
                builder.readNode(expression);
            }
        }

        @Override
        public void visitUnaryExpression(JetUnaryExpression expression) {
            IElementType operationType = expression.getOperationSign().getReferencedNameElementType();
            if (JetTokens.LABELS.contains(operationType)) {
                String referencedName = expression.getOperationSign().getReferencedName();
                referencedName = referencedName == null ? " <?>" : referencedName;
                visitLabeledExpression(referencedName.substring(1), expression.getBaseExpression());
            }
            else {
                visitElement(expression);
            }
        }

        @Override
        public void visitIfExpression(JetIfExpression expression) {
            value(expression.getCondition(), false, true);
            Label elseLabel = builder.createUnboundLabel();
            builder.jumpOnFalse(elseLabel);
            JetExpression thenBranch = expression.getThen();
            if (thenBranch != null) {
                value(thenBranch, true, inCondition);
            }
            else {
                builder.readUnit(expression);
            }
            Label resultLabel = builder.createUnboundLabel();
            builder.jump(resultLabel);
            builder.bindLabel(elseLabel);
            JetExpression elseBranch = expression.getElse();
            if (elseBranch != null) {
                value(elseBranch, true, inCondition);
            }
            else {
                builder.readUnit(expression);
            }
            builder.bindLabel(resultLabel);
//            if (!inCondition) {
//                builder.readNode(expression);
//            }
        }

        @Override
        public void visitTryExpression(JetTryExpression expression) {
            JetFinallySection finallyBlock = expression.getFinallyBlock();
            if (finallyBlock != null) {
                builder.enterTryFinally(finallyBlock.getFinalExpression());
            }

            List<JetCatchClause> catchClauses = expression.getCatchClauses();
            if (catchClauses.isEmpty()) {
                value(expression.getTryBlock(), true, false);
            }
            else {
                Label catchBlock = builder.createUnboundLabel();
                builder.nondeterministicJump(catchBlock);

                value(expression.getTryBlock(), true, false);

                Label afterCatches = builder.createUnboundLabel();
                builder.jump(afterCatches);

                builder.bindLabel(catchBlock);
                for (Iterator<JetCatchClause> iterator = catchClauses.iterator(); iterator.hasNext(); ) {
                    JetCatchClause catchClause = iterator.next();
                    value(catchClause.getCatchBody(), true, false);
                    if (iterator.hasNext()) {
                        builder.nondeterministicJump(afterCatches);
                    }
                }

                builder.bindLabel(afterCatches);
            }

            if (finallyBlock != null) {
                builder.exitTryFinally();
            }
        }

        @Override
        public void visitWhileExpression(JetWhileExpression expression) {
            Label loopExitPoint = builder.createUnboundLabel();
            Label loopEntryPoint = builder.enterLoop(expression, loopExitPoint);
            value(expression.getCondition(), false, true);
            builder.jumpOnFalse(loopExitPoint);
            value(expression.getBody(), true, false);
            builder.jump(loopEntryPoint);
            builder.exitLoop(expression);
        }

        @Override
        public void visitDoWhileExpression(JetDoWhileExpression expression) {
            Label loopExitPoint = builder.createUnboundLabel();
            Label loopEntryPoint = builder.enterLoop(expression, loopExitPoint);
            value(expression.getBody(), true, false);
            value(expression.getCondition(), false, true);
            builder.jumpOnTrue(loopEntryPoint);
            builder.exitLoop(expression);
        }

        @Override
        public void visitForExpression(JetForExpression expression) {
            value(expression.getLoopRange(), false, false);
            Label loopExitPoint = builder.createUnboundLabel();
            Label loopEntryPoint = builder.enterLoop(expression, loopExitPoint);
            value(expression.getBody(), true, false);
            builder.nondeterministicJump(loopEntryPoint);
            builder.exitLoop(expression);
        }

        @Override
        public void visitBreakExpression(JetBreakExpression expression) {
            JetElement loop = getCorrespondingLoop(expression);
            if (loop != null) {
                builder.jump(builder.getExitPoint(loop));
            }
        }

        @Override
        public void visitContinueExpression(JetContinueExpression expression) {
            JetElement loop = getCorrespondingLoop(expression);
            if (loop != null) {
                builder.jump(builder.getEntryPoint(loop));
            }
        }

        private JetElement getCorrespondingLoop(JetLabelQualifiedExpression expression) {
            String labelName = expression.getLabelName();
            JetElement loop;
            if (labelName != null) {
                JetSimpleNameExpression targetLabel = expression.getTargetLabel();
                assert targetLabel != null;
                loop = resolveLabel(labelName, targetLabel);
                if (!isLoop(loop)) {
                    semanticServices.getErrorHandler().genericError(expression.getNode(), "The label '" + targetLabel.getText() + "' does not denote a loop");
                    loop = null;
                }
            }
            else {
                loop = builder.getCurrentLoop();
                if (loop == null) {
                    semanticServices.getErrorHandler().genericError(expression.getNode(), "'break' and 'continue' are only allowed inside a loop");
                }
            }
            return loop;
        }

        private boolean isLoop(JetElement loop) {
            return loop instanceof JetWhileExpression ||
                   loop instanceof JetDoWhileExpression ||
                   loop instanceof JetForExpression;
        }

        @Override
        public void visitReturnExpression(JetReturnExpression expression) {
            JetExpression returnedExpression = expression.getReturnedExpression();
            if (returnedExpression != null) {
                value(returnedExpression, false, false);
            }
            JetSimpleNameExpression labelElement = expression.getTargetLabel();
            JetElement subroutine;
            if (labelElement != null) {
                String labelName = expression.getLabelName();
                assert labelName != null;
                subroutine = resolveLabel(labelName, labelElement);
            }
            else {
                subroutine = builder.getCurrentSubroutine();
                // TODO : a context check
            }
            if (subroutine != null) {
                if (returnedExpression == null) {
                    builder.returnNoValue(expression, subroutine);
                }
                else {
                    builder.returnValue(subroutine);
                }
            }
        }

        @Override
        public void visitBlockExpression(JetBlockExpression expression) {
            for (JetElement statement : expression.getStatements()) {
                value(statement, true, false);
            }
        }

        @Override
        public void visitFunction(JetFunction function) {
            generate(function, function.getBodyExpression());
        }

        @Override
        public void visitFunctionLiteralExpression(JetFunctionLiteralExpression expression) {
            if (preferBlock && !expression.hasParameterSpecification()) {
                for (JetElement statement : expression.getBody()) {
                    value(statement, true, false);
                }
            }
            else {
                generateSubroutineControlFlow(expression, expression.getBody(), true);
            }
        }

        @Override
        public void visitQualifiedExpression(JetQualifiedExpression expression) {
            value(expression.getReceiverExpression(), false, false);
            JetExpression selectorExpression = expression.getSelectorExpression();
            if (selectorExpression != null) {
                value(selectorExpression, false, false);
            }
            builder.readNode(expression);
        }

        @Override
        public void visitCallExpression(JetCallExpression expression) {
            for (JetTypeProjection typeArgument : expression.getTypeArguments()) {
                value(typeArgument, false, false);
            }

            for (JetArgument argument : expression.getValueArguments()) {
                JetExpression argumentExpression = argument.getArgumentExpression();
                if (argumentExpression != null) {
                    value(argumentExpression, false, false);
                }
            }

            for (JetExpression functionLiteral : expression.getFunctionLiteralArguments()) {
                value(functionLiteral, false, false);
            }

            value(expression.getCalleeExpression(), false, false);
            builder.readNode(expression);
        }

        @Override
        public void visitProperty(JetProperty property) {
            JetExpression initializer = property.getInitializer();
            if (initializer != null) {
                value(initializer, false, false);
                builder.writeNode(property, property);
            }
        }

        @Override
        public void visitTupleExpression(JetTupleExpression expression) {
            for (JetExpression entry : expression.getEntries()) {
                value(entry, false, false);
            }
            builder.readNode(expression);
        }

        @Override
        public void visitTypeProjection(JetTypeProjection typeProjection) {
            // TODO : Support Type Arguments. Class object may be initialized at this point");
        }

        @Override
        public void visitJetElement(JetElement elem) {
            builder.unsupported(elem);
        }
    }

}