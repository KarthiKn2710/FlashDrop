package com.flashdrop.data

import com.flashdrop.data.repository.DiscoveryRepositoryImpl
import com.flashdrop.data.repository.HistoryRepositoryImpl
import com.flashdrop.data.repository.SelectionRepositoryImpl
import com.flashdrop.data.repository.SettingsRepositoryImpl
import com.flashdrop.data.repository.TrustedDeviceRepositoryImpl
import com.flashdrop.data.transfer.TransferManager
import com.flashdrop.domain.repository.DiscoveryRepository
import com.flashdrop.domain.repository.HistoryRepository
import com.flashdrop.domain.repository.SelectionRepository
import com.flashdrop.domain.repository.SettingsRepository
import com.flashdrop.domain.repository.TransferController
import com.flashdrop.domain.repository.TrustedDeviceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun transferController(impl: TransferManager): TransferController
    @Binds abstract fun discovery(impl: DiscoveryRepositoryImpl): DiscoveryRepository
    @Binds abstract fun selection(impl: SelectionRepositoryImpl): SelectionRepository
    @Binds abstract fun history(impl: HistoryRepositoryImpl): HistoryRepository
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun trustedDevices(impl: TrustedDeviceRepositoryImpl): TrustedDeviceRepository
}
