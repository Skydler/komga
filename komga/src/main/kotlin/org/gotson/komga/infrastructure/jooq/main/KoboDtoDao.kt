package org.gotson.komga.infrastructure.jooq.main

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.MediaExtensionEpub
import org.gotson.komga.domain.model.MediaProfile
import org.gotson.komga.domain.model.MediaType
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.infrastructure.jooq.deserializeMediaExtension
import org.gotson.komga.interfaces.api.kobo.KoboTitleTemplate
import org.gotson.komga.interfaces.api.kobo.dto.ContributorDto
import org.gotson.komga.interfaces.api.kobo.dto.KoboBookMetadataDto
import org.gotson.komga.interfaces.api.kobo.dto.KoboMediaInfo
import org.gotson.komga.interfaces.api.kobo.dto.KoboSeriesDto
import org.gotson.komga.interfaces.api.kobo.dto.PublisherDto
import org.gotson.komga.interfaces.api.kobo.persistence.KoboDtoRepository
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.ZoneId

@Component
class KoboDtoDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val mapper: ObjectMapper,
  private val komgaSettingsProvider: KomgaSettingsProvider,
) : SplitDslDaoBase(dslRW, dslRO),
  KoboDtoRepository {
  private val b = Tables.BOOK
  private val m = Tables.MEDIA
  private val d = Tables.BOOK_METADATA
  private val a = Tables.BOOK_METADATA_AUTHOR
  private val sd = Tables.SERIES_METADATA
  private val bt = Tables.THUMBNAIL_BOOK
  private val p = Tables.BOOK_PROJECTION

  override fun findBookMetadataByIds(
    bookIds: Collection<String>,
  ): Collection<KoboBookMetadataDto> {
    val records =
      dslRO
        .select(
          d.BOOK_ID,
          d.TITLE,
          d.NUMBER,
          d.NUMBER_SORT,
          d.ISBN,
          d.SUMMARY,
          d.RELEASE_DATE,
          d.CREATED_DATE,
          sd.SERIES_ID,
          sd.TITLE,
          sd.PUBLISHER,
          sd.LANGUAGE,
          b.FILE_SIZE,
          b.ONESHOT,
          m.MEDIA_TYPE,
          m.EPUB_IS_KEPUB,
          m.EXTENSION_CLASS,
          m.EXTENSION_VALUE_BLOB,
          bt.ID,
        ).from(b)
        .leftJoin(d)
        .on(b.ID.eq(d.BOOK_ID))
        .leftJoin(sd)
        .on(b.SERIES_ID.eq(sd.SERIES_ID))
        .leftJoin(m)
        .on(b.ID.eq(m.BOOK_ID))
        .leftJoin(bt)
        .on(b.ID.eq(bt.BOOK_ID))
        .and(bt.SELECTED.isTrue)
        .where(d.BOOK_ID.`in`(bookIds))
        .fetch()

    val authors =
      dslRO
        .selectFrom(a)
        .where(a.BOOK_ID.`in`(bookIds))
        .filter { it.name != null }
        .groupBy({ it.bookId }, { it })

    val projections =
      dslRO
        .selectFrom(p)
        .where(p.BOOK_ID.`in`(bookIds))
        .groupBy({ it.bookId }, { it })

    val titleTemplate = komgaSettingsProvider.koboComicTitleTemplate

    return records.map { rec ->
      val br = rec.into(b)
      val dr = rec.into(d)
      val sr = rec.into(sd)
      val mr = rec.into(m)
      val btr = rec.into(bt)
      val mediaExtension = mapper.deserializeMediaExtension(mr.extensionClass, mr.extensionValueBlob) as? MediaExtensionEpub
      val mediaProfile = MediaType.fromMediaType(mr.mediaType)?.profile
      val isComic = mediaProfile == MediaProfile.DIVINA

      // comics in a series use the title template, so they make sense in the Kobo library list
      val (title, subTitle) =
        if (isComic && !br.oneshot) {
          val rendered = KoboTitleTemplate.render(titleTemplate, sr.title, dr.number, dr.title)
          val subTitle =
            if (!KoboTitleTemplate.containsTitle(titleTemplate) && !KoboTitleTemplate.isGenericTitle(dr.title, dr.number))
              dr.title
            else
              null
          rendered to subTitle
        } else {
          dr.title to null
        }

      KoboBookMetadataDto(
        contributorRoles = authors[dr.bookId].orEmpty().map { ContributorDto(it.name) },
        contributors = authors[dr.bookId].orEmpty().map { it.name },
        coverImageId = btr.id,
        crossRevisionId = dr.bookId,
        // if null or empty Kobo will not update it, force it to blank
        description = dr.summary.ifEmpty { " " },
        entitlementId = dr.bookId,
        isbn = dr.isbn.ifBlank { null },
        language = sr.language.take(2).ifBlank { "en" },
        publicationDate = dr.releaseDate?.atStartOfDay(ZoneId.of("Z")) ?: dr.createdDate.atZone(ZoneId.of("Z")),
        publisher = PublisherDto(name = sr.publisher),
        revisionId = dr.bookId,
        series =
          if (!br.oneshot)
            KoboSeriesDto(
              id = sr.seriesId,
              name = sr.title,
              number = dr.number,
              numberFloat = dr.numberSort,
            )
          else
            null,
        subTitle = subTitle,
        title = title,
        workId = dr.bookId,
        isKepub = mr.epubIsKepub,
        // comics are sent as generated fixed-layout EPUBs
        isPrePaginated = isComic || mediaExtension?.isFixedLayout == true,
        fileSize = br.fileSize,
        extraFileSizes = projections[dr.bookId].orEmpty().associate { it.profile to it.fileSize } + ("default" to br.fileSize),
        mediaProfile = mediaProfile,
      )
    }
  }

  override fun findMediaInfoByIds(bookIds: Collection<String>): Collection<KoboMediaInfo> =
    dslRO
      .select(m.BOOK_ID, m.MEDIA_TYPE, m.PAGE_COUNT)
      .from(m)
      .where(m.BOOK_ID.`in`(bookIds))
      .fetch()
      .map { rec ->
        KoboMediaInfo(
          bookId = rec.get(m.BOOK_ID),
          profile = MediaType.fromMediaType(rec.get(m.MEDIA_TYPE))?.profile,
          pageCount = rec.get(m.PAGE_COUNT),
        )
      }
}
