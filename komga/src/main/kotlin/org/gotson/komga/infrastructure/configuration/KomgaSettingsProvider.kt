package org.gotson.komga.infrastructure.configuration

import org.apache.commons.lang3.RandomStringUtils
import org.gotson.komga.domain.model.ThumbnailSize
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Service
class KomgaSettingsProvider(
  private val serverSettingsDao: ServerSettingsDao,
  private val eventPublisher: ApplicationEventPublisher,
) {
  var deleteEmptyCollections: Boolean =
    serverSettingsDao.getSettingByKey(Settings.DELETE_EMPTY_COLLECTIONS.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.DELETE_EMPTY_COLLECTIONS.name, value)
      field = value
    }

  var deleteEmptyReadLists: Boolean =
    serverSettingsDao.getSettingByKey(Settings.DELETE_EMPTY_READLISTS.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.DELETE_EMPTY_READLISTS.name, value)
      field = value
    }

  var rememberMeKey: String =
    serverSettingsDao.getSettingByKey(Settings.REMEMBER_ME_KEY.name, String::class.java)
      ?: getRandomRememberMeKey().also { rememberMeKey = it }
    set(value) {
      serverSettingsDao.saveSetting(Settings.REMEMBER_ME_KEY.name, value)
      field = value
    }

  fun renewRememberMeKey() {
    rememberMeKey = getRandomRememberMeKey()
  }

  private fun getRandomRememberMeKey() = RandomStringUtils.secure().nextAlphanumeric(32)

  var rememberMeDuration: Duration =
    (serverSettingsDao.getSettingByKey(Settings.REMEMBER_ME_DURATION.name, Int::class.java) ?: 365).days
    set(value) {
      serverSettingsDao.saveSetting(Settings.REMEMBER_ME_DURATION.name, value.inWholeDays.toInt())
      field = value
    }

  var thumbnailSize: ThumbnailSize =
    serverSettingsDao.getSettingByKey(Settings.THUMBNAIL_SIZE.name, String::class.java)?.let {
      ThumbnailSize.valueOf(it)
    } ?: ThumbnailSize.DEFAULT
    set(value) {
      serverSettingsDao.saveSetting(Settings.THUMBNAIL_SIZE.name, value.name)
      field = value
    }

  var taskPoolSize: Int =
    serverSettingsDao.getSettingByKey(Settings.TASK_POOL_SIZE.name, Int::class.java) ?: 1
    set(value) {
      serverSettingsDao.saveSetting(Settings.TASK_POOL_SIZE.name, value)
      field = value
      eventPublisher.publishEvent(SettingChangedEvent.TaskPoolSize)
    }

  var serverPort: Int? =
    serverSettingsDao.getSettingByKey(Settings.SERVER_PORT.name, Int::class.java)
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.SERVER_PORT.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.SERVER_PORT.name)
      field = value
    }

  var serverContextPath: String? =
    serverSettingsDao.getSettingByKey(Settings.SERVER_CONTEXT_PATH.name, String::class.java)
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.SERVER_CONTEXT_PATH.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.SERVER_CONTEXT_PATH.name)
      field = value
    }

  var koboProxy: Boolean =
    serverSettingsDao.getSettingByKey(Settings.KOBO_PROXY.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.KOBO_PROXY.name, value)
      field = value
    }

  var koboPort: Int? =
    serverSettingsDao.getSettingByKey(Settings.KOBO_PORT.name, Int::class.java)
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.KOBO_PORT.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.KOBO_PORT.name)
      field = value
    }

  var kepubifyPath: String? =
    serverSettingsDao.getSettingByKey(Settings.KEPUBIFY_PATH.name, String::class.java)?.ifBlank { null }
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.KEPUBIFY_PATH.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.KEPUBIFY_PATH.name)
      field = value
      eventPublisher.publishEvent(SettingChangedEvent.KepubifyPath)
    }

  var nextUiAsDefault: Boolean =
    serverSettingsDao.getSettingByKey(Settings.NEXTUI_AS_DEFAULT.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.NEXTUI_AS_DEFAULT.name, value)
      field = value
    }

  /**
   * Whether comic books (DIVINA profile: CBZ, CBR, …) are included in Kobo Sync.
   */
  var koboSyncComics: Boolean =
    serverSettingsDao.getSettingByKey(Settings.KOBO_SYNC_COMICS.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.KOBO_SYNC_COMICS.name, value)
      field = value
    }

  /**
   * Maximum page width for comics sent to Kobo. `null` means no resize.
   */
  var koboComicMaxWidth: Int? = getOptionalDimension(Settings.KOBO_COMIC_MAX_WIDTH, KOBO_COMIC_DEFAULT_MAX_WIDTH)
    set(value) {
      saveOptionalDimension(Settings.KOBO_COMIC_MAX_WIDTH, value)
      field = value
    }

  /**
   * Maximum page height for comics sent to Kobo. `null` means no resize.
   */
  var koboComicMaxHeight: Int? = getOptionalDimension(Settings.KOBO_COMIC_MAX_HEIGHT, KOBO_COMIC_DEFAULT_MAX_HEIGHT)
    set(value) {
      saveOptionalDimension(Settings.KOBO_COMIC_MAX_HEIGHT, value)
      field = value
    }

  var koboComicGrayscale: Boolean =
    serverSettingsDao.getSettingByKey(Settings.KOBO_COMIC_GRAYSCALE.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.KOBO_COMIC_GRAYSCALE.name, value)
      field = value
    }

  var koboComicTitleTemplate: String =
    serverSettingsDao.getSettingByKey(Settings.KOBO_COMIC_TITLE_TEMPLATE.name, String::class.java)?.ifBlank { null }
      ?: KOBO_COMIC_DEFAULT_TITLE_TEMPLATE
    set(value) {
      serverSettingsDao.saveSetting(Settings.KOBO_COMIC_TITLE_TEMPLATE.name, value)
      field = value
    }

  // A missing setting means "use the default". An explicit "no resize" is stored as 0.
  private fun getOptionalDimension(
    setting: Settings,
    default: Int,
  ): Int? =
    when (val stored = serverSettingsDao.getSettingByKey(setting.name, Int::class.java)) {
      null -> default
      0 -> null
      else -> stored
    }

  private fun saveOptionalDimension(
    setting: Settings,
    value: Int?,
  ) = serverSettingsDao.saveSetting(setting.name, value ?: 0)

  companion object {
    // Kobo Libra Colour
    const val KOBO_COMIC_DEFAULT_MAX_WIDTH = 1264
    const val KOBO_COMIC_DEFAULT_MAX_HEIGHT = 1680
    const val KOBO_COMIC_DEFAULT_TITLE_TEMPLATE = "{series} {number:4}"
  }
}

private enum class Settings {
  DELETE_EMPTY_COLLECTIONS,
  DELETE_EMPTY_READLISTS,
  REMEMBER_ME_KEY,
  REMEMBER_ME_DURATION,
  THUMBNAIL_SIZE,
  TASK_POOL_SIZE,
  SERVER_PORT,
  SERVER_CONTEXT_PATH,
  KOBO_PROXY,
  KOBO_PORT,
  KEPUBIFY_PATH,
  NEXTUI_AS_DEFAULT,
  KOBO_SYNC_COMICS,
  KOBO_COMIC_MAX_WIDTH,
  KOBO_COMIC_MAX_HEIGHT,
  KOBO_COMIC_GRAYSCALE,
  KOBO_COMIC_TITLE_TEMPLATE,
}
