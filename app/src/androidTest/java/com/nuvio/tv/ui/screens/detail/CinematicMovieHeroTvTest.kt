package com.nuvio.tv.ui.screens.detail

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NavigationMotion
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CinematicMovieHeroTvTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val meta = Meta(id="fixture:cinematic-film", type=ContentType.MOVIE,
        name="Uma jornada pelo Brasil", poster=null, posterShape=PosterShape.POSTER,
        background=null, logo=null, description="Uma viagem entre cidades, paisagens e histórias. ".repeat(30),
        releaseInfo="2026", imdbRating=8.1f, genres=listOf("Drama"), runtime="124 min",
        director=listOf("Direção de teste"), cast=emptyList(), videos=emptyList(), country=null,
        awards=null, language=null, links=emptyList())

    @Test fun movieActionsRemainVisibleAndDpadReachesTrailerAndLibraryLongPress() {
        var save=0; var lists=0; var trailer=0
        setContent(onSave={save++}, onLists={lists++}, onTrailer={trailer++})
        compose.onNodeWithText("Assistir").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.hero_play_trailer)).assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        compose.runOnIdle { assertEquals(1, lists); assertEquals(0, save) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, save) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, trailer) }
        capture("cinematic-movie-hero-tv")
    }

    @Test fun unavailableTrailerCreatesNoFocusableAction() {
        setContent(trailerAvailable=false)
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.hero_play_trailer)).assertDoesNotExist()
        compose.onNodeWithText("Assistir").assertIsDisplayed()
    }

    @Test fun longSynopsisOpensThroughDpadWithoutActivatingPlayback() {
        var synopsis=0; var play=0
        setContent(onSynopsis={synopsis++}, onPlay={play++})
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.hero_synopsis_read_more)).assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, synopsis); assertEquals(0, play) }
    }

    private fun setContent(trailerAvailable:Boolean=true, onSave:()->Unit={}, onLists:()->Unit={},
        onTrailer:()->Unit={}, onSynopsis:()->Unit={}, onPlay:()->Unit={}) {
        val playFocus=FocusRequester()
        compose.setContent {
            NuvioTheme(navigationMotion=NavigationMotion.OFF) {
                Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
                    HeroContentSection(meta=meta, nextEpisode=null,
                        nextToWatch=NextToWatch(null,false,null,null,null,"Assistir"),
                        onPlayClick=onPlay, isInLibrary=false, onToggleLibrary=onSave,
                        onLibraryLongPress=onLists, isMovieWatched=false, isMovieWatchedPending=false,
                        onToggleMovieWatched={}, trailerAvailable=trailerAvailable, onTrailerClick=onTrailer,
                        playButtonFocusRequester=playFocus, onShowFullDescription=onSynopsis)
                }
                LaunchedEffect(Unit) { playFocus.requestFocusAfterFrames() }
            }
        }
    }

    private fun capture(name:String) {
        compose.waitForIdle()
        val directory=instrumentation.targetContext.getExternalFilesDir(null)!!
        File(directory,"$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
