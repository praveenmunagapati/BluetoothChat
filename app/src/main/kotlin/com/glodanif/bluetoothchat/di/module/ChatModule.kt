package com.glodanif.bluetoothchat.di.module

import com.glodanif.bluetoothchat.data.model.*
import com.glodanif.bluetoothchat.di.PerActivity
import com.glodanif.bluetoothchat.ui.activity.ChatActivity
import com.glodanif.bluetoothchat.ui.presenter.ChatPresenter
import com.glodanif.bluetoothchat.ui.viewmodel.converter.ChatMessageConverter
import dagger.Module
import dagger.Provides

@Module
class ChatModule(private val address: String, private val activity: ChatActivity) {

    @Provides
    @PerActivity
    internal fun providePresenter(messages: MessagesStorage, conversations: ConversationsStorage,
                                  scanner: BluetoothScanner, connector: BluetoothConnector,
                                  preferences: UserPreferences, converter: ChatMessageConverter): ChatPresenter =
            ChatPresenter(address, activity, conversations, messages, scanner, connector, preferences, converter)

    @Provides
    @PerActivity
    internal fun provideScanner(): BluetoothScanner = BluetoothScannerImpl(activity)
}
