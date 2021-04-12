package org.jetbrains.dataframe.impl.codeGen

import org.jetbrains.dataframe.ColumnKind
import org.jetbrains.dataframe.DataFrame
import org.jetbrains.dataframe.DataFrameBase
import org.jetbrains.dataframe.DataRow
import org.jetbrains.dataframe.DataRowBase
import org.jetbrains.dataframe.annotations.DataSchema
import org.jetbrains.dataframe.internal.codeGen.SchemaProcessor
import org.jetbrains.dataframe.internal.codeGen.GeneratedCode
import org.jetbrains.dataframe.internal.codeGen.GeneratedField
import org.jetbrains.dataframe.internal.codeGen.Marker
import org.jetbrains.dataframe.columns.ColumnGroup
import org.jetbrains.dataframe.columns.DataColumn
import org.jetbrains.dataframe.internal.schema.ColumnSchema
import org.jetbrains.dataframe.internal.schema.DataFrameSchema
import org.jetbrains.dataframe.keywords.HardKeywords
import org.jetbrains.dataframe.keywords.ModifierKeywords
import org.jetbrains.kotlinx.jupyter.api.Code

private fun renderNullability(nullable: Boolean) = if(nullable) "?" else ""

internal fun GeneratedField.renderFieldType(): Code =
    when (columnKind) {
        ColumnKind.Value -> (columnSchema as ColumnSchema.Value).type.toString()
        ColumnKind.Map -> "${DataRow::class.qualifiedName}<${markerName}>"
        ColumnKind.Frame -> "${DataFrame::class.qualifiedName}<${markerName}>${renderNullability(columnSchema.nullable)}"
    }

internal fun getRequiredMarkers(schema: DataFrameSchema, markers: Iterable<Marker>) = markers
    .filter { it.isOpen && it.schema.compare(schema).isSuperOrEqual() }

internal val charsToQuote = """[ {}()<>'"/|.\\!?@:;%^&*#$-]""".toRegex()

internal fun String.needsQuoting(): Boolean {
    return contains(charsToQuote)
            || HardKeywords.VALUES.contains(this)
            || ModifierKeywords.VALUES.contains(this)
}

internal fun String.quoteIfNeeded() = if (needsQuoting()) "`$this`" else this

internal fun List<Code>.join() = joinToString("\n")

internal class CodeGeneratorImpl : CodeGenerator {

    private fun GeneratedField.renderColumnType() : Code =
        when (columnKind) {
            ColumnKind.Value -> "${DataColumn::class.qualifiedName}<${(columnSchema as ColumnSchema.Value).type}>"
            ColumnKind.Map -> "${ColumnGroup::class.qualifiedName}<${markerName}>"
            ColumnKind.Frame -> "${DataColumn::class.qualifiedName}<${DataFrame::class.qualifiedName}<${markerName}>${renderNullability(columnSchema.nullable)}>"
        }

    fun renderColumnName(name: String) = name
        .replace("\\", "\\\\")
        .replace("$", "\\\$")
        .replace("\"", "\\\"")

    private fun String.removeQuotes() = this.removeSurrounding("`")

    private fun generateExtensionProperties(markers: List<Marker>) = markers.map { generateExtensionProperties(it) }

    private fun generateExtensionProperties(marker: Marker): Code {

        val markerName = marker.name
        val shortMarkerName = markerName.substring(markerName.lastIndexOf('.') + 1)
        fun generatePropertyCode(typeName: String, name: String, propertyType: String, getter: String): String {
            val jvmName = "${shortMarkerName}_${name.removeQuotes()}"
            return "val $typeName.$name: $propertyType @JvmName(\"$jvmName\") get() = $getter as $propertyType"
        }

        val declarations = mutableListOf<String>()
        val dfTypename = "${DataFrameBase::class.qualifiedName}<$markerName>"
        val rowTypename = "${DataRowBase::class.qualifiedName}<$markerName>"
        marker.fields.sortedBy { it.fieldName }.forEach {
            val getter = "this[\"${it.columnName}\"]"
            val name = it.fieldName
            val fieldType = it.renderFieldType()
            val columnType = it.renderColumnType()
            declarations.add(generatePropertyCode(dfTypename, name, columnType, getter))
            declarations.add(generatePropertyCode(rowTypename, name, fieldType, getter))
        }
        return declarations.joinToString("\n")
    }

    override fun generate(marker: Marker, interfaceMode: InterfaceGenerationMode, extensionProperties: Boolean): GeneratedCode {
        val generateInterface = interfaceMode != InterfaceGenerationMode.None
        val code = when {
            generateInterface && extensionProperties -> generateInterface(marker, interfaceMode == InterfaceGenerationMode.WithFields) + "\n" + generateExtensionProperties(
                marker
            )
            generateInterface -> generateInterface(marker, interfaceMode == InterfaceGenerationMode.WithFields)
            extensionProperties -> generateExtensionProperties(marker)
            else -> ""
        }
        return GeneratedCode(code) { "$it.typed<${marker.name}>()" }
    }

    override fun generate(
        schema: DataFrameSchema,
        name: String,
        fields: Boolean,
        extensionProperties: Boolean,
        isOpen: Boolean,
        knownMarkers: Iterable<Marker>
    ): Pair<GeneratedCode, List<Marker>> {

        val context = SchemaProcessor.create(name, knownMarkers)
        val marker = context.process(schema, isOpen)
        val declarations = mutableListOf<Code>()
        declarations.addAll(generateInterfaces(context.generatedMarkers, fields))
        if(extensionProperties)
            declarations.addAll(generateExtensionProperties(context.generatedMarkers))
        return GeneratedCode(declarations.join()) { "$it.typed<${marker.name}>()" } to context.generatedMarkers
    }

    private fun generateInterfaces(
        schemas: List<Marker>, fields: Boolean
    ) = schemas.map { generateInterface(it, fields) }

    private fun generateInterface(
        marker: Marker,
        fields: Boolean
    ): Code {

        val annotationName = DataSchema::class.simpleName

        val header =
            "@$annotationName${if (marker.isOpen) "" else "(isOpen = false)"}\ninterface ${marker.name}"
        val baseInterfacesDeclaration =
            if (marker.baseMarkers.isNotEmpty()) " : " + marker.baseMarkers.map { it.value.name }
                .joinToString() else ""
        val resultDeclarations = mutableListOf<String>()

        val fieldsDeclaration = if(fields) marker.fields.map {
            val override = if (it.overrides) "override " else ""
            val columnNameAnnotation =
                if (it.columnName != it.fieldName) "\t@ColumnName(\"${renderColumnName(it.columnName)}\")\n" else ""

            val fieldType = it.renderFieldType()
            "${columnNameAnnotation}    ${override}val ${it.fieldName}: $fieldType"
        }.join() else ""
        val body = if (fieldsDeclaration.isNotBlank()) "{\n$fieldsDeclaration\n}" else ""
        resultDeclarations.add(header + baseInterfacesDeclaration + body)
        return resultDeclarations.join()
    }
}
