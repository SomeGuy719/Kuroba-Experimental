package com.github.adamantcheese.database.di

import android.app.Application
import com.github.adamantcheese.database.di.annotation.LoggerTagPrefix
import com.github.adamantcheese.database.di.annotation.VerboseLogs
import com.github.adamantcheese.database.repository.YoutubeLinkExtraContentRepository
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(
        modules = [
            DatabaseModule::class
        ]
)
interface DatabaseComponent {
    fun inject(application: Application)

    fun getYoutubeLinkExtraContentRepository(): YoutubeLinkExtraContentRepository

    @Component.Builder
    interface Builder {
        @BindsInstance
        fun application(application: Application): Builder
        @BindsInstance
        fun loggerTagPrefix(@LoggerTagPrefix loggerTagPrefix: String): Builder
        @BindsInstance
        fun verboseLogs(@VerboseLogs verboseLogs: Boolean): Builder

        fun build(): DatabaseComponent
    }

}