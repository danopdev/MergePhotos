
package com.dan.mergephotos

import android.app.Activity
import android.content.Context
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties

/**
Settings: all public var fields will be saved
 */
class Settings( private val activity: Activity) {

    companion object {
        const val SAVE_FOLDER = "/storage/emulated/0/Pictures/MergePhotos"
        const val IMG_SIZE_SMALL = 512
        const val DEFAULT_NAME = "output"

        const val MERGE_PANORAMA = 0
        const val MERGE_LONG_EXPOSURE = 1
        const val MERGE_HDR = 2
        const val MERGE_ALIGN = 3

        const val LONG_EXPOSURE_AVERAGE = 0
        const val LONG_EXPOSURE_NEAREST_TO_AVERAGE = 1
        const val LONG_EXPOSURE_FARTHEST_FROM_AVERAGE = 2

        const val OUTPUT_TYPE_JPEG = 0
        const val OUTPUT_TYPE_PNG = 1
        const val OUTPUT_TYPE_TIFF = 2
    }

    var mergeMode: Int = MERGE_PANORAMA
    var panoramaProjection: Int = 0
    var longexposureAlgorithm: Int = LONG_EXPOSURE_AVERAGE
    var outputType = OUTPUT_TYPE_PNG

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

    fun outputExtension() : String {
        return when(outputType) {
            OUTPUT_TYPE_PNG -> "png"
            OUTPUT_TYPE_TIFF -> "tiff"
            else -> "jpeg"
        }
    }
}
