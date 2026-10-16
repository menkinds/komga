package org.gotson.komga.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SeriesNameNormalizerTest {
  @Test
  fun `completion suffix and volume marker normalize to the same key`() {
    assertThat(SeriesNameNormalizer.normalize("제목 제01~09권"))
      .isEqualTo(SeriesNameNormalizer.normalize("제목 제01~09권 (완)"))
    assertThat(SeriesNameNormalizer.normalize("제목 제01~09권 (完)"))
      .isEqualTo(SeriesNameNormalizer.normalize("제목 완결"))
    assertThat(SeriesNameNormalizer.normalize("My Series"))
      .isEqualTo(SeriesNameNormalizer.normalize("My Series (완)"))
  }

  @Test
  fun `similar names stay distinct`() {
    assertThat(SeriesNameNormalizer.normalize("제목"))
      .isNotEqualTo(SeriesNameNormalizer.normalize("제목 외전"))
    assertThat(SeriesNameNormalizer.normalize("Alpha"))
      .isNotEqualTo(SeriesNameNormalizer.normalize("Alpha Beta"))
    assertThat(SeriesNameNormalizer.normalize("Alpha"))
      .isNotEqualTo(SeriesNameNormalizer.normalize("Alphabet"))
    assertThat(SeriesNameNormalizer.normalize("시즌"))
      .isNotEqualTo(SeriesNameNormalizer.normalize("시즌2"))
  }
}
