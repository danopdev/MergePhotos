
package com.dan.mergephotos

import android.app.Activity
import android.content.Context
import android.os.Environment
import java.io.File
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties

/**
Settings: all public var fields will be saved
 */
class Settings( private val activity: Activity) {

    companion object {
        val SAVE_FOLDER = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MergePhotos")
        const val DEFAULT_NAME = "output"
        const val EXT_JPEG = "jpeg"

        const val IMG_SIZE_SMALL = 1024

        const val MERGE_PANORAMA = 0
        const val MERGE_LONG_EXPOSURE = 1
        const val MERGE_HDR = 2
        const val MERGE_ALIGN = 3
        const val MERGE_FOCUS_STACK = 4

        const val LONG_EXPOSURE_AVERAGE = 0
        const val LONG_EXPOSURE_NEAREST_TO_AVERAGE = 1
        const val LONG_EXPOSURE_LIGHT = 2
        const val LONG_EXPOSURE_DARK = 3
    }

    var mergeMode: Int = MERGE_PANORAMA
    var panoramaProjection: Int = 0
    var longexposureAlgorithm: Int = LONG_EXPOSURE_AVERAGE
    var jpegQuality = 95

    init {
        loadProperties()
    }

    private fun forEachSettingProperty( listener: (KMutableProperty<*>)->Unit ) {
        for( member in this::class.declaredMemberProperties ) {
            if (member.visibility == KVisibility.PUBLIC && member is KMutableProperty<*>) {
                listener.invoke(member)
            }
        }
    }

    private fun loadProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)

        forEachSettingProperty { property ->
            when( property.returnType ) {
                Boolean::class.createType() -> property.setter.call( this, preferences.getBoolean( property.name, property.getter.call(this) as Boolean ) )
                Int::class.createType() -> property.setter.call( this, preferences.getInt( property.name, property.getter.call(this) as Int ) )
                Long::class.createType() -> property.setter.call( this, preferences.getLong( property.name, property.getter.call(this) as Long ) )
                Float::class.createType() -> property.setter.call( this, preferences.getFloat( property.name, property.getter.call(this) as Float ) )
                String::class.createType() -> property.setter.call( this, preferences.getString( property.name, property.getter.call(this) as String ) )
            }
        }
    }

    fun saveProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        val editor = preferences.edit()

        forEachSettingProperty { property ->
            when( property.returnType ) {
                Boolean::class.createType() -> editor.putBoolean( property.name, property.getter.call(this) as Boolean )
                Int::class.createType() -> editor.putInt( property.name, property.getter.call(this) as Int )
                Long::class.createType() -> editor.putLong( property.name, property.getter.call(this) as Long )
                Float::class.createType() -> editor.putFloat( property.name, property.getter.call(this) as Float )
                String::class.createType() -> editor.putString( property.name, property.getter.call(this) as String )
            }
        }

        editor.apply()
    }
}
