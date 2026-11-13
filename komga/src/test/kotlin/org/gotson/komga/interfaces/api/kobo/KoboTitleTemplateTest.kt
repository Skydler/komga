package org.gotson.komga.interfaces.api.kobo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class KoboTitleTemplateTest {
  @Test
  fun `given default template when rendering then series and padded number are used`() {
    val title = KoboTitleTemplate.render("{series} {number:4}", "One Piece", "45", "Gear 5")

    assertThat(title).isEqualTo("One Piece 0045")
  }

  @Test
  fun `given each placeholder when rendering then values are substituted`() {
    val title = KoboTitleTemplate.render("{series} #{number} - {title}", "One Piece", "45", "Gear 5")

    assertThat(title).isEqualTo("One Piece #45 - Gear 5")
  }

  @ParameterizedTest
  @CsvSource(
    "45, 4, 0045",
    "45.5, 4, 0045.5",
    "12345, 4, 12345",
    "-1, 3, -001",
    "SP1, 4, SP1",
    "1.2.3, 4, 1.2.3",
    "7, 0, 7",
  )
  fun `given number when padding then integer part is zero-padded or raw value is kept`(
    number: String,
    width: Int,
    expected: String,
  ) {
    assertThat(KoboTitleTemplate.padNumber(number, width)).isEqualTo(expected)
  }

  @Test
  fun `given empty title placeholder when rendering then leftover separators are removed`() {
    assertThat(KoboTitleTemplate.render("{series} - {title}", "One Piece", "45", "")).isEqualTo("One Piece")
    assertThat(KoboTitleTemplate.render("{title} - {series}", "One Piece", "45", "")).isEqualTo("One Piece")
    assertThat(KoboTitleTemplate.render("{series} - {number} - {title}", "One Piece", "", "Gear 5")).isEqualTo("One Piece - Gear 5")
    assertThat(KoboTitleTemplate.render("{series} ({title})", "One Piece", "45", "")).isEqualTo("One Piece")
  }

  @Test
  fun `given template rendering blank when rendering then book title is used`() {
    assertThat(KoboTitleTemplate.render("{series} {number:4}", "", "", "Gear 5")).isEqualTo("Gear 5")
  }

  @ParameterizedTest
  @ValueSource(strings = ["12", "Chapter 12", "chapter 12.5", "Ch. 3", "ch3", "Chap. 7", "Vol. 3", "volume 2", "v10", "#45", "45"])
  fun `given generic title when checking then it is generic`(title: String) {
    assertThat(KoboTitleTemplate.isGenericTitle(title, "45")).isTrue()
  }

  @ParameterizedTest
  @ValueSource(strings = ["Gear 5", "Romance Dawn", "Chapter 12: The Beginning"])
  fun `given meaningful title when checking then it is not generic`(title: String) {
    assertThat(KoboTitleTemplate.isGenericTitle(title, "45")).isFalse()
  }

  @Test
  fun `given title equal to the non-numeric number when checking then it is generic`() {
    assertThat(KoboTitleTemplate.isGenericTitle("SP1", "SP1")).isTrue()
  }

  @Test
  fun `given template when checking for title placeholder then result is correct`() {
    assertThat(KoboTitleTemplate.containsTitle("{series} {number:4}")).isFalse()
    assertThat(KoboTitleTemplate.containsTitle("{series} - {title}")).isTrue()
  }

  @Test
  fun `given valid templates when validating then there are no errors`() {
    assertThat(KoboTitleTemplate.validate("{series} {number:4}")).isEmpty()
    assertThat(KoboTitleTemplate.validate("{title}")).isEmpty()
    assertThat(KoboTitleTemplate.validate("{series} {number} {title}")).isEmpty()
  }

  @Test
  fun `given unknown placeholder when validating then there is an error`() {
    assertThat(KoboTitleTemplate.validate("{series} {volume}")).hasSize(1)
    assertThat(KoboTitleTemplate.validate("{series:2}")).isNotEmpty()
    assertThat(KoboTitleTemplate.validate("{series} {number:x}")).hasSize(1)
  }

  @Test
  fun `given template without series nor title when validating then there is an error`() {
    assertThat(KoboTitleTemplate.validate("{number:4}")).hasSize(1)
    assertThat(KoboTitleTemplate.validate("")).hasSize(1)
  }
}
