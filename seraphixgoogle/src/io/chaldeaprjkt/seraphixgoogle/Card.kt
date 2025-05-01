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

import android.content.Intent
import android.graphics.Bitmap

data class Card(
    var text: String? = null,
    var image: Bitmap? = null,
    var intent: Intent? = null,
    var expiryTimeMillis: Long = 0L,
    var cardType: Int = 0,
    var tapAction: Intent? = null
) {
    companion object {
        const val TYPE_WEATHER = 1
        const val TYPE_CALENDAR = 2
        const val TYPE_ALARM = 3
        const val TYPE_COMMUTE = 4
        const val TYPE_FLIGHT = 5
        const val TYPE_PACKAGE = 6
        const val TYPE_AT_A_GLANCE = 7
    }
}
