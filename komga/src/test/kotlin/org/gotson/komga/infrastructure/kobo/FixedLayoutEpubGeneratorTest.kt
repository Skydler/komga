package org.gotson.komga.infrastructure.kobo

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.tika.config.TikaConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.BookProjection
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.EPUB_FL_DEFAULT
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaType
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookProjectionRepository
import org.gotson.komga.domain.service.BookAnalyzer
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.image.ImageConverter
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize

class FixedLayoutEpubGeneratorTest {
  private val bookAnalyzer = mockk<BookAnalyzer>()
  private val bookProjectionRepository = mockk<BookProjectionRepository>(relaxed = true)
  private val contentDetector = ContentDetector(TikaConfig())
  private val generator =
    FixedLayoutEpubGenerator(
      bookAnalyzer,
      ImageConverter(ImageAnalyzer(), contentDetector),
      contentDetector,
      bookProjectionRepository,
    )

  private val options = FixedLayoutEpubGenerator.Options(1264, 1680, false)

  // page 1 is too big and gets resized, page 2 fits and is copied, page 3 is in an unsupported format
  private val pageImages =
    listOf(
      makeImage(2528, 3360, "png"),
      makeImage(1000, 1500, "jpeg"),
      makeImage(800, 1200, "bmp"),
    )

  private val book =
    BookWithMedia(
      makeBook("comic"),
      Media(
        status = Media.Status.READY,
        mediaType = MediaType.ZIP.type,
        pages = listOf(BookPage("1.png", "image/png"), BookPage("2.jpg", "image/jpeg"), BookPage("3.bmp", "image/bmp")),
      ),
    )

  private fun metadata(rightToLeft: Boolean = false) =
    FixedLayoutEpubGenerator.Metadata(
      bookId = book.book.id,
      title = "One Piece 0045 & more",
      language = "ja",
      rightToLeft = rightToLeft,
      modified = LocalDateTime.of(2024, 1, 2, 3, 4, 5),
    )

  init {
    every { bookAnalyzer.getPageContent(any(), any()) } answers { pageImages[secondArg<Int>() - 1] }
  }

  @Test
  fun `given comic when generating then mimetype is the first entry and is stored`() {
    val entries = generateEntries()

    val first = entries.first()
    assertThat(first.name).isEqualTo("mimetype")
    assertThat(first.method).isEqualTo(ZipEntry.STORED)
    assertThat(String(first.bytes)).isEqualTo("application/epub+zip")
  }

