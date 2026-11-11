package com.flashdrop.connectivity.lan

import com.flashdrop.connectivity.api.TransferTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Each transport adapter contributes itself to the set the selection policy chooses from. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LanModule {
    @Binds
    @IntoSet
    abstract fun bindLanTransport(transport: LanTransport): TransferTransport
}
