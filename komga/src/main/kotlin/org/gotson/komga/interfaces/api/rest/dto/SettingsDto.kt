package org.gotson.komga.interfaces.api.rest.dto

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SettingsDto(
  val deleteEmptyCollections: Boolean? = null,
  val deleteEmptyReadLists: Boolean? = null,
  val rememberMeDurationDays: Long? = null,
  val thumbnailSize: ThumbnailSizeDto? = null,
  val taskPoolSize: Int? = null,
  val serverPort: SettingMultiSource<Int>? = null,
  val serverContextPath: SettingMultiSource<String>? = null,
  val koboProxy: Boolean? = null,
  val koboPort: Int? = null,
  val kepubifyPath: SettingMultiSource<String>? = null,
  val maxUploadFileSizeBytes: Long? = null,
  val nextUiAsDefault: Boolean? = null,
  val koboSyncComics: Boolean? = null,
  @field:Schema(description = "Maximum page width for comics sent to Kobo. Absent means no resize.")
  val koboComicMaxWidth: Int? = null,
  @field:Schema(description = "Maximum page height for comics sent to Kobo. Absent means no resize.")
  val koboComicMaxHeight: Int? = null,
  val koboComicGrayscale: Boolean? = null,
  val koboComicTitleTemplate: String? = null,
)

fun SettingsDto.public() =
  SettingsDto(
    maxUploadFileSizeBytes = this.maxUploadFileSizeBytes,
  )

data class SettingMultiSource<T>(
  val configurationSource: T?,
  val databaseSource: T?,
  val effectiveValue: T?,
)
