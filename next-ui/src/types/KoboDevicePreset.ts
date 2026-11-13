import { defineMessages, type MessageDescriptor } from 'vue-intl'

/**
 * Screen sizes of Kobo devices, used to fit comic pages for Kobo Sync.
 */
export const koboDevicePresets = {
  LIBRA: { width: 1264, height: 1680 },
  CLARA: { width: 1072, height: 1448 },
  SAGE: { width: 1440, height: 1920 },
  ELIPSA: { width: 1404, height: 1872 },
} as const

export type KoboDevicePresetName = keyof typeof koboDevicePresets

export type KoboDevicePresetSelection = KoboDevicePresetName | 'CUSTOM' | 'NONE'

export const koboDevicePresetMessages: Record<KoboDevicePresetSelection, MessageDescriptor> =
  defineMessages({
    LIBRA: {
      description: 'Kobo device preset: Libra Colour / Libra 2',
      defaultMessage: 'Kobo Libra Colour / Libra 2 (1264 × 1680)',
      id: 'enum.KoboDevicePreset.LIBRA',
    },
    CLARA: {
      description: 'Kobo device preset: Clara Colour / Clara BW / Clara 2E',
      defaultMessage: 'Kobo Clara Colour / Clara BW / Clara 2E (1072 × 1448)',
      id: 'enum.KoboDevicePreset.CLARA',
    },
    SAGE: {
      description: 'Kobo device preset: Sage',
      defaultMessage: 'Kobo Sage (1440 × 1920)',
      id: 'enum.KoboDevicePreset.SAGE',
    },
    ELIPSA: {
      description: 'Kobo device preset: Elipsa 2E',
      defaultMessage: 'Kobo Elipsa 2E (1404 × 1872)',
      id: 'enum.KoboDevicePreset.ELIPSA',
    },
    CUSTOM: {
      description: 'Kobo device preset: custom size',
      defaultMessage: 'Custom',
      id: 'enum.KoboDevicePreset.CUSTOM',
    },
    NONE: {
      description: 'Kobo device preset: no resize',
      defaultMessage: 'No resize',
      id: 'enum.KoboDevicePreset.NONE',
    },
  })

/**
 * Find the preset matching a size. Returns 'NONE' when the size is not set, and 'CUSTOM' when no preset matches.
 */
export function koboDevicePresetFor(
  width?: number | null,
  height?: number | null,
): KoboDevicePresetSelection {
  if (width == null && height == null) return 'NONE'
  const match = (Object.keys(koboDevicePresets) as KoboDevicePresetName[]).find(
    (name) => koboDevicePresets[name].width === width && koboDevicePresets[name].height === height,
  )
  return match ?? 'CUSTOM'
}
