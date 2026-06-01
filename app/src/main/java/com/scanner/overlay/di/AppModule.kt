package com.scanner.overlay.di

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import com.scanner.overlay.calibration.SewCalibration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val PREFS_NAME = "scanner_prefs"

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideSewCalibration(prefs: SharedPreferences): SewCalibration {
        return SewCalibration(
            targetPackage = prefs.getString("sew_target_package", "") ?: "",
            openModal = Point(
                prefs.getInt("sew_open_modal_x", 0),
                prefs.getInt("sew_open_modal_y", 0)
            ),
            confirm = Point(
                prefs.getInt("sew_confirm_x", 0),
                prefs.getInt("sew_confirm_y", 0)
            )
        )
    }
}
