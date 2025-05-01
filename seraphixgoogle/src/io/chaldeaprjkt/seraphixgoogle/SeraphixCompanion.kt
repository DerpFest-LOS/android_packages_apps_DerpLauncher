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

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.TimeUnit

object SeraphixCompanion {
    fun Context.isPackageEnabled(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun ViewGroup.allChildren(): List<View> {
        val children = mutableListOf<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            children.add(child)
            if (child is ViewGroup) {
                children.addAll(child.allChildren())
            }
        }
        return children
    }

    fun View.bitmap(): Bitmap? {
        return when (this) {
            is ImageView -> drawable?.let { drawableToBitmap(it) }
            else -> null
        }
    }

    fun View.string(): String? {
        return when (this) {
            is TextView -> text.toString()
            else -> null
        }
    }

    inline fun <reified T : View> View.takeByName(name: String): T? {
        return if (this is T && contentDescription?.toString() == name) this else null
    }

    fun isCardExpired(card: Card): Boolean {
        return card.expiryTimeMillis > 0 && System.currentTimeMillis() > card.expiryTimeMillis
    }

    fun getCardTypeName(card: Card): String {
        return when (card.cardType) {
            Card.TYPE_WEATHER -> "Weather"
            Card.TYPE_CALENDAR -> "Calendar"
            Card.TYPE_ALARM -> "Alarm"
            Card.TYPE_COMMUTE -> "Commute"
            Card.TYPE_FLIGHT -> "Flight"
            Card.TYPE_PACKAGE -> "Package"
            Card.TYPE_AT_A_GLANCE -> "At a Glance"
            else -> "Unknown"
        }
    }

    fun formatExpiryTime(expiryTimeMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = expiryTimeMillis - now
        return when {
            diff < 0 -> "Expired"
            diff < TimeUnit.MINUTES.toMillis(1) -> "Expires in ${TimeUnit.MILLISECONDS.toSeconds(diff)}s"
            diff < TimeUnit.HOURS.toMillis(1) -> "Expires in ${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
            else -> "Expires in ${TimeUnit.MILLISECONDS.toHours(diff)}h"
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return null
        }
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