  @Test
  fun `given comic when generating then structure contains all files in order`() {
    val entries = generateEntries()

    assertThat(entries.map { it.name }).containsExactly(
      "mimetype",
      "META-INF/container.xml",
      "OEBPS/Images/page-0001.jpg",
      "OEBPS/Text/page-0001.xhtml",
      "OEBPS/Images/page-0002.jpg",
      "OEBPS/Text/page-0002.xhtml",
      "OEBPS/Images/page-0003.jpg",
      "OEBPS/Text/page-0003.xhtml",
      "OEBPS/nav.xhtml",
      "OEBPS/toc.ncx",
      "OEBPS/content.opf",
    )
    assertThat(entries.filter { it.name.startsWith("OEBPS/Images/") }.map { it.method }).containsOnly(ZipEntry.STORED)
    assertThat(String(entries.first { it.name == "META-INF/container.xml" }.bytes)).contains("""full-path="OEBPS/content.opf"""")
  }

  @Test
  fun `given comic when generating then OPF declares fixed layout, cover and spine order`() {
    val opf = String(generateEntries().first { it.name == "OEBPS/content.opf" }.bytes)

    assertThat(opf)
      .contains("""<meta property="rendition:layout">pre-paginated</meta>""")
      .contains("""<meta property="rendition:spread">none</meta>""")
      .contains("""<dc:title>One Piece 0045 &amp; more</dc:title>""")
      .contains("""<dc:language>ja</dc:language>""")
      .contains("""href="Images/page-0001.jpg" media-type="image/jpeg" properties="cover-image"""")
      .contains("""<meta name="cover" content="img-page-0001"/>""")
      .contains("""page-progression-direction="ltr"""")
    val spine = Regex("""<itemref idref="([^"]+)"/>""").findAll(opf).map { it.groupValues[1] }.toList()
    assertThat(spine).containsExactly("page-0001", "page-0002", "page-0003")
  }

  @Test
  fun `given right to left series when generating then page progression direction is rtl`() {
    val opf = String(generateEntries(metadata(rightToLeft = true)).first { it.name == "OEBPS/content.opf" }.bytes)

    assertThat(opf).contains("""page-progression-direction="rtl"""")
  }

  @Test
  fun `given pages when generating then viewport uses the final image dimensions`() {
    val entries = generateEntries()

    assertThat(String(entries.first { it.name == "OEBPS/Text/page-0001.xhtml" }.bytes))
      .contains("""<meta name="viewport" content="width=1264, height=1680"/>""")
      .contains("""<img src="../Images/page-0001.jpg"""")
    assertThat(String(entries.first { it.name == "OEBPS/Text/page-0002.xhtml" }.bytes))
      .contains("""<meta name="viewport" content="width=1000, height=1500"/>""")
    assertThat(String(entries.first { it.name == "OEBPS/Text/page-0003.xhtml" }.bytes))
      .contains("""<meta name="viewport" content="width=800, height=1200"/>""")
  }

  @Test
  fun `given page that fits when generating then image bytes are copied unchanged`() {
    val entries = generateEntries()

    assertThat(entries.first { it.name == "OEBPS/Images/page-0002.jpg" }.bytes).isEqualTo(pageImages[1])
  }

  @Test
  fun `given same input when generating twice then output is identical`() {
    val first = generateBytes(metadata())
    val second = generateBytes(metadata())

    assertThat(first).isEqualTo(second)
  }

  @Test
  fun `given comic when generating to temp file then projection size is saved`() {
    val projection = slot<BookProjection>()
    every { bookProjectionRepository.save(capture(projection)) } returns Unit

    val path = generator.generateToTempFile(book, metadata(), options)
    try {
      assertThat(projection.captured.bookId).isEqualTo(book.book.id)
      assertThat(projection.captured.profile).isEqualTo(EPUB_FL_DEFAULT)
      assertThat(projection.captured.fileSize).isEqualTo(path.fileSize())
    } finally {
      path.deleteIfExists()
    }
    verify(exactly = 1) { bookProjectionRepository.save(any()) }
  }

  @Test
  fun `given epub book when generating then it is rejected`() {
    val epub = BookWithMedia(makeBook("book"), Media(status = Media.Status.READY, mediaType = MediaType.EPUB.type))

    assertThatThrownBy { generator.generate(epub, metadata(), options, ByteArrayOutputStream()) }
      .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `given page number when building href then it maps back to the same page`() {
    assertThat(FixedLayoutEpubGenerator.pageHref(12)).isEqualTo("OEBPS/Text/page-0012.xhtml")
    assertThat(FixedLayoutEpubGenerator.pageNumberFromHref(FixedLayoutEpubGenerator.pageHref(12))).isEqualTo(12)
    assertThat(FixedLayoutEpubGenerator.pageNumberFromHref(FixedLayoutEpubGenerator.pageHref(12345))).isEqualTo(12345)
    assertThat(FixedLayoutEpubGenerator.pageNumberFromHref("Text/page-0003.xhtml#top")).isEqualTo(3)
    assertThat(FixedLayoutEpubGenerator.pageNumberFromHref("OEBPS/chapter1.xhtml")).isNull()
  }

  private class Entry(
    val name: String,
    val method: Int,
    val bytes: ByteArray,
  )

  private fun generateBytes(metadata: FixedLayoutEpubGenerator.Metadata): ByteArray =
    ByteArrayOutputStream().use {
      generator.generate(book, metadata, options, it)
      it.toByteArray()
    }

  private fun generateEntries(metadata: FixedLayoutEpubGenerator.Metadata = metadata()): List<Entry> =
    ZipInputStream(generateBytes(metadata).inputStream()).use { zip ->
      generateSequence { zip.nextEntry }
        .map { Entry(it.name, it.method, zip.readAllBytes()) }
        .toList()
    }

  private fun makeImage(
    width: Int,
    height: Int,
    format: String,
  ): ByteArray {
    val image =
      BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also {
        val g = it.createGraphics()
        g.color = Color.GREEN
        g.fillRect(0, 0, width, height)
        g.dispose()
      }
    return ByteArrayOutputStream().use {
      ImageIO.write(image, format, it)
      it.toByteArray()
    }
  }
}
