package org.jetbrains.kotlin.idea.inspections.dfa

import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeBinOp
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.codeInspection.dataFlow.types.DfTypes
import com.intellij.codeInspection.dataFlow.value.RelationType
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.resolve.constants.*
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.*

internal fun getDfType(type : KotlinType) : DfType {
    return when {
        type.isBoolean() -> DfTypes.BOOLEAN
        type.isByte() -> DfTypes.intRange(LongRangeSet.range(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()))
        type.isChar() -> DfTypes.intRange(LongRangeSet.range(Character.MIN_VALUE.toLong(), Character.MAX_VALUE.toLong()))
        type.isShort() -> DfTypes.intRange(LongRangeSet.range(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()))
        type.isInt() -> DfTypes.INT
        type.isLong() -> DfTypes.LONG
        type.isFloat() -> DfTypes.FLOAT
        type.isDouble() -> DfTypes.DOUBLE
        else -> DfType.TOP
    }
}

internal fun getConstant(expr: KtConstantExpression): DfType {
    val bindingContext = expr.analyze(BodyResolveMode.PARTIAL)
    val type = bindingContext.getType(expr)
    val constant: ConstantValue<Any?>? =
        if (type == null) null else ConstantExpressionEvaluator.getConstant(expr, bindingContext)?.toConstantValue(type)
    return when (constant) {
        is NullValue -> DfTypes.NULL
        is BooleanValue -> DfTypes.booleanValue(constant.value)
        is ByteValue -> DfTypes.intValue(constant.value.toInt())
        is ShortValue -> DfTypes.intValue(constant.value.toInt())
        is CharValue -> DfTypes.intValue(constant.value.toInt())
        is IntValue -> DfTypes.intValue(constant.value)
        is LongValue -> DfTypes.longValue(constant.value)
        is FloatValue -> DfTypes.floatValue(constant.value)
        is DoubleValue -> DfTypes.doubleValue(constant.value)
        else -> DfType.TOP
    }
}

internal fun relationFromToken(token: IElementType): RelationType? = when (token) {
    KtTokens.LT -> RelationType.LT
    KtTokens.GT -> RelationType.GT
    KtTokens.LTEQ -> RelationType.LE
    KtTokens.GTEQ -> RelationType.GE
    KtTokens.EQEQ -> RelationType.EQ
    KtTokens.EXCLEQ -> RelationType.NE
    KtTokens.EQEQEQ -> RelationType.EQ
    KtTokens.EXCLEQEQEQ -> RelationType.NE
    else -> null
}

internal fun mathOpFromToken(token: IElementType): LongRangeBinOp? = when(token) {
    KtTokens.PLUS -> LongRangeBinOp.PLUS
    KtTokens.MINUS -> LongRangeBinOp.MINUS
    KtTokens.MUL -> LongRangeBinOp.MUL
    KtTokens.DIV -> LongRangeBinOp.DIV
    KtTokens.PERC -> LongRangeBinOp.MOD
    else -> null
}