package org.gotson.komga.interfaces.api.kobo.dto

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.gotson.komga.domain.model.MediaProfile
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.infrastructure.kobo.FixedLayoutEpubGenerator
import org.gotson.komga.language.toUTCZoned
import java.time.ZonedDateTime

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy::class)
data class ReadingStateDto(
  val created: ZonedDateTime? = null,
  val currentBookmark: BookmarkDto,
  val entitlementId: String,
  val lastModified: ZonedDateTime,
  /**
   * From CW: apparently always equals to lastModified
   */
  val priorityTimestamp: ZonedDateTime? = null,
  val statistics: StatisticsDto,
  val statusInfo: StatusInfoDto,
)

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy::class)
data class WrappedReadingStateDto(
  val readingState: ReadingStateDto,
)

/**
 * Media information needed to build a [ReadingStateDto].
 */
data class KoboMediaInfo(
  val bookId: String,
  val profile: MediaProfile?,
  val pageCount: Int,
)

/**
 * @param media media information for the book. Required to build the bookmark of comics, which is built from the page number.
 */
fun ReadProgress.toDto(media: KoboMediaInfo? = null) =
  ReadingStateDto(
    created = this.createdDate.toUTCZoned(),
    lastModified = this.lastModifiedDate.toUTCZoned(),
    priorityTimestamp = this.lastModifiedDate.toUTCZoned(),
    entitlementId = this.bookId,
    currentBookmark =
      if (media?.profile == MediaProfile.DIVINA && media.pageCount > 0)
        toComicBookmark(media.pageCount)
      else
        toEpubBookmark(),
    statistics =
      StatisticsDto(
        lastModified = this.lastModifiedDate.toUTCZoned(),
      ),
    statusInfo =
      StatusInfoDto(
        lastModified = this.lastModifiedDate.toUTCZoned(),
        status =
          when {
            this.completed -> StatusDto.FINISHED
            !this.completed -> StatusDto.READING
            else -> StatusDto.READY_TO_READ
          },
        timesStartedReading = 1,
      ),
  )

private fun ReadProgress.toEpubBookmark() =
  BookmarkDto(
    lastModified = this.lastModifiedDate.toUTCZoned(),
    progressPercent =
      this.locator
        ?.locations
        ?.totalProgression
        ?.times(100),
    contentSourceProgressPercent =
      this.locator
        ?.locations
        ?.progression
        ?.times(100),
    location = this.locator?.let { LocationDto(source = it.href, value = it.koboSpan) },
  )

/**
 * Comics are sent as generated fixed-layout EPUBs with one XHTML resource per page, see [FixedLayoutEpubGenerator].
 * The bookmark points to the resource of the current page.
 */
private fun ReadProgress.toComicBookmark(pageCount: Int): BookmarkDto {
  val page = this.page.coerceIn(1, pageCount)
  // reuse the href sent by the Kobo when it points to the same page, else build it
  val source =
    this.locator
      ?.href
      ?.takeIf { FixedLayoutEpubGenerator.pageNumberFromHref(it) == page }
      ?: FixedLayoutEpubGenerator.pageHref(page)
  return BookmarkDto(
    lastModified = this.lastModifiedDate.toUTCZoned(),
    progressPercent = page.toFloat() / pageCount * 100,
    contentSourceProgressPercent = 0F,
    location = LocationDto(source = source, value = "kobo.1.1"),
  )
}
