/**
 * Client-side copy of the server Kobo title template renderer (KoboTitleTemplate.kt).
 * Used for the live preview and validation in the server settings.
 *
 * Placeholders: {series}, {number}, {number:N} (zero-padded), {title}
 */

export const KOBO_TITLE_TEMPLATE_DEFAULT = '{series} {number:4}'

const placeholderRegex = /\{([^{}]*)\}/g
const numberWithPaddingRegex = /^number:(\d+)$/
const numericRegex = /^(-?)(\d+)(\.\d+)?$/

// marker for placeholders that rendered empty, removed during cleanup
const EMPTY = '\u0000'
const SEPARATORS = '\\s\\-–—:|/·,_'
const emptyInBracketsRegex = new RegExp(`\\(\\s*${EMPTY}\\s*\\)|\\[\\s*${EMPTY}\\s*\\]`, 'g')
const emptyWithSeparatorsRegex = new RegExp(`([${SEPARATORS}]*)${EMPTY}+([${SEPARATORS}]*)`, 'g')
const trimSeparatorsRegex = new RegExp(`^[${SEPARATORS}]+|[${SEPARATORS}]+$`, 'g')

function isKnownPlaceholder(placeholder: string): boolean {
  return (
    placeholder === 'series' ||
    placeholder === 'number' ||
    placeholder === 'title' ||
    numberWithPaddingRegex.test(placeholder)
  )
}

function placeholders(template: string): string[] {
  return [...template.matchAll(placeholderRegex)].map((m) => m[1]!)
}

/**
 * @return true if the template only contains known placeholders, and contains {series} or {title}
 */
export function isValidKoboTitleTemplate(template: string): boolean {
  const found = placeholders(template)
  return (
    found.every((p) => isKnownPlaceholder(p)) &&
    (found.includes('series') || found.includes('title'))
  )
}

export function padKoboNumber(number: string, width: number): string {
  const match = numericRegex.exec(number.trim())
  if (!match) return number
  const [, sign, integer, decimal] = match
  return sign! + integer!.padStart(width, '0') + (decimal ?? '')
}

export function renderKoboTitleTemplate(
  template: string,
  series: string,
  number: string,
  title: string,
): string {
  let hasEmpty = false
  const rendered = template.replace(placeholderRegex, (match, placeholder: string) => {
    let value: string
    if (placeholder === 'series') value = series
    else if (placeholder === 'number') value = number
    else if (placeholder === 'title') value = title
    else {
      const padding = numberWithPaddingRegex.exec(placeholder)
      value = padding ? padKoboNumber(number, parseInt(padding[1]!)) : match
    }
    value = value.trim()
    if (value === '') {
      hasEmpty = true
      return EMPTY
    }
    return value
  })

  const cleaned = hasEmpty
    ? rendered
        .replace(emptyInBracketsRegex, EMPTY)
        // keep a single separator group, so the surrounding parts don't get glued together
        .replace(emptyWithSeparatorsRegex, (_, before: string, after: string) => before || after)
        .replace(/\s{2,}/g, ' ')
        .replace(trimSeparatorsRegex, '')
    : rendered.trim()

  return cleaned.trim() === '' ? title : cleaned
}
