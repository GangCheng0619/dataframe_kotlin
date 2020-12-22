package org.jetbrains.dataframe.api.columns

import org.jetbrains.dataframe.ColumnResolutionContext

interface SingleColumn<out C> : ColumnSet<C> {

    override fun resolve(context: ColumnResolutionContext) = resolveSingle(context)?.let { listOf(it) } ?: emptyList()

    fun resolveSingle(context: ColumnResolutionContext): ColumnWithPath<C>?
}