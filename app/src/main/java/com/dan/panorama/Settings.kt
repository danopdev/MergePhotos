package com.dan.panorama

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
        const val SAVE_FOLDER = "/storage/emulated/0/Panorama"
        const val SAVE_QUALITY = 100
    }

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