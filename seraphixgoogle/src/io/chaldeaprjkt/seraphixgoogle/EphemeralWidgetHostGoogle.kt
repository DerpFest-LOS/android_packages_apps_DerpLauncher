/*
 * Copyright (C) 2021 Chaldeaprjkt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.seraphixgoogle

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class EphemeralWidgetHostGoogle(context: Context?, hostId: Int) : AppWidgetHost(context, hostId) {
    private var listener: DataProviderListener? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var expiryCheckTask: ScheduledFuture<*>? = null
    private var currentCard: Card? = null

    override fun onCreateView(
        context: Context?,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView = EphemeralWidgetHostViewGoogle(context).setOnUpdateAppWidget(listener)

    fun setOnDataUpdated(listener: DataProviderListener? = null) {
        this.listener = listener
    }

    override fun startListening() {
        super.startListening()
        startExpiryCheck()
    }

    override fun stopListening() {
        super.stopListening()
        stopExpiryCheck()
    }

    fun updateCard(card: Card) {
        currentCard = card
        listener?.onDataUpdated(card)
    }

    private fun startExpiryCheck() {
        stopExpiryCheck()
        expiryCheckTask = scheduler.scheduleAtFixedRate({
            currentCard?.let { card ->
                if (SeraphixCompanion.isCardExpired(card)) {
                    Log.i(TAG, "Card expired: ${SeraphixCompanion.getCardTypeName(card)}")
                    listener?.onCardExpired(card)
                }
            }
        }, 1, 1, TimeUnit.MINUTES)
    }

    private fun stopExpiryCheck() {
        expiryCheckTask?.cancel(false)
        expiryCheckTask = null
    }

    override fun deleteHost() {
        stopExpiryCheck()
        super.deleteHost()
    }

    companion object {
        private const val TAG = "EphemeralWidgetHostGoogle"
    }
}
