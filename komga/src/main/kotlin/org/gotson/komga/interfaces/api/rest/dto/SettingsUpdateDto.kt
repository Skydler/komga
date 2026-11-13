package org.gotson.komga.interfaces.api.rest.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import kotlin.properties.Delegates

class SettingsUpdateDto {
  private val isSet = mutableMapOf<String, Boolean>()

  fun isSet(prop: String) = isSet.getOrDefault(prop, false)

  var deleteEmptyCollections: Boolean? = null

  var deleteEmptyReadLists: Boolean? = null

  @get:Positive
  var rememberMeDurationDays: Long? = null

  var renewRememberMeKey: Boolean? = null

  var thumbnailSize: ThumbnailSizeDto? = null

  @get:Positive
  var taskPoolSize: Int? = null

  @get:Positive
  @get:Max(65535)
  var serverPort: Int?
    by Delegates.observable(null) { prop, _, _ ->
      isSet[prop.name] = true
    }

  @get:Pattern(regexp = "^/[\\w-/]*[a-zA-Z0-9]$")
  var serverContextPath: String?
    by Delegates.observable(null) { prop, _, _ ->
      isSet[prop.name] = true
    }

  var koboProxy: Boolean? = null

  @get:Positive
  @get:Max(65535)
  var koboPort: Int?
    by Delegates.observable(null) { prop, _, _ ->
      isSet[prop.name] = true
    }

  @Deprecated("Will be removed in a future version")
  var kepubifyPath: String?
    by Delegates.observable(null) { prop, _, _ ->
      isSet[prop.name] = true
    }

  var nextUiAsDefault: Boolean? = null

  var koboSyncComics: Boolean? = null

  @get:Positive
  @get:Schema(description = "Maximum page width for comics sent to Kobo. Set to null for no resize. Must be set together with koboComicMaxHeight.")
  var koboComicMaxWidth: Int?
    by Delegates.observable(null) { prop, _, _ ->
      isSet[prop.name] = true
    }

  @get:Positive
  @get:Schema(description = "Maximum page height for comics sent to Kobo. Set to null for no resize. Must be set together with koboComicMaxWidth.")
  var koboComicMaxHeight: Int?
    by Delegates.observable(null) { prop, _, _ ->
      isSet[prop.name] = true
    }

  var koboComicGrayscale: Boolean? = null

  @get:Schema(description = "Title template for comics sent to Kobo. Placeholders: {series}, {number}, {number:N}, {title}.")
  var koboComicTitleTemplate: String? = null
}
