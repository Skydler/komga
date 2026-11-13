import { describe, expect, test } from 'vitest'
import {
  isValidKoboTitleTemplate,
  padKoboNumber,
  renderKoboTitleTemplate,
} from '@/functions/kobo-title-template'

describe('kobo title template', () => {
  test('render placeholders', () => {
    expect(renderKoboTitleTemplate('{series} {number:4}', 'One Piece', '45', 'Gear 5')).toBe(
      'One Piece 0045',
    )
    expect(
      renderKoboTitleTemplate('{series} #{number} - {title}', 'One Piece', '45', 'Gear 5'),
    ).toBe('One Piece #45 - Gear 5')
  })

  test('pad numbers', () => {
    expect(padKoboNumber('45', 4)).toBe('0045')
    expect(padKoboNumber('45.5', 4)).toBe('0045.5')
    expect(padKoboNumber('12345', 4)).toBe('12345')
    expect(padKoboNumber('-1', 3)).toBe('-001')
    expect(padKoboNumber('SP1', 4)).toBe('SP1')
  })

  test('remove leftover separators around empty placeholders', () => {
    expect(renderKoboTitleTemplate('{series} - {title}', 'One Piece', '45', '')).toBe('One Piece')
    expect(renderKoboTitleTemplate('{title} - {series}', 'One Piece', '45', '')).toBe('One Piece')
    expect(
      renderKoboTitleTemplate('{series} - {number} - {title}', 'One Piece', '', 'Gear 5'),
    ).toBe('One Piece - Gear 5')
    expect(renderKoboTitleTemplate('{series} ({title})', 'One Piece', '45', '')).toBe('One Piece')
  })

  test('fall back to the book title when blank', () => {
    expect(renderKoboTitleTemplate('{series} {number:4}', '', '', 'Gear 5')).toBe('Gear 5')
  })

  test('validate templates', () => {
    expect(isValidKoboTitleTemplate('{series} {number:4}')).toBeTruthy()
    expect(isValidKoboTitleTemplate('{title}')).toBeTruthy()
    expect(isValidKoboTitleTemplate('{series} {volume}')).toBeFalsy()
    expect(isValidKoboTitleTemplate('{series:2}')).toBeFalsy()
    expect(isValidKoboTitleTemplate('{number:4}')).toBeFalsy()
    expect(isValidKoboTitleTemplate('')).toBeFalsy()
  })
})
