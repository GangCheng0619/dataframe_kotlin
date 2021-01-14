package org.jetbrains.dataframe.io

import org.jetbrains.dataframe.DataFrame
import java.io.File
import java.net.URL

enum class SupportedFormats {
    CSV,
    JSON
}

internal fun guessFormat(file: File): SupportedFormats? = when(file.extension.toLowerCase()){
    "json" -> SupportedFormats.JSON
    "csv" -> SupportedFormats.CSV
    else -> null
}

internal fun guessFormat(url: URL): SupportedFormats? = guessFormat(url.path)

internal fun guessFormat(url: String): SupportedFormats? = when{
    url.endsWith(".csv") -> SupportedFormats.CSV
    url.endsWith(".json") -> SupportedFormats.JSON
    else -> null
}

fun DataFrame.Companion.read(file: File): DataFrame<*> = when(guessFormat(file)) {
    SupportedFormats.CSV -> readCSV(file)
    SupportedFormats.JSON -> readJSON(file)
    else -> try {
        readCSV(file)
    } catch(e: Exception) {
        readJSON(file)
    }
}

fun DataFrame.Companion.read(url: URL): DataFrame<*> = when(guessFormat(url)) {
    SupportedFormats.CSV -> readCSV(url)
    SupportedFormats.JSON -> readJSON(url)
    else -> try {
        readCSV(url)
    } catch(e: Exception) {
        readJSON(url)
    }
}

fun DataFrame.Companion.read(path: String): DataFrame<*> = when(guessFormat(path)) {
    SupportedFormats.CSV -> readCSV(path)
    SupportedFormats.JSON -> readJSON(path)
    else -> try {
        readCSV(path)
    } catch(e: Exception) {
        readJSON(path)
    }
}
