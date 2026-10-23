package com.karen.search

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parses only dates that are actually present in source metadata. It never
 * invents a date and returns null when a value is ambiguous.
 */
object DateParser {
    private val loosePatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    )

    fun parse(raw: String?): Long? {
        var value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        value = value
            .replace(Regex("^(published|updated|date|posted)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (value.isBlank()) return null

        // ISO-8601 forms first: full instants, offsets, then local values.
        parseOrNull { Instant.parse(value) }?.let { return valid(it.toEpochMilli()) }
        parseOrNull { OffsetDateTime.parse(value).toInstant() }?.let { return valid(it.toEpochMilli()) }
        parseOrNull { ZonedDateTime.parse(value).toInstant() }?.let { return valid(it.toEpochMilli()) }
        parseOrNull { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC) }
            ?.let { return valid(it.toEpochMilli()) }
        parseOrNull { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant() }
            ?.let { return valid(it.toEpochMilli()) }
        parseOrNull { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            ?.let { return valid(it.toEpochMilli()) }

        for (formatter in loosePatterns) {
            parseOrNull { LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC) }
                ?.let { return valid(it.toEpochMilli()) }
            parseOrNull { LocalDate.parse(value, formatter).atStartOfDay(ZoneOffset.UTC).toInstant() }
                ?.let { return valid(it.toEpochMilli()) }
        }
        return null
    }

    fun formatDate(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) return ""
        return try {
            Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun valid(epochMillis: Long): Long? {
        if (epochMillis < MIN_DATE_MS || epochMillis > MAX_DATE_MS) return null
        return epochMillis
    }

    private inline fun <T> parseOrNull(block: () -> T): T? = try {
        block()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: Exception) {
        null
    }

    private val MIN_DATE_MS = LocalDate.of(1970, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val MAX_DATE_MS = LocalDate.of(2100, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
