package com.opencontacts.data.repository

import com.opencontacts.core.model.ContactSummary
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val explicitFolderHeaders = setOf("folder", "foldername", "group", "groupname", "container")
private val explicitTagHeaders = setOf("tags", "tag", "categories", "labels")

@Singleton
class VcfHandler @Inject constructor() {
    fun parse(stream: InputStream): List<ContactSummary> {
        val contacts = mutableListOf<ContactSummary>()
        val unfolded = mutableListOf<String>()
        var previous: String? = null

        stream.bufferedReader().forEachLine { raw ->
            val line = raw.trimEnd()
            if ((line.startsWith(" ") || line.startsWith("\t")) && previous != null) {
                previous += line.trimStart()
                unfolded[unfolded.lastIndex] = previous!!
            } else {
                previous = line
                unfolded += line
            }
        }

        var name: String? = null
        var phone: String? = null
        var tags: List<String> = emptyList()
        val folders = mutableListOf<String>()

        fun commit() {
            val displayName = decodeVcf(name?.trim().orEmpty())
            if (displayName.isBlank()) return
            contacts += ContactSummary(
                id = UUID.randomUUID().toString(),
                displayName = displayName,
                primaryPhone = phone?.trim()?.takeIf { it.isNotBlank() },
                tags = tags,
                isFavorite = false,
                folderName = folders.firstOrNull(),
                folderNames = folders.distinctBy { it.lowercase() },
            )
        }

        unfolded.forEach { raw ->
            val line = raw.trim()
            when {
                line.equals("BEGIN:VCARD", ignoreCase = true) -> {
                    name = null
                    phone = null
                    tags = emptyList()
                    folders.clear()
                }
                line.startsWith("FN:", ignoreCase = true) -> name = line.substringAfter(':')
                line.startsWith("TEL", ignoreCase = true) -> {
                    phone = line.substringAfter(':').replace("[\\s\\-()]".toRegex(), "")
                }
                line.startsWith("CATEGORIES:", ignoreCase = true) -> {
                    tags = line.substringAfter(':')
                        .split(',')
                        .map { decodeVcf(it).trim().removePrefix("#") }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }
                }
                line.startsWith("X-OPENCONTACTS-FOLDER:", ignoreCase = true) ||
                    line.startsWith("X-FOLDER:", ignoreCase = true) ||
                    line.startsWith("X-GROUP:", ignoreCase = true) ||
                    line.startsWith("X-GROUP-NAME:", ignoreCase = true) -> {
                    decodeVcf(line.substringAfter(':'))
                        .split('|', ',', ';')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { folders += it }
                }
                line.equals("END:VCARD", ignoreCase = true) -> commit()
            }
        }
        return contacts
    }

    fun write(contacts: List<ContactSummary>, stream: OutputStream) {
        val writer = stream.bufferedWriter()
        contacts.forEach { contact ->
            writer.write("BEGIN:VCARD\r\n")
            writer.write("VERSION:3.0\r\n")
            writer.write("FN:${encodeVcf(contact.displayName)}\r\n")
            writer.write("N:${encodeVcf(contact.displayName)};;;;\r\n")
            contact.primaryPhone?.takeIf { it.isNotBlank() }?.let {
                writer.write("TEL;TYPE=CELL:${encodeVcf(it)}\r\n")
            }
            if (contact.tags.isNotEmpty()) {
                writer.write(
                    "CATEGORIES:${contact.tags.joinToString(",") { tag -> encodeVcf(tag) }}\r\n"
                )
            }
            contact.folderNames.ifEmpty { listOfNotNull(contact.folderName) }
                .filter { it.isNotBlank() }
                .forEach { folderName ->
                    writer.write("X-OPENCONTACTS-FOLDER:${encodeVcf(folderName)}\r\n")
                }
            writer.write("END:VCARD\r\n")
        }
        writer.flush()
    }

    private fun decodeVcf(value: String): String = value
        .replace("\\n", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    private fun encodeVcf(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")
}

@Singleton
class CsvHandler @Inject constructor() {
    private val defaultHeader = listOf("displayName", "primaryPhone", "tags", "folderName", "isFavorite")

    fun parse(stream: InputStream): List<ContactSummary> {
        val lines = stream.bufferedReader().readLines()
        if (lines.isEmpty()) return emptyList()

        val parsedFirstLine = parseCells(lines.first().trimStart('﻿'))
        val hasHeader = parsedFirstLine.any { headerCell ->
            val normalized = headerCell.trim().lowercase()
            normalized in setOf("displayname", "name", "fullname", "primaryphone", "phone", "mobile", "folder", "foldername", "group", "tags", "tag")
        }

        val headerMap = if (hasHeader) {
            parsedFirstLine.mapIndexed { index, raw -> raw.trim().lowercase() to index }.toMap()
        } else {
            defaultHeader.mapIndexed { index, raw -> raw.trim().lowercase() to index }.toMap()
        }

        val body = if (hasHeader) lines.drop(1) else lines
        return body.mapNotNull { parseLine(it.trimStart('﻿'), headerMap) }
    }

    fun write(contacts: List<ContactSummary>, stream: OutputStream) {
        val writer = stream.bufferedWriter()
        writer.write(defaultHeader.joinToString(","))
        writer.newLine()
        contacts.forEach { c ->
            val row = listOf(
                c.displayName,
                c.primaryPhone.orEmpty(),
                c.tags.joinToString("|"),
                c.folderName.orEmpty(),
                c.isFavorite.toString(),
            ).joinToString(",") { cell -> quote(cell) }
            writer.write(row)
            writer.newLine()
        }
        writer.flush()
    }

    private fun parseLine(line: String, headerMap: Map<String, Int>): ContactSummary? {
        if (line.isBlank()) return null
        val cells = parseCells(line)
        fun value(vararg aliases: String): String {
            val index = aliases.firstNotNullOfOrNull { alias -> headerMap[alias.lowercase()] } ?: return ""
            return cells.getOrNull(index).orEmpty().trim()
        }

        val displayName = value("displayname", "name", "fullname")
        if (displayName.isBlank()) return null

        val folderValues = value(*explicitFolderHeaders.toTypedArray())
            .split('|', ',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val tags = value(*explicitTagHeaders.toTypedArray())
            .split('|', ',', ';')
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val phone = value("primaryphone", "phone", "mobile", "number").ifBlank { null }
        val favoriteRaw = value("isfavorite", "favorite", "starred")

        return ContactSummary(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            primaryPhone = phone,
            tags = tags,
            isFavorite = favoriteRaw.equals("true", ignoreCase = true) || favoriteRaw == "1",
            folderName = folderValues.firstOrNull(),
            folderNames = folderValues,
        )
    }

    private fun parseCells(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        val delimiter = if (line.count { it == ';' } > line.count { it == ',' }) ';' else ','
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
