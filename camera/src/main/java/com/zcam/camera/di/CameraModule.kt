package com.zcam.camera.di

import com.zcam.camera.CameraRuntime
import com.zcam.camera.CameraRuntimeImpl
import com.zcam.camera.CameraControlManager
import com.zcam.camera.FramePipelineStatusSource
import com.zcam.camera.MjpegFrameSource
import com.zcam.camera.VideoRecordingPipeline
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindCameraRuntime(impl: CameraRuntimeImpl): CameraRuntime

    @Binds
    @Singleton
    abstract fun bindCameraControlManager(impl: CameraRuntimeImpl): CameraControlManager

    @Binds
    @Singleton
    abstract fun bindFrameSource(impl: CameraRuntimeImpl): MjpegFrameSource

    @Binds
    @Singleton
    abstract fun bindFramePipelineStatusSource(impl: CameraRuntimeImpl): FramePipelineStatusSource

    @Binds
    @Singleton
    abstract fun bindVideoRecordingPipeline(impl: CameraRuntimeImpl): VideoRecordingPipeline
}
