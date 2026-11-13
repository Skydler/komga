package org.gotson.komga.infrastructure.image

import org.apache.tika.config.TikaConfig
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageConverterFitToBoxTest {
  private val imageAnalyzer = ImageAnalyzer()
  private val imageConverter = ImageConverter(imageAnalyzer, ContentDetector(TikaConfig()))

  @Test
  fun `given image that fits in a supported format when fitting then bytes are copied unchanged`() {
    val bytes = makeImage(1000, 1500, "png")

    val result = imageConverter.fitImageToBox(bytes, 1264, 1680, false)

    assertThat(result.bytes).isSameAs(bytes)
    assertThat(result.mediaType).isEqualTo("image/png")
    assertThat(result.dimension).isEqualTo(Dimension(1000, 1500))
  }

  @Test
  fun `given image larger than the box when fitting then it is resized to JPEG keeping the aspect ratio`() {
    val bytes = makeImage(2528, 3360, "png")

    val result = imageConverter.fitImageToBox(bytes, 1264, 1680, false)

    assertThat(result.mediaType).isEqualTo("image/jpeg")
    assertThat(result.dimension).isEqualTo(Dimension(1264, 1680))
    assertThat(imageAnalyzer.getDimension(result.bytes.inputStream())).isEqualTo(result.dimension)
  }

  @Test
  fun `given landscape image when fitting then it fits inside the box with the same aspect ratio`() {
    val bytes = makeImage(4000, 2000, "jpeg")

    val result = imageConverter.fitImageToBox(bytes, 1264, 1680, false)

    assertThat(result.dimension).isEqualTo(Dimension(1264, 632))
    assertThat(imageAnalyzer.getDimension(result.bytes.inputStream())).isEqualTo(Dimension(1264, 632))
  }

  @Test
  fun `given small image in an unsupported format when fitting then it is converted to JPEG without upscaling`() {
    val bytes = makeImage(300, 400, "bmp")

    val result = imageConverter.fitImageToBox(bytes, 1264, 1680, false)

    assertThat(result.mediaType).isEqualTo("image/jpeg")
    assertThat(result.dimension).isEqualTo(Dimension(300, 400))
    assertThat(imageAnalyzer.getDimension(result.bytes.inputStream())).isEqualTo(Dimension(300, 400))
  }

  @Test
  fun `given grayscale when fitting then image is converted to grayscale JPEG`() {
    val bytes = makeImage(200, 300, "png", Color.RED)

    val result = imageConverter.fitImageToBox(bytes, 1264, 1680, true)

    assertThat(result.mediaType).isEqualTo("image/jpeg")
    val decoded = ImageIO.read(result.bytes.inputStream())
    assertThat(decoded.colorModel.numColorComponents).isEqualTo(1)
    assertThat(result.dimension).isEqualTo(Dimension(200, 300))
  }

  @Test
  fun `given empty box when fitting then large image is not resized`() {
    val bytes = makeImage(3000, 4000, "jpeg")

    val result = imageConverter.fitImageToBox(bytes, null, null, false)

    assertThat(result.bytes).isSameAs(bytes)
    assertThat(result.dimension).isEqualTo(Dimension(3000, 4000))
  }

  @Test
  fun `given transparent image when converting then alpha is flattened on white`() {
    val image = BufferedImage(2000, 2000, BufferedImage.TYPE_INT_ARGB) // fully transparent
    val bytes =
      ByteArrayOutputStream().use {
        ImageIO.write(image, "png", it)
        it.toByteArray()
      }

    val result = imageConverter.fitImageToBox(bytes, 1000, 1000, false)

    val decoded = ImageIO.read(result.bytes.inputStream())
    val pixel = Color(decoded.getRGB(500, 500))
    assertThat(pixel.red).isGreaterThan(250)
    assertThat(pixel.green).isGreaterThan(250)
    assertThat(pixel.blue).isGreaterThan(250)
  }

  private fun makeImage(
    width: Int,
    height: Int,
    format: String,
    color: Color = Color.BLUE,
  ): ByteArray {
    val image =
      BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also {
        val g = it.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
      }
    return ByteArrayOutputStream().use {
      ImageIO.write(image, format, it)
      it.toByteArray()
    }
  }
}
