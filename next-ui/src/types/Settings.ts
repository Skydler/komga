import type { SettingsUpdateDto } from '@/generated/openapi'
import type { ClientSettingOauth2Login } from '@/types/ClientSettingsGlobal'

export type ThumbnailRegenerate = 'no' | 'bigger' | 'all'

export type SettingsUpdateDtoExtended = Omit<
  SettingsUpdateDto,
  'koboComicMaxWidth' | 'koboComicMaxHeight'
> & {
  // null means "no resize", and must be sent to the server to clear the value
  koboComicMaxWidth?: number | null
  koboComicMaxHeight?: number | null
} & {
  thumbnailRegenerate: ThumbnailRegenerate
} & {
  oauth2: ClientSettingOauth2Login
}
