package org.gotson.komga.infrastructure.image

import io.github.oshai.kotlinlogging.KotlinLogging
import net.coobird.thumbnailator.Thumbnails
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

private const val WEBP_NIGHT_MONKEYS = "com.github.gotson.nightmonkeys.webp.imageio.plugins.WebpImageReaderSpi"

private const val FITTED_JPEG_QUALITY = 0.85F

// formats that every reader supports, and can be sent as-is
private val passThroughMediaTypes = setOf("image/jpeg", "image/png", "image/gif")

class FittedImage(
  val bytes: ByteArray,
  val mediaType: String,
  val dimension: Dimension,
)

@Service
class ImageConverter(
  private val imageAnalyzer: ImageAnalyzer,
  private val contentDetector: ContentDetector,
) {
  val supportedReadFormats by lazy { ImageIO.getReaderFormatNames().toList() }
  val supportedReadMediaTypes by lazy { ImageIO.getReaderMIMETypes().toList() }
  val supportedWriteFormats by lazy { ImageIO.getWriterFormatNames().toList() }
  val supportedWriteMediaTypes by lazy { ImageIO.getWriterMIMETypes().toList() }

  init {
    chooseWebpReader()
    logger.info { "Supported read formats: $supportedReadFormats" }
    logger.info { "Supported read mediaTypes: $supportedReadMediaTypes" }
    logger.info { "Supported write formats: $supportedWriteFormats" }
    logger.info { "Supported write mediaTypes: $supportedWriteMediaTypes" }
  }

  private fun chooseWebpReader() {
    val providers =
      IIORegistry
        .getDefaultInstance()
        .getServiceProviders(
          ImageReaderSpi::class.java,
          { it is ImageReaderSpi && it.mimeTypes.contains("image/webp") },
          false,
        ).asSequence()
        .toList()

    if (providers.size > 1) {
      logger.debug { "WebP reader providers: ${providers.map { it.javaClass.canonicalName }}" }
      providers.firstOrNull { it.javaClass.canonicalName == WEBP_NIGHT_MONKEYS }?.let { nightMonkeys ->
        (providers - nightMonkeys).forEach {
          logger.debug { "Deregister provider: ${it.javaClass.canonicalName}" }
          IIORegistry.getDefaultInstance().deregisterServiceProvider(it)
        }
      }
    }
  }

  private val supportsTransparency = listOf("png")

  fun canConvertMediaType(
    from: String,
    to: String,
  ) = supportedReadMediaTypes.contains(from) && supportedWriteMediaTypes.contains(to)

  fun convertImage(
    imageBytes: ByteArray,
    format: String,
  ): ByteArray =
    ByteArrayOutputStream().use { baos ->
      val image = ImageIO.read(imageBytes.inputStream())

      val result =
        if (!supportsTransparency.contains(format) && containsAlphaChannel(image)) {
          if (containsTransparency(image))
            logger.info { "Image contains alpha channel but is not opaque, visual artifacts may appear" }
          else
            logger.info { "Image contains alpha channel but is opaque, conversion should not generate any visual artifacts" }
          BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().drawImage(image, 0, 0, Color.WHITE, null)
          }
        } else {
          image
        }

      ImageIO.write(result, format, baos)

      baos.toByteArray()
    }

  fun resizeImageToByteArray(
    imageBytes: ByteArray,
    format: ImageType,
    size: Int,
  ): ByteArray {
    val builder = resizeImageBuilder(imageBytes, format, size) ?: return imageBytes

    return ByteArrayOutputStream().use {
      builder.toOutputStream(it)
      it.toByteArray()
    }
  }

  fun resizeImageToBufferedImage(
    imageBytes: ByteArray,
    format: ImageType,
    size: Int,
  ): BufferedImage {
    val builder = resizeImageBuilder(imageBytes, format, size) ?: return ImageIO.read(imageBytes.inputStream())

    return builder.asBufferedImage()
  }

  private fun resizeImageBuilder(
    imageBytes: ByteArray,
    format: ImageType,
    size: Int,
  ): Thumbnails.Builder<out InputStream>? {
    val longestEdge =
      imageAnalyzer.getDimension(imageBytes.inputStream())?.let {
        val mediaType = contentDetector.detectMediaType(imageBytes.inputStream())
        val longestEdge = max(it.height, it.width)
        // don't resize if source and target format is the same, and source is smaller than desired
        if (mediaType == format.mediaType && longestEdge <= size) return null
        longestEdge
      }

    // prevent upscaling
    val resizeTo = if (longestEdge != null) min(longestEdge, size) else size

    return Thumbnails
      .of(imageBytes.inputStream())
      .size(resizeTo, resizeTo)
      .imageType(BufferedImage.TYPE_INT_ARGB)
      .outputFormat(format.imageIOFormat)
  }

  /**
   * Fit an image inside a [maxWidth] × [maxHeight] box, keeping the aspect ratio. Images are never upscaled.
   *
   * The original bytes are returned when the image already fits, its format is widely supported (JPEG, PNG, GIF),
   * and [grayscale] is off. Otherwise the image is re-encoded to JPEG, with alpha flattened on a white background.
   *
   * @param maxWidth maximum width, or null to disable resizing
   * @param maxHeight maximum height, or null to disable resizing
   * @throws IllegalArgumentException if the image cannot be read
   */
  fun fitImageToBox(
    imageBytes: ByteArray,
    maxWidth: Int?,
    maxHeight: Int?,
    grayscale: Boolean,
  ): FittedImage {
    val mediaType = contentDetector.detectMediaType(imageBytes.inputStream())
    val sourceDimension =
      imageAnalyzer.getDimension(imageBytes.inputStream())
        ?: throw IllegalArgumentException("Cannot read image dimensions, media type: $mediaType")

    val scale =
      if (maxWidth == null || maxHeight == null)
        1.0
      else
        minOf(1.0, maxWidth.toDouble() / sourceDimension.width, maxHeight.toDouble() / sourceDimension.height)

    if (scale >= 1.0 && mediaType in passThroughMediaTypes && !grayscale)
      return FittedImage(imageBytes, mediaType, sourceDimension)

    val source = ImageIO.read(imageBytes.inputStream()) ?: throw IllegalArgumentException("Cannot read image, media type: $mediaType")
    val targetWidth = max(1, (sourceDimension.width * scale).roundToInt())
    val targetHeight = max(1, (sourceDimension.height * scale).roundToInt())

    val resized =
      if (scale < 1.0)
        Thumbnails
          .of(source)
          .forceSize(targetWidth, targetHeight)
          .asBufferedImage()
      else
        source

    // draw on an opaque canvas: flattens alpha on white, and converts to grayscale if needed
    val flattened =
      BufferedImage(resized.width, resized.height, if (grayscale) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB).also {
        val graphics = it.createGraphics()
        try {
          graphics.drawImage(resized, 0, 0, Color.WHITE, null)
        } finally {
          graphics.dispose()
        }
      }

    return FittedImage(writeJpeg(flattened, FITTED_JPEG_QUALITY), ImageType.JPEG.mediaType, Dimension(flattened.width, flattened.height))
  }

  private fun writeJpeg(
    image: BufferedImage,
    quality: Float,
  ): ByteArray =
    ByteArrayOutputStream().use { baos ->
      val writer = ImageIO.getImageWritersByFormatName(ImageType.JPEG.imageIOFormat).next()
      try {
        ImageIO.createImageOutputStream(baos).use { ios ->
          writer.output = ios
          val params =
            writer.defaultWriteParam.apply {
              compressionMode = ImageWriteParam.MODE_EXPLICIT
              compressionQuality = quality
            }
          writer.write(null, IIOImage(image, null, null), params)
        }
      } finally {
        writer.dispose()
      }
      baos.toByteArray()
    }

  private fun containsAlphaChannel(image: BufferedImage): Boolean = image.colorModel.hasAlpha()

  private fun containsTransparency(image: BufferedImage): Boolean {
    for (x in 0 until image.width) {
      for (y in 0 until image.height) {
        val pixel = image.getRGB(x, y)
        if (pixel shr 24 == 0x00) return true
      }
    }
    return false
  }
}
