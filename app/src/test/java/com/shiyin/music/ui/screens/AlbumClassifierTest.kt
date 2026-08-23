package com.shiyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumClassifierTest {

    // ── v1.2.0 #3: 合集启发式 isCompilationAlbum(distinctArtists, trackCount) ──────
    @Test fun compilation_folderManyArtists() = assertTrue(isCompilationAlbum(7, 7))
    @Test fun compilation_vaEp4Artists() = assertTrue(isCompilationAlbum(4, 4))
    @Test fun compilation_halfDistinctBoundary() = assertTrue(isCompilationAlbum(3, 6))

    @Test fun notCompilation_realAlbumOneArtist() = assertFalse(isCompilationAlbum(1, 10))
    @Test fun notCompilation_albumWithFeats() = assertFalse(isCompilationAlbum(2, 20))
    @Test fun notCompilation_tooFewTracks() = assertFalse(isCompilationAlbum(3, 3))
    @Test fun notCompilation_distinctBelow3() = assertFalse(isCompilationAlbum(2, 10))
    @Test fun notCompilation_underHalfRatio() = assertFalse(isCompilationAlbum(3, 10))

    @Test fun categoryLabel_compilation() = assertEquals("合集", categoryLabel(AlbumCategory.Compilation))
    @Test fun categoryLabel_albumEpSingle() {
        assertEquals("专辑", categoryLabel(AlbumCategory.Album))
        assertEquals("EP", categoryLabel(AlbumCategory.EP))
        assertEquals("单曲", categoryLabel(AlbumCategory.Single))
    }
}
