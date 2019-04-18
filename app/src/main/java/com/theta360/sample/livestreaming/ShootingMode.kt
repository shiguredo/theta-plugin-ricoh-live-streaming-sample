package com.theta360.sample.livestreaming

enum class ShootingMode(
        val value: String,
        val width: Int,
        val height: Int,
        val videoSize: String
) {
    RIC_MOVIE_PREVIEW_640("RicMoviePreview640", 640, 320, "640x320"),
    RIC_MOVIE_PREVIEW_1024("RicMoviePreview1024", 1024, 512, "1024x512"),
    RIC_MOVIE_PREVIEW_1920("RicMoviePreview1920", 1920, 960, "1920x960"),
    RIC_MOVIE_PREVIEW_3840("RicMoviePreview3840", 3840, 1920, "3840x1920"),

    RIC_MOVIE_RECORDING_4K_EQUI("RicMovieRecording4kEqui", 3840, 1920, "3840x1920"),
    RIC_MOVIE_RECORDING_4K_DUAL("RicMovieRecording4kDual", 3840, 1920, "3840x1920"),
    RIC_MOVIE_RECORDING_2K_EQUI("RicMovieRecording2kEqui", 1920, 960, "1920x960"),
    RIC_MOVIE_RECORDING_2K_DUAL("RicMovieRecording2kDual", 1920, 960, "1920x960"),

    RIC_STILL_PREVIEW_1920("RicStillPreview1920", 1920, 960, "1920x960"),
    // Does not exist?
    // RIC_STILL_PREVIEW_3840("RicStillPreview3840", 3840, 1920, "3840x1920")
}
