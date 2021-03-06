package org.jetbrains.dataframe.impl.columns

import org.jetbrains.dataframe.columns.DataColumn
import org.jetbrains.dataframe.columns.ColumnGroup
import kotlin.reflect.KType

internal interface DataColumnInternal<T> : DataColumn<T> {

    override fun rename(newName: String): DataColumnInternal<T>
    fun forceResolve(): DataColumn<T>
    fun changeType(type: KType): DataColumn<T>
    fun addParent(parent: ColumnGroup<*>): DataColumn<T>
}