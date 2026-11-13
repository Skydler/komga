package org.gotson.komga.interfaces.api.kobo.persistence

import org.gotson.komga.interfaces.api.kobo.dto.KoboBookMetadataDto
import org.gotson.komga.interfaces.api.kobo.dto.KoboMediaInfo

interface KoboDtoRepository {
  fun findBookMetadataByIds(
    bookIds: Collection<String>,
  ): Collection<KoboBookMetadataDto>

  /**
   * Light-weight media information, used to build the reading state of books.
   */
  fun findMediaInfoByIds(
    bookIds: Collection<String>,
  ): Collection<KoboMediaInfo>
}
