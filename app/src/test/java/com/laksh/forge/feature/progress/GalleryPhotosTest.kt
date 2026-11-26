package com.laksh.forge.feature.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class GalleryPhotosTest {

    @Test
    fun `EXIF DateTimeOriginal becomes the capture day`() {
        assertThat(GalleryPhotos.parseExifDate("2026:03:08 07:12:45")).isEqualTo(LocalDate.of(2026, 3, 8))
    }

    @Test
    fun `unset camera clocks read as no date, so the person is asked`() {
        assertThat(GalleryPhotos.parseExifDate("0000:00:00 00:00:00")).isNull()
        assertThat(GalleryPhotos.parseExifDate("1970:01:01 00:00:00")).isNull()
        assertThat(GalleryPhotos.parseExifDate("    ")).isNull()
        assertThat(GalleryPhotos.parseExifDate("not a date")).isNull()
    }

    @Test
    fun `gallery file names are dated and safe`() {
        assertThat(GalleryPhotos.displayName(LocalDate.of(2026, 11, 1), "Front")).isEqualTo("Forge progress 2026-11-01 front.jpg")
    }
}
