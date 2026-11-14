package org.gotson.komga.interfaces.api.kobo

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.MarkSelectedPreference
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaType
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.UserRoles
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.gotson.komga.domain.service.KomgaUserLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.gotson.komga.infrastructure.kobo.KoboHeaders
import org.gotson.komga.infrastructure.kobo.KomgaSyncTokenGenerator
import org.gotson.komga.infrastructure.web.WebServerEffectiveSettings
import org.gotson.komga.language.toUTCZoned
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.ZonedDateTime
import java.util.stream.Stream
import org.springframework.http.MediaType as SpringMediaType

@SpringBootTest(properties = ["komga.kobo.sync-item-limit=1"])
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class KoboControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val komgaUserLifecycle: KomgaUserLifecycle,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val mediaRepository: MediaRepository,
  @Autowired private val komgaSyncTokenGenerator: KomgaSyncTokenGenerator,
  @Autowired private val komgaSettingsProvider: KomgaSettingsProvider,
  @Autowired private val serverSettings: WebServerEffectiveSettings,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val bookMetadataRepository: BookMetadataRepository,
  @Autowired private val bookLifecycle: BookLifecycle,
  @Autowired private val readProgressRepository: ReadProgressRepository,
) {
  private val library1 = makeLibrary()
  private val user1 =
    KomgaUser(
      "user@example.org",
      "",
      roles = setOf(UserRoles.KOBO_SYNC),
    )
  private lateinit var apiKey: String

  @BeforeAll
  fun `setup library`() {
    libraryRepository.insert(library1)
    userRepository.insert(user1)
    apiKey = komgaUserLifecycle.createApiKey(user1, "test")!!.key
  }

  @AfterAll
  fun teardown() {
    libraryRepository.deleteAll()
    komgaUserLifecycle.deleteUser(user1)
  }

  @AfterEach
  fun `clear repository`() {
    seriesLifecycle.deleteMany(seriesRepository.findAll())
  }

  @Test
  fun `given user when syncing for the first time then books are synced`() {
    // given
    val book1 = makeBook("valid", libraryId = library1.id)
    val book2 = makeBook("valid", libraryId = library1.id)

    makeSeries(name = "series1", libraryId = library1.id).also { series ->
      seriesLifecycle.createSeries(series).let { created ->
        seriesLifecycle.addBooks(created, listOf(book1, book2))
      }
    }

    mediaRepository.findById(book1.id).let { media -> mediaRepository.update(media.copy(status = Media.Status.READY, mediaType = MediaType.EPUB.type)) }
    mediaRepository.findById(book2.id).let { media -> mediaRepository.update(media.copy(status = Media.Status.READY, mediaType = MediaType.EPUB.type)) }

    // first sync
    val mvcResult1 =
      mockMvc
        .get("/kobo/$apiKey/v1/library/sync")
        .andExpect {
          status { isOk() }
          jsonPath("$.length()") { value(1) }
          header { string(KoboHeaders.X_KOBO_SYNC, "continue") }
        }.andReturn()

    val syncToken1Base64 = mvcResult1.response.getHeaderValue(KoboHeaders.X_KOBO_SYNCTOKEN) as String
    val syncToken1 = komgaSyncTokenGenerator.fromBase64(syncToken1Base64)
    assertThat(syncToken1.ongoingSyncPointId).isNotEmpty()
    assertThat(syncToken1.lastSuccessfulSyncPointId).isNull()

    // second sync
    val mvcResult2 =
      mockMvc
        .get("/kobo/$apiKey/v1/library/sync") {
          header(KoboHeaders.X_KOBO_SYNCTOKEN, syncToken1Base64)
        }.andExpect {
          status { isOk() }
          jsonPath("$.length()") { value(1) }
          header { doesNotExist(KoboHeaders.X_KOBO_SYNC) }
        }.andReturn()

    val syncToken2 = komgaSyncTokenGenerator.fromBase64(mvcResult2.response.getHeaderValue(KoboHeaders.X_KOBO_SYNCTOKEN) as String)
    assertThat(syncToken2.ongoingSyncPointId).isNull()
    assertThat(syncToken2.lastSuccessfulSyncPointId).isEqualTo(syncToken1.ongoingSyncPointId)
  }

  @Test
  fun `given kobo proxy is enabled when requesting book cover for non-existent book then redirect response is returned`() {
    komgaSettingsProvider.koboProxy = true

    try {
      mockMvc
        .get("/kobo/$apiKey/v1/books/nonexistent/thumbnail/800/800/false/image.jpg")
        .andExpect {
          status { isTemporaryRedirect() }
          header { string(HttpHeaders.LOCATION, "https://cdn.kobo.com/book-images/nonexistent/800/800/false/image.jpg") }
        }
    } finally {
      komgaSettingsProvider.koboProxy = false
    }
  }

  private val pageCount = 10

  private fun makeComic(): String {
    val book = makeBook("comic", libraryId = library1.id)
    makeSeries(name = "One Piece", libraryId = library1.id).also { series ->
      seriesLifecycle.createSeries(series).let { created ->
        seriesLifecycle.addBooks(created, listOf(book))
      }
    }
    mediaRepository.findById(book.id).let { media ->
      mediaRepository.update(
        media.copy(
          status = Media.Status.READY,
          mediaType = MediaType.ZIP.type,
          pages = (1..pageCount).map { BookPage("$it.jpg", "image/jpeg") },
          pageCount = pageCount,
        ),
      )
    }
    bookMetadataRepository.findById(book.id).let { bookMetadataRepository.update(it.copy(title = "Gear 5", number = "45", numberSort = 45F)) }
    return book.id
  }

  private fun makeEpub(): String {
    val book = makeBook("epub", libraryId = library1.id)
    makeSeries(name = "series", libraryId = library1.id).also { series ->
      seriesLifecycle.createSeries(series).let { created ->
        seriesLifecycle.addBooks(created, listOf(book))
      }
    }
    mediaRepository.findById(book.id).let { media -> mediaRepository.update(media.copy(status = Media.Status.READY, mediaType = MediaType.EPUB.type)) }
    // the sync diff compares thumbnail IDs, and does not match books without a thumbnail
    bookLifecycle.addThumbnailForBook(
      ThumbnailBook(ByteArray(10), type = ThumbnailBook.Type.USER_UPLOADED, mediaType = "image/jpeg", fileSize = 10L, dimension = Dimension(1, 1), bookId = book.id),
      MarkSelectedPreference.YES,
    )
    return book.id
  }

  /**
   * @param source the bookmark location source, or null to send a bookmark without a location
   */
  private fun stateBody(
    bookId: String,
    source: String?,
    status: String = "Reading",
    lastModified: String = "2099-01-01T00:00:00Z",
  ): String {
    val location = source?.let { """, "Location": { "Value": "kobo.1.1", "Type": "KoboSpan", "Source": "$it" }""" } ?: ""
    return """
      {
        "ReadingStates": [
          {
            "EntitlementId": "$bookId",
            "LastModified": "$lastModified",
            "CurrentBookmark": {
              "LastModified": "$lastModified",
              "ProgressPercent": 1,
              "ContentSourceProgressPercent": 0$location
            },
            "Statistics": { "LastModified": "$lastModified" },
            "StatusInfo": { "LastModified": "$lastModified", "Status": "$status", "TimesStartedReading": 1 }
          }
        ]
      }
      """.trimIndent()
  }

  @Nested
  inner class MarkAsUnread {
    @Test
    fun `given comic with read progress when kobo marks it unread then read progress is deleted`() {
      val bookId = makeComic()
      bookLifecycle.markReadProgress(bookRepository.findByIdOrNull(bookId)!!, user1, 5)

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          content = stateBody(bookId, "OEBPS/Text/page-0005.xhtml", "ReadyToRead")
        }.andExpect {
          status { isOk() }
          jsonPath("RequestResult") { value("Success") }
        }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)).isNull()
    }

    @Test
    fun `given epub with read progress when kobo marks it unread then read progress is deleted`() {
      val bookId = makeEpub()
      readProgressRepository.save(ReadProgress(bookId, user1.id, 1, false))

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          content = stateBody(bookId, "OEBPS/chapter1.xhtml", "ReadyToRead")
        }.andExpect {
          status { isOk() }
          jsonPath("RequestResult") { value("Success") }
        }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)).isNull()
    }

    @Test
    fun `given read progress when kobo marks book unread without a bookmark location then request succeeds and read progress is deleted`() {
      val bookId = makeEpub()
      readProgressRepository.save(ReadProgress(bookId, user1.id, 1, false))

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          content = stateBody(bookId, null, "ReadyToRead")
        }.andExpect {
          status { isOk() }
          jsonPath("RequestResult") { value("Success") }
        }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)).isNull()
    }

    @Test
    fun `given newer read progress when kobo marks book unread with an older date then read progress is kept`() {
      val bookId = makeEpub()
      readProgressRepository.save(ReadProgress(bookId, user1.id, 1, false))

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          content = stateBody(bookId, null, "ReadyToRead", lastModified = "2000-01-01T00:00:00Z")
        }.andExpect {
          status { isOk() }
          jsonPath("RequestResult") { value("Success") }
        }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)).isNotNull
    }

    @Test
    fun `given no read progress when kobo marks book unread then request succeeds and nothing changes`() {
      val bookId = makeEpub()

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          content = stateBody(bookId, null, "ReadyToRead")
        }.andExpect {
          status { isOk() }
          jsonPath("RequestResult") { value("Success") }
        }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)).isNull()
    }

    @Test
    fun `given read progress deleted in komga when syncing then kobo receives a newer ReadyToRead state`() {
      val bookId = makeEpub()
      readProgressRepository.save(ReadProgress(bookId, user1.id, 1, false))
      val progressDate = readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)!!.lastModifiedDate.toUTCZoned()

      // first sync, Kobo gets the book with its read progress
      val syncToken1 =
        mockMvc
          .get("/kobo/$apiKey/v1/library/sync")
          .andExpect {
            status { isOk() }
            jsonPath("$[0].NewEntitlement.ReadingState.StatusInfo.Status") { value("Reading") }
            header { doesNotExist(KoboHeaders.X_KOBO_SYNC) }
          }.andReturn()
          .response
          .getHeaderValue(KoboHeaders.X_KOBO_SYNCTOKEN) as String

      bookLifecycle.deleteReadProgress(bookRepository.findByIdOrNull(bookId)!!, user1)

      // second sync, Kobo gets the unread state
      val response =
        mockMvc
          .get("/kobo/$apiKey/v1/library/sync") {
            header(KoboHeaders.X_KOBO_SYNCTOKEN, syncToken1)
          }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].ChangedReadingState.ReadingState.EntitlementId") { value(bookId) }
            jsonPath("$[0].ChangedReadingState.ReadingState.StatusInfo.Status") { value("ReadyToRead") }
          }.andReturn()
          .response
          .contentAsString

      val lastModified = ZonedDateTime.parse(JsonPath.read<String>(response, "$[0].ChangedReadingState.ReadingState.LastModified"))
      assertThat(lastModified).isAfter(progressDate)
    }

    @Test
    fun `given book never read when syncing then reading state uses the book creation date`() {
      val bookId = makeEpub()
      val createdDate = bookRepository.findByIdOrNull(bookId)!!.createdDate

      val response =
        mockMvc
          .get("/kobo/$apiKey/v1/library/sync")
          .andExpect {
            status { isOk() }
            jsonPath("$[0].NewEntitlement.ReadingState.StatusInfo.Status") { value("ReadyToRead") }
          }.andReturn()
          .response
          .contentAsString

      val lastModified = ZonedDateTime.parse(JsonPath.read<String>(response, "$[0].NewEntitlement.ReadingState.LastModified"))
      assertThat(lastModified).isEqualTo(createdDate.toUTCZoned())
    }
  }

  @Nested
  inner class Comics {
    @Test
    fun `given comic in series when getting metadata then title uses template and download is fixed layout epub`() {
      val bookId = makeComic()

      mockMvc
        .get("/kobo/$apiKey/v1/library/$bookId/metadata")
        .andExpect {
          status { isOk() }
          jsonPath("$[0].Title") { value("One Piece 0045") }
          jsonPath("$[0].SubTitle") { value("Gear 5") }
          jsonPath("$[0].Series.Name") { value("One Piece") }
          jsonPath("$[0].DownloadUrls[0].Format") { value("EPUB3FL") }
        }
    }

    @Test
    fun `given comic when kobo updates state with a page href then read progress is set to that page`() {
      val bookId = makeComic()

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          content = stateBody(bookId, "OEBPS/Text/page-0004.xhtml")
        }.andExpect {
          status { isOk() }
          jsonPath("RequestResult") { value("Success") }
        }

      val progress = readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)
      assertThat(progress).isNotNull
      assertThat(progress!!.page).isEqualTo(4)
      assertThat(progress.completed).isFalse()
      assertThat(progress.locator?.href).isEqualTo("OEBPS/Text/page-0004.xhtml")
    }

    @Test
    fun `given comic when kobo marks it finished then read progress is completed on the last page`() {
      val bookId = makeComic()

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          // Kobo sends the first resource when a book is finished
          content = stateBody(bookId, "OEBPS/Text/page-0001.xhtml", "Finished")
        }.andExpect {
          status { isOk() }
          jsonPath("RequestResult") { value("Success") }
        }

      val progress = readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user1.id)
      assertThat(progress!!.page).isEqualTo(pageCount)
      assertThat(progress.completed).isTrue()
    }

    @Test
    fun `given comic when kobo sends an href that is not a page then request is rejected`() {
      val bookId = makeComic()

      mockMvc
        .put("/kobo/$apiKey/v1/library/$bookId/state") {
          contentType = SpringMediaType.APPLICATION_JSON
          content = stateBody(bookId, "OEBPS/chapter1.xhtml")
        }.andExpect {
          status { isBadRequest() }
        }
    }

    @Test
    fun `given comic read in komga when getting state then bookmark points to the page resource`() {
      val bookId = makeComic()
      bookLifecycle.markReadProgress(bookRepository.findByIdOrNull(bookId)!!, user1, 5)

      mockMvc
        .get("/kobo/$apiKey/v1/library/$bookId/state")
        .andExpect {
          status { isOk() }
          jsonPath("$[0].CurrentBookmark.Location.Source") { value("OEBPS/Text/page-0005.xhtml") }
          jsonPath("$[0].CurrentBookmark.Location.Value") { value("kobo.1.1") }
          jsonPath("$[0].CurrentBookmark.Location.Type") { value("KoboSpan") }
          jsonPath("$[0].CurrentBookmark.ProgressPercent") { value(50.0) }
          jsonPath("$[0].StatusInfo.Status") { value("Reading") }
        }
    }

    @Test
    fun `given comics setting when syncing then comics are synced only when enabled`() {
      makeComic()

      try {
        komgaSettingsProvider.koboSyncComics = false
        mockMvc
          .get("/kobo/$apiKey/v1/library/sync")
          .andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
          }

        komgaSettingsProvider.koboSyncComics = true
        mockMvc
          .get("/kobo/$apiKey/v1/library/sync")
          .andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].NewEntitlement.BookMetadata.Title") { value("One Piece 0045") }
          }
      } finally {
        komgaSettingsProvider.koboSyncComics = false
      }
    }
  }

  @Nested
  inner class HostHeader(
    @param:Value($$"${server.port:#{null}}") private val configServerPort: Int?,
  ) {
    @ParameterizedTest
    @MethodSource("headers")
    fun `given partial host header when getting initialization then img urls are correct`(
      hostHeader: String,
      expected: String,
      extraHeaders: Map<String, String>?,
      koboPort: Int?,
    ) {
      // ServletWebServerInitializedEvent is not triggered during tests
      serverSettings.effectiveServerPort = configServerPort

      val oldPort = komgaSettingsProvider.koboPort
      koboPort?.let { komgaSettingsProvider.koboPort = it }

      try {
        mockMvc
          .get("/kobo/$apiKey/v1/initialization") {
            header(HttpHeaders.HOST, hostHeader)
            extraHeaders?.forEach { (h, v) -> header(h, v) }
          }.andExpect {
            jsonPath("Resources.image_host") { value(expected) }
          }.andReturn()
      } finally {
        komgaSettingsProvider.koboPort = oldPort
      }
    }

    private fun headers(): Stream<Arguments> =
      Stream.of(
        Arguments.of("127.0.0.1", "http://127.0.0.1:$configServerPort", null, null),
        Arguments.of("localhost", "http://localhost:$configServerPort", null, null),
        Arguments.of(
          "127.0.0.1",
          "https://demo.komga.org",
          mapOf(
            "X-Forwarded-Proto" to "https",
            "X-Forwarded-Host" to "demo.komga.org",
          ),
          null,
        ),
        Arguments.of("127.0.0.1", "http://127.0.0.1:8085", null, 8085),
        Arguments.of("localhost", "http://localhost:8085", null, 8085),
        Arguments.of(
          "127.0.0.1",
          "https://demo.komga.org",
          mapOf(
            "X-Forwarded-Proto" to "https",
            "X-Forwarded-Host" to "demo.komga.org",
          ),
          8085,
        ),
      )
  }
}
