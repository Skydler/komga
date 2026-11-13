package org.gotson.komga.interfaces.api.kobo

/**
 * Renders the title of comic books sent to Kobo devices.
 *
 * Placeholders:
 * - `{series}`: series title
 * - `{number}`: book number as stored
 * - `{number:N}`: book number, integer part zero-padded to N digits. Non-numeric numbers are rendered as stored.
 * - `{title}`: book title
 */
object KoboTitleTemplate {
  private const val SERIES = "series"
  private const val NUMBER = "number"
  private const val TITLE = "title"

  private val placeholderRegex = Regex("""\{([^{}]*)}""")
  private val numberWithPaddingRegex = Regex("""^number:(\d+)$""")
  private val numericRegex = Regex("""^(-?)(\d+)(\.\d+)?$""")
  private val genericTitleRegex = Regex("""^(chapter|chap\.?|ch\.?|volume|vol\.?|v|#)?\s*\d+(\.\d+)?$""", RegexOption.IGNORE_CASE)

  // Marker for placeholders that rendered empty, removed during cleanup
  private const val EMPTY = '\u0000'
  private const val SEPARATORS = """\s\-–—:|/·,_"""
  private val emptyInBracketsRegex = Regex("""\(\s*$EMPTY\s*\)|\[\s*$EMPTY\s*]""")
  private val emptyWithSeparatorsRegex = Regex("""([$SEPARATORS]*)$EMPTY+([$SEPARATORS]*)""")
  private val multipleSpacesRegex = Regex("""\s{2,}""")

  /**
   * @return a list of validation errors, empty if the template is valid
   */
  fun validate(template: String): List<String> =
    buildList {
      val placeholders = placeholderRegex.findAll(template).map { it.groupValues[1] }.toList()
      placeholders
        .filterNot { it == SERIES || it == NUMBER || it == TITLE || numberWithPaddingRegex.matches(it) }
        .forEach { add("Unknown placeholder: {$it}") }
      if (SERIES !in placeholders && TITLE !in placeholders) add("Template must contain {series} or {title}")
    }

  fun containsTitle(template: String): Boolean = placeholderRegex.findAll(template).any { it.groupValues[1] == TITLE }

  /**
   * Render the template. Leftover separators around empty placeholders are removed.
   * If the result is blank, the book title is returned.
   */
  fun render(
    template: String,
    seriesTitle: String,
    number: String,
    bookTitle: String,
  ): String {
    var hasEmpty = false
    val rendered =
      placeholderRegex.replace(template) { match ->
        val value =
          when (val placeholder = match.groupValues[1]) {
            SERIES -> seriesTitle
            NUMBER -> number
            TITLE -> bookTitle
            else ->
              numberWithPaddingRegex.matchEntire(placeholder)?.let { padNumber(number, it.groupValues[1].toInt()) }
                ?: match.value
          }.trim()
        value.ifEmpty {
          hasEmpty = true
          EMPTY.toString()
        }
      }

    val cleaned =
      if (hasEmpty)
        rendered
          .replace(emptyInBracketsRegex, EMPTY.toString())
          .replace(emptyWithSeparatorsRegex) { m ->
            // keep a single separator group, so the surrounding parts don't get glued together
            m.groupValues[1].ifEmpty { m.groupValues[2] }
          }.replace(multipleSpacesRegex, " ")
          .trim { it.isWhitespace() || it in "-–—:|/·,_" }
      else
        rendered.trim()

    return cleaned.ifBlank { bookTitle }
  }

  /**
   * Zero-pad the integer part of [number] to [width] digits. Non-numeric values are returned unchanged.
   */
  fun padNumber(
    number: String,
    width: Int,
  ): String {
    val match = numericRegex.matchEntire(number.trim()) ?: return number
    val (sign, integer, decimal) = match.destructured
    return sign + integer.padStart(width, '0') + decimal
  }

  /**
   * A title is generic when it does not add information to the series and number, like `Chapter 12` or `Vol. 3`.
   */
  fun isGenericTitle(
    title: String,
    number: String,
  ): Boolean = title.isBlank() || title.trim() == number.trim() || genericTitleRegex.matches(title.trim())
}
