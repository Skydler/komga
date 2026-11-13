package org.gotson.komga.infrastructure.kobo

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookProjection
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.EPUB_FL_DEFAULT
import org.gotson.komga.domain.model.MediaProfile
import org.gotson.komga.domain.persistence.BookProjectionRepository
import org.gotson.komga.domain.service.BookAnalyzer
import org.gotson.komga.infrastructure.image.FittedImage
import org.gotson.komga.infrastructure.image.ImageConverter
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

/**
 * Generates a fixed-layout EPUB3 from a comic book (DIVINA profile), with one XHTML file per page.
 *
 * The page hrefs ([pageHref]) map directly to a page number ([pageNumberFromHref]), which gives exact progress sync with Kobo devices.
 *
 * The output is deterministic: same input and options give the same bytes.
 */
@Component
class FixedLayoutEpubGenerator(
  private val bookAnalyzer: BookAnalyzer,
  private val imageConverter: ImageConverter,
  private val contentDetector: ContentDetector,
  private val bookProjectionRepository: BookProjectionRepository,
) {
  data class Options(
    val maxWidth: Int?,
    val maxHeight: Int?,
    val grayscale: Boolean,
  ) {
    /**
     * Short stable representation of the image options, to use in cache keys.
     */
    val cacheKey: String
      get() = "${maxWidth ?: "x"}x${maxHeight ?: "x"}-${if (grayscale) "g" else "c"}"
  }

  data class Metadata(
    val bookId: String,
    val title: String,
    val language: String,
    val rightToLeft: Boolean,
    val modified: LocalDateTime,
  )

  /**
   * Generate the EPUB to a temporary file, and store its size as a [BookProjection] with profile [EPUB_FL_DEFAULT].
   *
   * @return the path of the generated file. The caller is responsible for deleting it.
   */
  fun generateToTempFile(
    book: BookWithMedia,
    metadata: Metadata,
    options: Options,
  ): Path {
    val destination = Files.createTempFile("komga-${book.book.id}-", ".epub")
    try {
      destination.outputStream().use { generate(book, metadata, options, it) }
    } catch (e: Exception) {
      destination.deleteIfExists()
      throw e
    }
    bookProjectionRepository.save(BookProjection(book.book.id, EPUB_FL_DEFAULT, destination.fileSize()))
    return destination
  }

  fun generate(
    book: BookWithMedia,
    metadata: Metadata,
    options: Options,
    output: OutputStream,
  ) {
    require(book.media.profile == MediaProfile.DIVINA) { "Cannot generate fixed-layout EPUB, book is not a comic: ${book.book.path}" }
    logger.debug { "Generate fixed-layout EPUB for book ${book.book.id} with options $options" }

    val pages =
      (1..book.media.pageCount).asSequence().map { pageNumber ->
        val raw = bookAnalyzer.getPageContent(book, pageNumber)
        processPage(raw, book.media.pages[pageNumber - 1].dimension, options)
      }

    writeEpub(pages, metadata, output)
  }

  private fun processPage(
    raw: ByteArray,
    knownDimension: Dimension?,
    options: Options,
  ): FittedImage =
    try {
      imageConverter.fitImageToBox(raw, options.maxWidth, options.maxHeight, options.grayscale)
    } catch (e: Exception) {
      // the image cannot be decoded: send it as-is, the device may still be able to display it
      logger.warn(e) { "Could not process page image, sending it unchanged" }
      FittedImage(
        raw,
        contentDetector.detectMediaType(raw.inputStream()),
        knownDimension ?: Dimension(options.maxWidth ?: DEFAULT_WIDTH, options.maxHeight ?: DEFAULT_HEIGHT),
      )
    }

  /**
   * Write the EPUB container. Pages are consumed lazily, so only one page is held in memory at a time.
   */
  internal fun writeEpub(
    pages: Sequence<FittedImage>,
    metadata: Metadata,
    output: OutputStream,
  ) {
    val manifestPages = mutableListOf<ManifestPage>()

    ZipOutputStream(output).use { zip ->
      zip.putStored(MIMETYPE, "application/epub+zip".toByteArray())
      zip.putDeflated("META-INF/container.xml", CONTAINER_XML.toByteArray())

      pages.forEachIndexed { index, page ->
        val number = index + 1
        val manifestPage = ManifestPage(number, page.mediaType, page.dimension)
        manifestPages += manifestPage
        zip.putStored(OEBPS + manifestPage.imageHref, page.bytes)
        zip.putDeflated(OEBPS + manifestPage.xhtmlHref, pageXhtml(manifestPage).toByteArray())
      }

      zip.putDeflated(OEBPS + "nav.xhtml", navXhtml(metadata, manifestPages).toByteArray())
      zip.putDeflated(OEBPS + "toc.ncx", tocNcx(metadata, manifestPages).toByteArray())
      zip.putDeflated(OEBPS + "content.opf", contentOpf(metadata, manifestPages).toByteArray())
    }
  }

  private class ManifestPage(
    val number: Int,
    val mediaType: String,
    val dimension: Dimension,
  ) {
    val name = pageName(number)
    val imageHref = "Images/$name.${imageExtension(mediaType)}"
    val xhtmlHref = "Text/$name.xhtml"
  }

  private fun pageXhtml(page: ManifestPage): String {
    val (w, h) = page.dimension
    return """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<!DOCTYPE html>
      |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
      |<head>
      |<title>Page ${page.number}</title>
      |<meta name="viewport" content="width=$w, height=$h"/>
      |<style>html,body{margin:0;padding:0;width:${w}px;height:${h}px;overflow:hidden}img{display:block;width:${w}px;height:${h}px}</style>
      |</head>
      |<body>
      |<img src="../${page.imageHref}" alt="Page ${page.number}"/>
      |</body>
      |</html>
      |
      """.trimMargin()
  }

  private fun navXhtml(
    metadata: Metadata,
    pages: List<ManifestPage>,
  ): String =
    """
    |<?xml version="1.0" encoding="UTF-8"?>
    |<!DOCTYPE html>
    |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
    |<head><title>${metadata.title.xmlEscape()}</title></head>
    |<body>
    |<nav epub:type="toc" id="toc">
    |<ol>
    |<li><a href="${pages.firstOrNull()?.xhtmlHref ?: ""}">${metadata.title.xmlEscape()}</a></li>
    |</ol>
    |</nav>
    |<nav epub:type="page-list" hidden="">
    |<ol>
    |${pages.joinToString("\n") { """<li><a href="${it.xhtmlHref}">${it.number}</a></li>""" }}
    |</ol>
    |</nav>
    |</body>
    |</html>
    |
    """.trimMargin()

  private fun tocNcx(
    metadata: Metadata,
    pages: List<ManifestPage>,
  ): String =
    """
    |<?xml version="1.0" encoding="UTF-8"?>
    |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
    |<head>
    |<meta name="dtb:uid" content="${identifier(metadata)}"/>
    |</head>
    |<docTitle><text>${metadata.title.xmlEscape()}</text></docTitle>
    |<navMap>
    |<navPoint id="nav-1" playOrder="1"><navLabel><text>${metadata.title.xmlEscape()}</text></navLabel><content src="${pages.firstOrNull()?.xhtmlHref ?: ""}"/></navPoint>
    |</navMap>
    |</ncx>
    |
    """.trimMargin()

  private fun contentOpf(
    metadata: Metadata,
    pages: List<ManifestPage>,
  ): String {
    val manifestItems =
      pages.joinToString("\n") { page ->
        val coverProperty = if (page.number == 1) """ properties="cover-image"""" else ""
        """<item id="img-${page.name}" href="${page.imageHref}" media-type="${page.mediaType}"$coverProperty/>""" + "\n" +
          """<item id="${page.name}" href="${page.xhtmlHref}" media-type="application/xhtml+xml"/>"""
      }
    val spineItems = pages.joinToString("\n") { """<itemref idref="${it.name}"/>""" }
    val progressionDirection = if (metadata.rightToLeft) "rtl" else "ltr"
    val coverMeta = pages.firstOrNull()?.let { """<meta name="cover" content="img-${it.name}"/>""" } ?: ""

    return """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" prefix="rendition: http://www.idpf.org/vocab/rendition/#">
      |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
      |<dc:identifier id="bookid">${identifier(metadata)}</dc:identifier>
      |<dc:title>${metadata.title.xmlEscape()}</dc:title>
      |<dc:language>${metadata.language.xmlEscape()}</dc:language>
      |<meta property="dcterms:modified">${metadata.modified.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).format(MODIFIED_FORMAT)}</meta>
      |<meta property="rendition:layout">pre-paginated</meta>
      |<meta property="rendition:spread">none</meta>
      |<meta property="rendition:orientation">auto</meta>
      |$coverMeta
      |</metadata>
      |<manifest>
      |<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
      |<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
      |$manifestItems
      |</manifest>
      |<spine toc="ncx" page-progression-direction="$progressionDirection">
      |$spineItems
      |</spine>
      |</package>
      |
      """.trimMargin()
  }

  private fun identifier(metadata: Metadata) = "urn:komga:book:${metadata.bookId.xmlEscape()}"

  companion object {
    private const val MIMETYPE = "mimetype"
    private const val OEBPS = "OEBPS/"
    private const val DEFAULT_WIDTH = 1264
    private const val DEFAULT_HEIGHT = 1680

    // fixed timestamp for all zip entries, so the output does not change between runs
    private val ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0)
    private val MODIFIED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    private val pageHrefRegex = Regex("""page-(\d+)\.xhtml$""")

    private val CONTAINER_XML =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      |<rootfiles>
      |<rootfile full-path="${OEBPS}content.opf" media-type="application/oebps-package+xml"/>
      |</rootfiles>
      |</container>
      |
      """.trimMargin()

    private fun pageName(number: Int) = "page-" + number.toString().padStart(4, '0')

    /**
     * The href of the XHTML file for a page, relative to the root of the EPUB file.
     * This is the format used by Kobo devices in the reading state location.
     */
    fun pageHref(pageNumber: Int) = "${OEBPS}Text/${pageName(pageNumber)}.xhtml"

    /**
     * Extract the page number from an href produced by [pageHref]. Accepts relative paths and fragments.
     *
     * @return the page number, or null if the href does not point to a page
     */
    fun pageNumberFromHref(href: String): Int? =
      pageHrefRegex
        .find(href.substringBefore('#'))
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

    private fun imageExtension(mediaType: String) =
      when (mediaType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "image/jxl" -> "jxl"
        "image/heif" -> "heif"
        else -> "img"
      }

    private fun String.xmlEscape() =
      this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun ZipOutputStream.putStored(
      name: String,
      bytes: ByteArray,
    ) {
      val entry =
        ZipEntry(name).apply {
          method = ZipEntry.STORED
          size = bytes.size.toLong()
          compressedSize = bytes.size.toLong()
          crc = CRC32().also { it.update(bytes) }.value
          timeLocal = ENTRY_TIME
        }
      putNextEntry(entry)
      write(bytes)
      closeEntry()
    }

    private fun ZipOutputStream.putDeflated(
      name: String,
      bytes: ByteArray,
    ) {
      putNextEntry(ZipEntry(name).apply { timeLocal = ENTRY_TIME })
      write(bytes)
      closeEntry()
    }
  }
}
